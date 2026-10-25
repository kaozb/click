package com.mclick.timer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private NumberPicker hourPicker;
    private NumberPicker minutePicker;
    private NumberPicker secondPicker;
    private EditText msInput;
    private EditText offsetInput;
    private TextView statusText;
    private TextView accessChip;
    private TextView overlayButton;
    private TextView startButtonRef;
    private Button startButton;
    private Button stopButton;
    private Button overlayButtonReal;
    private LinearLayout listContainer;
    private TextView emptyHint;
    private TextView listCount;
    private View led;

    private final List<String> times = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private boolean pendingStart;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (pendingStart && isAccessibilityEnabled()
                    && ClickAccessibilityService.getInstance() != null) {
                pendingStart = false;
                armFromList();
            }
            refreshStatus();
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ClickAccessibilityService.PREF, MODE_PRIVATE);
        setContentView(R.layout.activity_main);
        bindViews();
        setupPickers();
        loadTimes();
        loadLastPicker();
        renderList();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void bindViews() {
        hourPicker = findViewById(R.id.pick_hour);
        minutePicker = findViewById(R.id.pick_minute);
        secondPicker = findViewById(R.id.pick_second);
        msInput = findViewById(R.id.ms_input);
        offsetInput = findViewById(R.id.offset_input);
        statusText = findViewById(R.id.status_text);
        accessChip = findViewById(R.id.status_access);
        led = findViewById(R.id.status_led);
        listContainer = findViewById(R.id.list_container);
        emptyHint = findViewById(R.id.empty_hint);
        listCount = findViewById(R.id.list_count);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);
        overlayButtonReal = findViewById(R.id.overlay_button);
    }

    private void setupPickers() {
        setupPicker(hourPicker, 23);
        setupPicker(minutePicker, 59);
        setupPicker(secondPicker, 59);
    }

    private void setupPicker(NumberPicker picker, int max) {
        picker.setMinValue(0);
        picker.setMaxValue(max);
        picker.setWrapSelectorWheel(true);
        picker.setFormatter(value -> String.format(Locale.US, "%02d", value));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.setTextColor(0xFF3E3930);
            picker.setTextSize(17f);
        }
    }

    private void loadLastPicker() {
        int[] t = new int[4];
        String last = prefs.getString(ClickAccessibilityService.KEY_TIME_TEXT, "19:59:59.500");
        if (!ClickAccessibilityService.parseSpec(last, t)) {
            t[0] = 19;
            t[1] = 59;
            t[2] = 59;
            t[3] = 500;
        }
        hourPicker.setValue(t[0]);
        minutePicker.setValue(t[1]);
        secondPicker.setValue(t[2]);
        msInput.setText(String.valueOf(t[3]));
        msInput.setSelection(msInput.getText().length());
        offsetInput.setText(String.valueOf(prefs.getInt(ClickAccessibilityService.KEY_OFFSET, 0)));
    }

    private void loadTimes() {
        times.clear();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String raw = prefs.getString(ClickAccessibilityService.KEY_TIMES, "");
        int[] buf = new int[4];
        if (!TextUtils.isEmpty(raw)) {
            for (String s : raw.split(",")) {
                String v = s.trim();
                if (ClickAccessibilityService.parseSpec(v, buf)) {
                    set.add(ClickAccessibilityService.formatSpec(buf[0], buf[1], buf[2], buf[3]));
                }
            }
        }
        times.addAll(set);
        Collections.sort(times);
    }

    private void saveTimes() {
        prefs.edit().putString(ClickAccessibilityService.KEY_TIMES,
                TextUtils.join(",", times)).apply();
    }

    private void setupListeners() {
        findViewById(R.id.add_button).setOnClickListener(v -> addTime());
        startButton.setOnClickListener(v -> armFromList());
        stopButton.setOnClickListener(v -> {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopArm();
            }
            toast("已停止执行，悬浮按钮保留，可再次启动");
            refreshStatus();
        });
        overlayButtonReal.setOnClickListener(v -> toggleOverlay());
        accessChip.setOnClickListener(v -> openAccessibilitySettings());
    }

    private void addTime() {
        int ms = parseMilliseconds();
        if (ms < 0) {
            return;
        }
        String spec = ClickAccessibilityService.formatSpec(
                hourPicker.getValue(), minutePicker.getValue(), secondPicker.getValue(), ms);
        if (times.contains(spec)) {
            toast("这个时间已经在列表里了");
            return;
        }
        if (times.size() >= 30) {
            toast("最多保存 30 个时间");
            return;
        }
        times.add(spec);
        Collections.sort(times);
        saveTimes();
        prefs.edit().putString(ClickAccessibilityService.KEY_TIME_TEXT, spec).apply();
        if (ClickAccessibilityService.armed) {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopArm();
            }
            toast("已保存并停止当前执行，请重新点「启动定时点击」");
        } else {
            toast("已保存：" + spec);
        }
        renderList();
        refreshStatus();
        hideKeyboard();
    }

    private int parseMilliseconds() {
        String s = msInput.getText().toString().trim();
        if (s.isEmpty()) {
            return 0;
        }
        int ms;
        try {
            ms = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            toast("毫秒请填 0–999 的数字");
            return -1;
        }
        if (ms < 0 || ms > 999) {
            toast("毫秒请填 0–999 的数字");
            return -1;
        }
        return ms;
    }

    private int parseOffset() {
        String s = offsetInput.getText().toString().trim();
        int offset;
        try {
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
        return offset;
    }

    private void renderList() {
        listContainer.removeAllViews();
        emptyHint.setVisibility(times.isEmpty() ? View.VISIBLE : View.GONE);
        listCount.setText(times.size() + " 个");
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String spec : new ArrayList<>(times)) {
            View row = inflater.inflate(R.layout.item_time, listContainer, false);
            ((TextView) row.findViewById(R.id.item_time)).setText(spec);
            row.findViewById(R.id.item_delete).setOnClickListener(v -> {
                times.remove(spec);
                saveTimes();
                boolean wasArmed = ClickAccessibilityService.armed;
                if (wasArmed) {
                    ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
                    if (svc != null) {
                        svc.stopArm();
                    }
                }
                renderList();
                refreshStatus();
                toast(wasArmed ? "已删除并停止执行，请重新启动" : "已删除 " + spec);
            });
            listContainer.addView(row);
        }
    }

    private void armFromList() {
        if (times.isEmpty()) {
            toast("请先保存至少一个时间");
            return;
        }
        int ms = parseMilliseconds();
        if (ms < 0) {
            return;
        }
        String spec = ClickAccessibilityService.formatSpec(
                hourPicker.getValue(), minutePicker.getValue(), secondPicker.getValue(), ms);
        prefs.edit()
                .putString(ClickAccessibilityService.KEY_TIME_TEXT, spec)
                .putInt(ClickAccessibilityService.KEY_OFFSET, parseOffset())
                .apply();

        if (!isAccessibilityEnabled()) {
            pendingStart = true;
            toast("请先开启「毫秒定时点击」的无障碍服务");
            openAccessibilitySettings();
            return;
        }
        ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
        if (svc == null) {
            pendingStart = true;
            toast("无障碍服务正在连接，请稍候…");
            return;
        }
        pendingStart = false;
        svc.armAll(new ArrayList<>(times), parseOffset());
        refreshStatus();
    }

    private void toggleOverlay() {
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务");
            openAccessibilitySettings();
            return;
        }
        ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
        if (svc == null) {
            toast("无障碍服务正在连接，请稍候再试");
            return;
        }
        if (ClickAccessibilityService.overlayShown) {
            boolean wasArmed = ClickAccessibilityService.armed;
            svc.hideOverlay();
            toast(wasArmed ? "已停止执行并隐藏悬浮按钮" : "已隐藏悬浮按钮");
        } else {
            svc.showOverlayOnly();
            toast("拖动准星到目标位置后，点「启动定时点击」");
        }
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isAccessibilityEnabled();
        boolean armed = ClickAccessibilityService.armed;
        boolean shown = ClickAccessibilityService.overlayShown;
        long next = ClickAccessibilityService.targetWallMs;
        int remaining = ClickAccessibilityService.remainingCount;
        long now = System.currentTimeMillis();

        if (armed && next > now) {
            long rem = next - now;
            statusText.setText("已启动 · 剩 " + remaining + " 个 · "
                    + fmt.format(new Date(next)) + " · "
                    + (rem / 1000) + "." + String.format(Locale.US, "%03d", rem % 1000) + "s");
            led.setBackgroundResource(R.drawable.led_on);
        } else if (armed) {
            statusText.setText("正在执行点击…");
            led.setBackgroundResource(R.drawable.led_on);
        } else if (shown) {
            long last = ClickAccessibilityService.lastFireWallMs;
            if (last > 0 && now - last < 60000L) {
                statusText.setText("已执行 " + fmt.format(new Date(last))
                        + " · 悬浮按钮待启动");
            } else {
                statusText.setText("悬浮按钮已显示 · 拖到目标位置后点「启动」");
            }
            led.setBackgroundResource(R.drawable.led_wait);
        } else if (!enabled) {
            statusText.setText("无障碍未开启，无法执行");
            led.setBackgroundResource(R.drawable.led_off);
        } else {
            statusText.setText("待启动 · 保存时间后点「启动定时点击」");
            led.setBackgroundResource(R.drawable.led_off);
        }

        accessChip.setText(enabled ? "无障碍 已开启" : "去开启无障碍");
        overlayButtonReal.setText(shown ? "隐藏悬浮按钮" : "显示悬浮按钮");
        stopButton.setVisibility(armed ? View.VISIBLE : View.GONE);
        startButton.setText(armed ? "重新启动" : "启动定时点击");
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName cn = new ComponentName(this, ClickAccessibilityService.class);
        return enabled.contains(cn.flattenToString())
                || enabled.contains(cn.flattenToShortString());
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable e) {
            toast("无法打开无障碍设置，请手动到系统设置中开启");
        }
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
