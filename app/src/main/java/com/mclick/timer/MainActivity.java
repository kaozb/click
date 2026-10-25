package com.mclick.timer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import android.widget.ScrollView;
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
    private WheelView hourWheel;
    private WheelView minuteWheel;
    private WheelView secondWheel;
    private EditText msInput;
    private EditText offsetInput;
    private Button statusButton;
    private Button overlayButton;
    private Button startButton;
    private LinearLayout listContainer;
    private TextView emptyHint;
    private TextView listCount;
    private ScrollView scroll;
    private View timeCard;
    private View listCard;

    private final List<String> times = new ArrayList<>();
    private String selectedSpec = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private boolean pendingStart;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (pendingStart && isAccessibilityEnabled()
                    && ClickAccessibilityService.getInstance() != null) {
                pendingStart = false;
                startSelected();
            }
            refreshStatus();
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
        if (isAccessibilityEnabled()) {
            KeepAliveService.start(this);
        }
        handler.removeCallbacks(ticker);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void bindViews() {
        hourWheel = findViewById(R.id.pick_hour);
        minuteWheel = findViewById(R.id.pick_minute);
        secondWheel = findViewById(R.id.pick_second);
        msInput = findViewById(R.id.ms_input);
        offsetInput = findViewById(R.id.offset_input);
        statusButton = findViewById(R.id.status_button);
        listContainer = findViewById(R.id.list_container);
        emptyHint = findViewById(R.id.empty_hint);
        listCount = findViewById(R.id.list_count);
        startButton = findViewById(R.id.start_button);
        overlayButton = findViewById(R.id.overlay_button);
        scroll = findViewById(R.id.scroll);
        timeCard = findViewById(R.id.time_card);
        listCard = findViewById(R.id.list_card);
    }

    private void setupPickers() {
        hourWheel.setRange(0, 23);
        minuteWheel.setRange(0, 59);
        secondWheel.setRange(0, 59);
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

        String saved = prefs.getString(ClickAccessibilityService.KEY_SELECTED, "");
        if (!times.contains(saved)) {
            saved = times.isEmpty() ? "" : times.get(0);
        }
        selectedSpec = saved;
        prefs.edit().putString(ClickAccessibilityService.KEY_SELECTED, selectedSpec).apply();
    }

    private void loadLastPicker() {
        int[] t = new int[4];
        String last = selectedSpec;
        if (!ClickAccessibilityService.parseSpec(last, t)) {
            last = prefs.getString(ClickAccessibilityService.KEY_TIME_TEXT, "19:59:59.500");
        }
        if (!ClickAccessibilityService.parseSpec(last, t)) {
            t[0] = 19;
            t[1] = 59;
            t[2] = 59;
            t[3] = 500;
        }
        applyToPickers(t[0], t[1], t[2], t[3]);
        offsetInput.setText(String.valueOf(prefs.getInt(ClickAccessibilityService.KEY_OFFSET, 0)));
    }

    private void applyToPickers(int h, int m, int s, int ms) {
        hourWheel.setValue(h);
        minuteWheel.setValue(m);
        secondWheel.setValue(s);
        msInput.setText(String.valueOf(ms));
        msInput.setSelection(msInput.getText().length());
    }

    private void loadPickerFromSpec(String spec) {
        int[] t = new int[4];
        if (ClickAccessibilityService.parseSpec(spec, t)) {
            applyToPickers(t[0], t[1], t[2], t[3]);
        }
    }

    private void saveTimes() {
        prefs.edit()
                .putString(ClickAccessibilityService.KEY_TIMES, TextUtils.join(",", times))
                .putString(ClickAccessibilityService.KEY_SELECTED, selectedSpec)
                .apply();
    }

    private void setupListeners() {
        findViewById(R.id.add_button).setOnClickListener(v -> addTime());
        startButton.setOnClickListener(v -> {
            if (ClickAccessibilityService.armed) {
                ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
                if (svc != null) {
                    svc.stopArm();
                }
                toast("已结束本次任务，可重新选择时间启动");
                refreshStatus();
            } else {
                startSelected();
            }
        });
        overlayButton.setOnClickListener(v -> toggleOverlay());
        findViewById(R.id.battery_button).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable e) {
                openAccessibilitySettings();
            }
        });
        statusButton.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                openAccessibilitySettings();
            } else if (times.isEmpty()) {
                scrollTo(timeCard);
            } else {
                scrollTo(listCard);
            }
        });
    }

    private void addTime() {
        int ms = parseMilliseconds();
        if (ms < 0) {
            return;
        }
        String spec = ClickAccessibilityService.formatSpec(
                hourWheel.getValue(), minuteWheel.getValue(), secondWheel.getValue(), ms);
        if (times.contains(spec)) {
            toast("这个时间已经在列表里了，已直接选中");
            selectTime(spec, true);
            return;
        }
        if (times.size() >= 30) {
            toast("最多保存 30 个时间");
            return;
        }
        times.add(spec);
        Collections.sort(times);
        selectedSpec = spec;
        saveTimes();
        prefs.edit().putString(ClickAccessibilityService.KEY_TIME_TEXT, spec).apply();

        boolean wasArmed = ClickAccessibilityService.armed;
        if (wasArmed) {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopArm();
            }
            toast("已保存并选中，已停止原执行，请重新点「启动选中时间」");
        } else {
            toast("已保存并选中：" + spec);
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
            final boolean selected = spec.equals(selectedSpec);
            View row = inflater.inflate(R.layout.item_time, listContainer, false);
            TextView timeView = row.findViewById(R.id.item_time);
            View dot = row.findViewById(R.id.item_dot);
            timeView.setText(spec);
            row.setBackgroundResource(selected
                    ? R.drawable.bg_pill_selected : R.drawable.bg_pill);
            timeView.setTextColor(selected ? 0xFF66855A : 0xFF3E3930);
            timeView.setTypeface(timeView.getTypeface(),
                    selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            dot.setBackgroundResource(selected ? R.drawable.led_on : R.drawable.led_off);

            row.setOnClickListener(v -> selectTime(spec, true));
            row.findViewById(R.id.item_delete).setOnClickListener(v -> deleteTime(spec));
            listContainer.addView(row);
        }
    }

    private void selectTime(String spec, boolean loadPicker) {
        if (!times.contains(spec)) {
            return;
        }
        boolean changed = !spec.equals(selectedSpec);
        selectedSpec = spec;
        saveTimes();
        if (changed && ClickAccessibilityService.armed) {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopArm();
            }
            toast("已切换选中时间并停止原执行，请重新启动");
        }
        if (loadPicker) {
            loadPickerFromSpec(spec);
        }
        renderList();
        refreshStatus();
    }

    private void deleteTime(String spec) {
        times.remove(spec);
        boolean wasSelected = spec.equals(selectedSpec);
        if (wasSelected) {
            selectedSpec = times.isEmpty() ? "" : times.get(0);
        }
        saveTimes();
        boolean wasArmed = ClickAccessibilityService.armed;
        if (wasArmed) {
            ClickAccessibilityService svc = ClickAccessibilityService.getInstance();
            if (svc != null) {
                svc.stopArm();
            }
        }
        if (wasSelected && !selectedSpec.isEmpty()) {
            loadPickerFromSpec(selectedSpec);
        }
        renderList();
        refreshStatus();
        toast(wasArmed ? "已删除并停止执行，请重新启动"
                : (wasSelected && !selectedSpec.isEmpty()
                ? "已删除，当前选中 " + selectedSpec : "已删除 " + spec));
    }

    private void startSelected() {
        if (selectedSpec.isEmpty()) {
            toast("请先在列表中选中一个时间");
            return;
        }
        int offset = parseOffset();
        prefs.edit()
                .putString(ClickAccessibilityService.KEY_TIME_TEXT, selectedSpec)
                .putString(ClickAccessibilityService.KEY_SELECTED, selectedSpec)
                .putInt(ClickAccessibilityService.KEY_OFFSET, offset)
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
        svc.arm(selectedSpec, offset);
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
            if (selectedSpec.isEmpty()) {
                toast("请先保存并选中一个时间，再显示悬浮按钮");
                return;
            }
            svc.showOverlayOnly();
            toast("拖动准星到目标位置后，点「启动选中时间」");
        }
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isAccessibilityEnabled();
        boolean armed = ClickAccessibilityService.armed;
        boolean shown = ClickAccessibilityService.overlayShown;
        long next = ClickAccessibilityService.targetWallMs;
        long now = System.currentTimeMillis();

        String text;
        int bg;
        int color;
        if (armed && next > now) {
            long rem = next - now;
            text = "已启动 · " + fmt.format(new Date(next)) + " · "
                    + (rem / 1000) + "." + String.format(Locale.US, "%03d", rem % 1000) + "s";
            bg = R.drawable.bg_pill_selected;
            color = 0xFF66855A;
        } else if (armed) {
            text = "正在执行点击…";
            bg = R.drawable.bg_pill_selected;
            color = 0xFF66855A;
        } else if (!enabled) {
            text = "无障碍未开启 · 点此去开启";
            bg = R.drawable.bg_btn_ghost;
            color = 0xFFB3564E;
        } else if (selectedSpec.isEmpty()) {
            text = "待启动 · 保存并选中一个时间";
            bg = R.drawable.bg_btn_ghost;
            color = 0xFF3E3930;
        } else if (shown) {
            long last = ClickAccessibilityService.lastFireWallMs;
            if (last > 0 && now - last < 60000L) {
                text = "已执行 " + fmt.format(new Date(last)) + " · 再点启动可执行选中时间";
            } else {
                text = "待启动 · 已选中 " + selectedSpec + " · 悬浮按钮已显示";
            }
            bg = R.drawable.bg_btn_ghost;
            color = 0xFF3E3930;
        } else {
            text = "待启动 · 已选中 " + selectedSpec + " · 点「启动」执行";
            bg = R.drawable.bg_btn_ghost;
            color = 0xFF3E3930;
        }
        if (!text.contentEquals(statusButton.getText())) {
            statusButton.setText(text);
        }
        if (!Integer.valueOf(bg).equals(statusButton.getTag())) {
            statusButton.setBackgroundResource(bg);
            statusButton.setTag(bg);
        }
        if (statusButton.getCurrentTextColor() != color) {
            statusButton.setTextColor(color);
        }
        String startText = armed ? "结束任务" : "启动选中时间";
        if (!startText.contentEquals(startButton.getText())) {
            startButton.setText(startText);
        }
        int startBg = armed ? R.drawable.bg_btn_ghost : R.drawable.bg_btn_accent;
        int startColor = armed ? 0xFFB3564E : 0xFFFFFFFF;
        if (!Integer.valueOf(startBg).equals(startButton.getTag())) {
            startButton.setBackgroundResource(startBg);
            startButton.setTag(startBg);
        }
        if (startButton.getCurrentTextColor() != startColor) {
            startButton.setTextColor(startColor);
        }
        String overlayText = shown ? "隐藏悬浮按钮" : "显示悬浮按钮";
        if (!overlayText.contentEquals(overlayButton.getText())) {
            overlayButton.setText(overlayText);
        }

        // 只在等待执行或等待服务连接时刷新，空闲时完全停止轮询以省电
        handler.removeCallbacks(ticker);
        if (armed || pendingStart) {
            long rem = next - now;
            long delay;
            if (!armed) {
                delay = 500L;
            } else if (rem > 60_000L) {
                delay = 1000L;
            } else if (rem > 10_000L) {
                delay = 500L;
            } else {
                delay = 250L;
            }
            handler.postDelayed(ticker, delay);
        }
    }

    private void scrollTo(View target) {
        if (target == null || scroll == null) {
            return;
        }
        scroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(8)));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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
        // 优先直达本服务的无障碍详情页，少走一层总列表
        try {
            Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            detail.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(this, ClickAccessibilityService.class).flattenToString());
            startActivity(detail);
            return;
        } catch (Throwable ignored) {
        }
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
