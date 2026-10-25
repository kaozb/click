package com.mclick.timer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无障碍服务：负责悬浮准星、单点定时以及手势点击。
 * 悬浮窗使用 TYPE_ACCESSIBILITY_OVERLAY，因此无需“显示在其他应用上层”权限。
 */
public class ClickAccessibilityService extends AccessibilityService {

    static final String PREF = "cfg";
    static final String KEY_ARMED = "armed";
    static final String KEY_TARGET = "target";
    static final String KEY_X = "x";
    static final String KEY_Y = "y";
    static final String KEY_OFFSET = "offset";
    static final String KEY_TIME_TEXT = "time_text";
    static final String KEY_TIMES = "times";
    static final String KEY_SELECTED = "selected";
    static final String KEY_OVERLAY = "overlay_shown";

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?$");

    private static volatile ClickAccessibilityService sInstance;

    static volatile boolean armed;
    static volatile boolean overlayShown;
    static volatile long targetWallMs;
    static volatile long lastFireWallMs;

    private WindowManager wm;
    private View overlay;
    private TextView label;
    private WindowManager.LayoutParams params;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread scheduler;
    private volatile boolean running;
    private volatile int offsetMs;
    private int generation;
    private Runnable pendingHide;
    private float[] clickPoint;

    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static ClickAccessibilityService getInstance() {
        return sInstance;
    }

    // ---------------- 时间解析 ----------------

    static boolean parseSpec(String s, int[] out) {
        if (s == null) {
            return false;
        }
        Matcher m = TIME_PATTERN.matcher(s.trim());
        if (!m.matches()) {
            return false;
        }
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        int sec = Integer.parseInt(m.group(3));
        if (h > 23 || mi > 59 || sec > 59) {
            return false;
        }
        int ms = 0;
        String frac = m.group(4);
        if (frac != null) {
            while (frac.length() < 3) {
                frac = frac + "0";
            }
            ms = Integer.parseInt(frac);
        }
        out[0] = h;
        out[1] = mi;
        out[2] = sec;
        out[3] = ms;
        return true;
    }

