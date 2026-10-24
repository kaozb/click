package com.mclick.timer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?$");

    private EditText timeInput;
    private EditText offsetInput;
    private TextView statusView;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pendingArm;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ClickAccessibilityService.PREF, MODE_PRIVATE);
        setContentView(buildUi());

        timeInput.setText(prefs.getString(ClickAccessibilityService.KEY_TIME_TEXT, "19:59:59.500"));
        offsetInput.setText(String.valueOf(prefs.getInt(ClickAccessibilityService.KEY_OFFSET, 0)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (pendingArm && isAccessibilityEnabled()
                && ClickAccessibilityService.getInstance() != null) {
            pendingArm = false;
            startFromInputs();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private LinearLayout buildUi() {
        int pad = dp(22);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF5F5F5);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("毫秒定时点击");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTextColor(0xFF212121);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("设定一个精确到毫秒的时间，点「启动悬浮按钮」，把准星拖到需要点击的位置。"
                + "到达设定时间后，应用会在该位置自动点击一次。"
                + "若时间已过，会自动顺延到明天同一时刻。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        desc.setTextColor(0xFF616161);
        desc.setLineSpacing(dp(3), 1f);
        addWithTop(root, desc, dp(8));

        TextView label1 = smallLabel("触发时间（时:分:秒.毫秒）");
        addWithTop(root, label1, dp(20));

        timeInput = new EditText(this);
        timeInput.setSingleLine(true);
        timeInput.setInputType(InputType.TYPE_CLASS_TEXT);
        timeInput.setHint("例如 19:59:59.500");
        timeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        root.addView(timeInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label2 = smallLabel("延迟补偿（毫秒，可填 0–2000）");
        addWithTop(root, label2, dp(12));

        offsetInput = new EditText(this);
        offsetInput.setSingleLine(true);
        offsetInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        offsetInput.setHint("0");
        offsetInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        root.addView(offsetInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView offsetTip = new TextView(this);
        offsetTip.setText("系统注入点击本身有少量延迟。如果实测总是偏晚，可把补偿填成实测偏晚的毫秒数，"
                + "应用会提前相应时间执行。默认 0。");
        offsetTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        offsetTip.setTextColor(0xFF9E9E9E);
        addWithTop(root, offsetTip, dp(4));

        Button start = new Button(this);
        start.setText("启动悬浮按钮");
        start.setAllCaps(false);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        addWithTop(root, start, dp(22));
        start.setOnClickListener(v -> startFromInputs());

        Button hide = new Button(this);
        hide.setText("停止 / 隐藏悬浮按钮");
        hide.setAllCaps(false);
        addWithTop(root, hide, dp(8));
        hide.setOnClickListener(v -> {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.disarm();
            } else {
                prefs.edit().putBoolean(ClickAccessibilityService.KEY_ARMED, false).apply();
            }
            refreshStatus();
            toast("已停止");
        });

        Button open = new Button(this);
        open.setText("打开无障碍服务设置");
        open.setAllCaps(false);
        addWithTop(root, open, dp(8));
        open.setOnClickListener(v -> openAccessibilitySettings());

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusView.setTextColor(0xFF212121);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackgroundColor(0xFFE8E8E8);
        addWithTop(root, statusView, dp(20));

        TextView help = new TextView(this);
        help.setText("使用步骤：\n"
                + "1. 点击「打开无障碍服务设置」，找到「毫秒定时点击」并开启。\n"
                + "2. 回到本页，填写时间与补偿，点「启动悬浮按钮」。\n"
                + "3. 把悬浮准星拖到目标位置，保持页面/应用处于要点击的状态。\n"
                + "4. 到点自动点击一次，悬浮按钮会自动恢复。\n\n"
                + "说明：本应用不联网、不读取屏幕内容，APK 体积很小。"
                + "部分系统（Android 13+）如果提示「受限制的设置」，"
                + "需先在系统「应用信息」里允许受限设置，再回来开启无障碍。");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        help.setTextColor(0xFF757575);
        help.setLineSpacing(dp(3), 1f);
        addWithTop(root, help, dp(16));

        scroll.addView(root);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrapper;
    }

    private TextView smallLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(0xFF424242);
        return tv;
    }

    private void addWithTop(LinearLayout parent, android.view.View v, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(v, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startFromInputs() {
        String raw = timeInput.getText().toString().trim();
        Matcher m = TIME_PATTERN.matcher(raw);
        if (!m.matches()) {
            toast("时间格式应为 时:分:秒.毫秒，例如 19:59:59.500");
            return;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        int second = Integer.parseInt(m.group(3));
        if (hour > 23 || minute > 59 || second > 59) {
            toast("小时需 0–23，分钟/秒需 0–59");
            return;
        }
        String frac = m.group(4);
        int millis = 0;
        if (frac != null) {
            while (frac.length() < 3) {
                frac = frac + "0";
            }
            millis = Integer.parseInt(frac);
        }
        int offset;
        try {
            String s = offsetInput.getText().toString().trim();
            offset = s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            offset = 0;
        }
        if (offset < 0) {
            offset = 0;
        }
        if (offset > 2000) {
            offset = 2000;
            offsetInput.setText("2000");
        }

        prefs.edit()
                .putString(ClickAccessibilityService.KEY_TIME_TEXT, raw)
                .putInt(ClickAccessibilityService.KEY_OFFSET, offset)
                .apply();

        if (!isAccessibilityEnabled()) {
            pendingArm = true;
            toast("请先开启「毫秒定时点击」的无障碍服务");
            openAccessibilitySettings();
            return;
        }
        ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
        if (svc == null) {
            toast("无障碍服务正在启动，请稍等再点一次");
            return;
        }
        svc.arm(hour, minute, second, millis, offset);
        refreshStatus();
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName cn = new ComponentName(this, ClickAccessibilityService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable e) {
            toast("无法打开无障碍设置，请手动到系统设置中开启");
        }
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
        long now = System.currentTimeMillis();
        if (ClickAccessibilityService.armed && ClickAccessibilityService.targetWallMs > now) {
            long rem = ClickAccessibilityService.targetWallMs - now;
            String when = rem < 86400000L ? "今天" : "明天";
            statusView.setText("● 已启动｜" + when + " "
                    + fmt.format(new Date(ClickAccessibilityService.targetWallMs))
                    + "｜剩余 " + (rem / 1000) + "."
                    + String.format(Locale.US, "%03d", rem % 1000) + " 秒");
        } else if (ClickAccessibilityService.lastFireWallMs > 0
                && now - ClickAccessibilityService.lastFireWallMs < 60000L) {
            statusView.setText("● 已执行点击｜"
                    + fmt.format(new Date(ClickAccessibilityService.lastFireWallMs)));
        } else if (!isAccessibilityEnabled()) {
            statusView.setText("① 未开启无障碍服务，请先点下方按钮开启");
        } else if (svc == null) {
            statusView.setText("② 无障碍已开启，正在等待服务连接…");
        } else {
            statusView.setText("② 无障碍已开启，设置时间后点「启动悬浮按钮」");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
