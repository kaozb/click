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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * 无障碍服务：负责悬浮按钮、毫秒级定时以及手势点击。
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

    private static volatile ClickAccessibilityService sInstance;

    static volatile boolean armed;
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
    private volatile Runnable pendingHide;
    private float[] clickPoint;

    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static ClickAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        offsetMs = sp.getInt(KEY_OFFSET, 0);
        long savedTarget = sp.getLong(KEY_TARGET, 0L);
        if (sp.getBoolean(KEY_ARMED, false) && savedTarget > System.currentTimeMillis()) {
            armed = true;
            targetWallMs = savedTarget;
            showOverlay();
            startScheduler(savedTarget);
            startTicker();
        } else {
            armed = false;
            targetWallMs = 0L;
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
        targetWallMs = 0L;
    }

    /** 由界面调用：按当天/次日的时:分:秒.毫秒 设定并显示悬浮按钮。 */
    void arm(int hour, int minute, int second, int millis, int offset) {
        offsetMs = offset;
        long now = System.currentTimeMillis();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, second);
        c.set(java.util.Calendar.MILLISECOND, millis);
        if (c.getTimeInMillis() <= now + 50L) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        targetWallMs = c.getTimeInMillis();
        armed = true;

        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_ARMED, true)
                .putLong(KEY_TARGET, targetWallMs)
                .putInt(KEY_OFFSET, offset)
                .apply();

        showOverlay();
        updateLabel();
        startScheduler(targetWallMs);
        startTicker();

        long diff = targetWallMs - now;
        String when = diff < 86400000L ? "今天" : "明天";
        toast("已设定 " + when + " " + fmt.format(new Date(targetWallMs))
                + "，把悬浮按钮拖到要点击的位置即可");
    }

    void disarm() {
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
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_ARMED, false).apply();
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
                        } else {
                            long rem = targetWallMs - System.currentTimeMillis();
                            if (armed && rem > 0) {
                                toast("距离点击还有 " + (rem / 1000) + "."
                                        + String.format(Locale.US, "%03d", rem % 1000) + " 秒");
                            }
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

    private void updateLabel() {
        if (label == null) {
            return;
        }
        long rem = Math.max(0L, targetWallMs - System.currentTimeMillis());
        String text = fmt.format(new Date(targetWallMs)) + "  剩 "
                + (rem / 1000) + "." + String.format(Locale.US, "%03d", rem % 1000) + "s";
        label.setText(text);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!armed) {
                return;
            }
            updateLabel();
            main.postDelayed(this, 60);
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

        running = true;
        final long target = targetWall;
        Thread t = new Thread(() -> {
            try {
                // 提前约 250ms 移除悬浮窗，避免它挡住注入的点击
                final Runnable hide = () -> {
                    if (myGen == generation) {
                        hideForClick();
                    }
                };
                pendingHide = hide;
                long hideDelay = delayMs - 250L - offsetMs;
                main.postDelayed(hide, Math.max(0L, hideDelay));

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
            clickPoint = new float[]{
                    loc[0] + overlay.getWidth() / 2f,
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
        lastFireWallMs = System.currentTimeMillis();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_ARMED, false).apply();

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

        main.postDelayed(() -> {
            if (overlay != null && overlay.getParent() == null && wm != null) {
                try {
                    wm.addView(overlay, params);
                } catch (Throwable ignored) {
                }
            }
        }, 400);
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
            if (overlay != null && overlay.getParent() == null && wm != null) {
                try {
                    wm.addView(overlay, params);
                } catch (Throwable ignored) {
                }
            }
        }, 120);
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