    static String formatSpec(int h, int m, int s, int ms) {
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, ms);
    }

    static long nextOccurrence(int h, int m, int s, int ms) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, s);
        c.set(Calendar.MILLISECOND, ms);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 50L) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static boolean isTomorrow(long wall) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(wall);
        return a.get(Calendar.YEAR) != b.get(Calendar.YEAR)
                || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR);
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        offsetMs = sp.getInt(KEY_OFFSET, 0);
        long savedTarget = sp.getLong(KEY_TARGET, 0L);

        if (sp.getBoolean(KEY_ARMED, false) && savedTarget > System.currentTimeMillis() + 20L) {
            armed = true;
            overlayShown = true;
            targetWallMs = savedTarget;
            showOverlay();
            updateLabel();
            startTicker();
            startScheduler(savedTarget);
        } else if (sp.getBoolean(KEY_OVERLAY, false)) {
            overlayShown = true;
            showOverlay();
            showIdleLabel();
        } else {
            sp.edit().putBoolean(KEY_ARMED, false).apply();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopAll();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopAll();
        sInstance = null;
        super.onDestroy();
    }

    private void stopAll() {
        running = false;
        generation++;
        Thread t = scheduler;
        scheduler = null;
        if (t != null) {
            t.interrupt();
        }
        main.removeCallbacksAndMessages(null);
        removeOverlay();
        armed = false;
        overlayShown = false;
        targetWallMs = 0L;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ARMED, false)
                .putBoolean(KEY_OVERLAY, false)
                .apply();
    }

    // ---------------- 对外控制 ----------------

    /** 只显示悬浮准星，便于拖动定位，不启动执行。 */
    void showOverlayOnly() {
        if (armed) {
            return;
        }
        overlayShown = true;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_OVERLAY, true).apply();
        showOverlay();
        showIdleLabel();
    }

    /** 对选中的单个时间启动一次执行。 */
    void arm(String spec, int offset) {
        int[] t = new int[4];
        if (!parseSpec(spec, t)) {
            toast("时间格式不正确");
            return;
        }
        long target = nextOccurrence(t[0], t[1], t[2], t[3]);
        offsetMs = offset;
        armed = true;
        overlayShown = true;
        targetWallMs = target;

        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ARMED, true)
                .putLong(KEY_TARGET, target)
                .putInt(KEY_OFFSET, offset)
                .putBoolean(KEY_OVERLAY, true)
                .apply();

        showOverlay();
        updateLabel();
        startTicker();
        startScheduler(target);

        toast("已启动 " + (isTomorrow(target) ? "明天 " : "今天 ")
                + fmt.format(new Date(target)) + "，到点在准星位置点击一次");
    }

    /** 停止执行，但保留悬浮准星便于下次启动。 */
    void stopArm() {
        running = false;
        generation++;
        Thread t = scheduler;
        scheduler = null;
        if (t != null) {
            t.interrupt();
        }
        main.removeCallbacksAndMessages(null);
        armed = false;
        targetWallMs = 0L;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ARMED, false).apply();
        if (overlayShown) {
            restoreOverlay();
            showIdleLabel();
        }
    }

    /** 停止并隐藏悬浮准星。 */
    void hideOverlay() {
        stopArm();
        overlayShown = false;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_OVERLAY, false).apply();
        removeOverlay();
    }

    // ---------------- 悬浮按钮 ----------------

    private void showOverlay() {
        if (wm == null) {
            return;
        }
        if (overlay == null) {
            buildOverlay();
        }
        if (overlay.getParent() == null) {
            try {
                wm.addView(overlay, params);
            } catch (Throwable ignored) {
            }
        }
    }

    private void restoreOverlay() {
        if (overlay != null && overlay.getParent() == null && wm != null) {
            try {
                wm.addView(overlay, params);
            } catch (Throwable ignored) {
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        CrossView dot = new CrossView(this);
        box.addView(dot, new LinearLayout.LayoutParams(dp(64), dp(64)));

        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(6));
        label.setBackground(bg);
        label.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        box.addView(label, lp);

        overlay = box;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        params.x = Math.max(0, Math.min(sp.getInt(KEY_X, dm.widthPixels / 2 - dp(32)),
                dm.widthPixels - dp(64)));
        params.y = Math.max(0, Math.min(sp.getInt(KEY_Y, dm.heightPixels / 2 - dp(50)),
                dm.heightPixels - dp(90)));

        box.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downX, downY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downX = params.x;
                        downY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                            moved = true;
                        }
                        if (moved) {
                            DisplayMetrics d = getResources().getDisplayMetrics();
                            int maxX = Math.max(0, d.widthPixels - v.getWidth());
                            int maxY = Math.max(0, d.heightPixels - v.getHeight());
                            params.x = Math.max(0, Math.min(maxX, downX + (int) dx));
                            params.y = Math.max(0, Math.min(maxY, downY + (int) dy));
                            try {
                                wm.updateViewLayout(overlay, params);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                                    .putInt(KEY_X, params.x)
                                    .putInt(KEY_Y, params.y)
                                    .apply();
                        } else if (armed) {
                            long rem = targetWallMs - System.currentTimeMillis();
                            if (rem > 0) {
                                toast("距离点击还有 " + (rem / 1000) + "."
                                        + String.format(Locale.US, "%03d", rem % 1000) + " 秒");
                            }
                        } else {
                            toast("已定位。拖动可调整位置，点「启动选中时间」开始");
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void removeOverlay() {
        if (overlay != null && overlay.getParent() != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
    }

    private void showIdleLabel() {
        if (label != null) {
            label.setText("待启动 · 拖到目标位置");
        }
    }

    private void updateLabel() {
        if (label == null) {
            return;
        }
        if (!armed || targetWallMs <= 0) {
            showIdleLabel();
            return;
        }
        long rem = Math.max(0L, targetWallMs - System.currentTimeMillis());
        label.setText(fmt.format(new Date(targetWallMs)) + " · "
                + (rem / 1000) + "." + String.format(Locale.US, "%03d", rem % 1000) + "s");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!armed) {
                return;
            }
            updateLabel();
            long rem = targetWallMs - System.currentTimeMillis();
            long delay;
            if (rem > 300_000L) {
                delay = 5000L;
            } else if (rem > 60_000L) {
                delay = 1000L;
            } else if (rem > 10_000L) {
                delay = 250L;
            } else if (rem > 1_000L) {
                delay = 100L;
            } else {
                delay = 50L;
            }
            main.postDelayed(this, delay);
        }
    };

    private void startTicker() {
        main.removeCallbacks(tick);
        main.post(tick);
    }

    // ---------------- 毫秒调度 ----------------

    private void startScheduler(long targetWall) {
        running = false;
        Thread old = scheduler;
        if (old != null) {
            old.interrupt();
        }
        if (pendingHide != null) {
            main.removeCallbacks(pendingHide);
        }
        offsetMs = getSharedPreferences(PREF, MODE_PRIVATE).getInt(KEY_OFFSET, 0);

        final long delayMs = targetWall - System.currentTimeMillis();
        final long fireAtNanos = SystemClock.elapsedRealtimeNanos()
                + delayMs * 1_000_000L - offsetMs * 1_000_000L;
        final int myGen = ++generation;
        final long target = targetWall;

        running = true;
        Thread t = new Thread(() -> {
            try {
                // 提前约 250ms 移除悬浮窗，避免它挡住注入的点击
                final Runnable hide = () -> {
                    if (myGen == generation) {
                        hideForClick();
                    }
                };
                pendingHide = hide;
                main.postDelayed(hide, Math.max(0L, delayMs - 250L - offsetMs));

                // 先粗睡，最后 0.4ms 自旋，避免线程唤醒抖动
                while (running) {
                    long rem = fireAtNanos - SystemClock.elapsedRealtimeNanos();
                    if (rem <= 0) {
                        break;
                    }
                    if (rem > 3_000_000L) {
                        LockSupport.parkNanos(rem - 2_000_000L);
                    } else if (rem > 400_000L) {
                        LockSupport.parkNanos(100_000L);
                    } else {
                        Thread.yield();
                    }
                }
                if (myGen != generation || !running || !armed || targetWallMs != target) {
                    return;
                }
                fire();
            } catch (Throwable ignored) {
            }
        }, "ms-click-scheduler");
        t.setPriority(Thread.MAX_PRIORITY);
        t.setDaemon(true);
        scheduler = t;
        t.start();
    }

    private void hideForClick() {
        if (overlay != null && overlay.getParent() != null) {
            int[] loc = new int[2];
            overlay.getLocationOnScreen(loc);
            int w = overlay.getWidth();
            clickPoint = new float[]{
                    loc[0] + (w > 0 ? w / 2f : dp(32)),
                    loc[1] + dp(32)
            };
            try {
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
    }

    private void fire() {
        armed = false;
        targetWallMs = 0L;
        lastFireWallMs = System.currentTimeMillis();
        main.removeCallbacks(tick);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ARMED, false).apply();

        float x;
        float y;
        if (clickPoint != null) {
            x = clickPoint[0];
            y = clickPoint[1];
        } else if (overlay != null) {
            int[] loc = new int[2];
            overlay.getLocationOnScreen(loc);
            x = loc[0] + overlay.getWidth() / 2f;
            y = loc[1] + dp(32);
        } else {
            x = params.x + dp(32);
            y = params.y + dp(32);
        }

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 24L);
        try {
            boolean ok = dispatchGesture(
                    new GestureDescription.Builder().addStroke(stroke).build(),
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            finishFire(true);
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            finishFire(false);
                        }
                    }, null);
            if (!ok) {
                main.post(() -> finishFire(false));
            }
        } catch (Throwable e) {
            main.post(() -> finishFire(false));
        }
    }

    private void finishFire(boolean ok) {
        final String text = ok
                ? "已点击 " + fmt.format(new Date(lastFireWallMs))
                : "点击失败，请确认无障碍服务仍然开启";
        toast(text);
        if (label != null) {
            label.setText(text);
        }
        main.postDelayed(() -> {
            if (overlayShown) {
                restoreOverlay();
            }
        }, 150);
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /** 画一个半透明准星，中心点就是点击位置。 */
    private static class CrossView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        CrossView(Context c) {
            super(c);
            fill.setColor(0x66E53935);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2f);
            line.setShadowLayer(4f, 0f, 0f, 0x99000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 4f * getResources().getDisplayMetrics().density;
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, line);
            float arm = r * 0.55f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, line);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, line);
        }
    }
}
