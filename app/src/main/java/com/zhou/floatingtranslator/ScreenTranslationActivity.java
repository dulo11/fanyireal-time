package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Settings and onboarding page for global accessibility translation. */
public final class ScreenTranslationActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private SharedPreferences prefs;
    private TextView status;
    private TextView language;
    private Button continuous;
    private Button ocrFallback;
    private Button smartOcr;
    private Button skipTarget;
    private Button incrementalCache;
    private Button inputReverse;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(110));
        scroll.addView(root);

        TextView title = text("全局翻译", 30, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("网站 · 手机界面 · 聊天 App · 无障碍优先 · 不占用录屏", 14,
            Color.rgb(190, 165, 255));
        subtitle.setPadding(0, dp(3), 0, dp(16));
        root.addView(subtitle);

        LinearLayout serviceCard = card(root);
        serviceCard.addView(sectionTitle("无障碍服务"));
        status = text("", 14, Color.rgb(224, 217, 238));
        status.setPadding(0, dp(7), 0, dp(8));
        serviceCard.addView(status);
        Button accessibility = primaryButton("打开系统无障碍设置");
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        serviceCard.addView(accessibility, matchWrap());

        TextView serviceTip = text(
            "启用“浮译 · 全屏翻译”后会出现“译”悬浮球。\n"
                + "轻点：翻译当前页面；双击：把当前聊天输入框反向翻译；长按：开关全局自动翻译；拖动：移动悬浮球。",
            13, Color.rgb(185, 175, 207));
        serviceTip.setPadding(0, dp(7), 0, 0);
        serviceCard.addView(serviceTip);

        LinearLayout modeCard = card(root);
        modeCard.addView(sectionTitle("全局翻译方式"));
        TextView modeTip = text(
            "优先直接读取网站、系统界面、聊天气泡和普通 App 的无障碍文字。读不到文字时，再用无障碍截图 + ML Kit OCR。"
                + "不会占用 MediaProjection 录屏会话。",
            13, Color.rgb(205, 194, 224));
        modeTip.setPadding(0, dp(6), 0, dp(8));
        modeCard.addView(modeTip);

        continuous = secondaryButton("");
        continuous.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS, false));
        modeCard.addView(continuous, matchWrap());

        ocrFallback = secondaryButton("");
        ocrFallback.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, true));
        modeCard.addView(ocrFallback, matchWrap());

        smartOcr = secondaryButton("");
        smartOcr.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_SMART_OCR, false));
        modeCard.addView(smartOcr, matchWrap());

        skipTarget = secondaryButton("");
        skipTarget.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_SKIP_TARGET, true));
        modeCard.addView(skipTarget, matchWrap());

        incrementalCache = secondaryButton("");
        incrementalCache.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_INCREMENTAL_CACHE, true));
        modeCard.addView(incrementalCache, matchWrap());

        LinearLayout chatCard = card(root);
        chatCard.addView(sectionTitle("聊天 App"));
        TextView chatTip = text(
            "Telegram、WhatsApp、LINE、Messenger、微信等，只要聊天文字能被无障碍读取，就会跟随页面变化自动翻译。"
                + "输入框不会在你打字时触发整屏翻译。\n\n"
                + "发送前翻译：先点一下聊天输入框并输入中文（或你的目标语言），再双击“译”悬浮球。浮译会按“原语言”设置反向翻译并替换输入框，发送前仍由你确认。",
            13, Color.rgb(205, 194, 224));
        chatTip.setPadding(0, dp(6), 0, dp(8));
        chatCard.addView(chatTip);

        inputReverse = secondaryButton("");
        inputReverse.setOnClickListener(v -> toggle(ScreenTranslationAccessibilityService.PREF_SCREEN_INPUT_REVERSE, true));
        chatCard.addView(inputReverse, matchWrap());

        LinearLayout languageCard = card(root);
        languageCard.addView(sectionTitle("语言与翻译引擎"));
        language = text("", 14, Color.rgb(224, 217, 238));
        language.setPadding(0, dp(7), 0, dp(8));
        languageCard.addView(language);
        Button console = secondaryButton("打开实时翻译控制台调整语言");
        console.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        languageCard.addView(console, matchWrap());
        Button api = secondaryButton("打开翻译引擎 / Azure 账号设置");
        api.setOnClickListener(v -> startActivity(new Intent(this, ApiSettingsActivity.class)));
        languageCard.addView(api, matchWrap());

        LinearLayout coverageCard = card(root);
        coverageCard.addView(sectionTitle("适用范围"));
        TextView coverage = text(
            "✅ 浏览器网页：普通网页文字、菜单、按钮、滚动内容\n"
                + "✅ 手机界面：系统设置和大多数普通 App\n"
                + "✅ 聊天软件：聊天气泡、新出现的消息、普通输入框\n"
                + "✅ 图片/视频字幕/Canvas：无文字节点时可尝试 OCR\n\n"
                + "⚠ 银行、密码框、DRM、FLAG_SECURE 页面和部分游戏可能禁止读取或截图，无法保证 100% 覆盖。",
            13, Color.rgb(205, 194, 224));
        coverage.setPadding(0, dp(6), 0, 0);
        coverageCard.addView(coverage);

        refresh();
        return scroll;
    }

    private void toggle(String key, boolean defaultValue) {
        boolean next = !prefs.getBoolean(key, defaultValue);
        prefs.edit().putBoolean(key, next).apply();
        refresh();
    }

    private void refresh() {
        if (prefs == null) return;
        boolean enabled = ScreenTranslationAccessibilityService.isEnabled(this);
        String last = prefs.getString(ScreenTranslationAccessibilityService.PREF_SCREEN_LAST_STATUS, "尚未运行");
        if (status != null) {
            status.setText("状态：" + (enabled ? "✅ 已开启" : "⚠ 未开启")
                + "\n最近：" + (last == null ? "" : last));
        }

        boolean auto = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS, false);
        boolean ocr = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, true);
        boolean smart = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_SMART_OCR, false);
        boolean skip = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_SKIP_TARGET, true);
        boolean cache = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_INCREMENTAL_CACHE, true);
        boolean reverse = prefs.getBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_INPUT_REVERSE, true);

        if (continuous != null) continuous.setText("全局自动翻译：" + (auto ? "✅ 开" : "关闭"));
        if (ocrFallback != null) ocrFallback.setText("无障碍截图 OCR 兜底：" + (ocr ? "✅ 开" : "关闭"));
        if (smartOcr != null) smartOcr.setText("少量文字时强制 OCR 增强：" + (smart ? "✅ 开" : "关闭"));
        if (skipTarget != null) skipTarget.setText("跳过已经是目标语言的文字：" + (skip ? "✅ 开" : "关闭"));
        if (incrementalCache != null) incrementalCache.setText("网页 / 聊天增量缓存：" + (cache ? "✅ 开" : "关闭"));
        if (inputReverse != null) inputReverse.setText("双击悬浮球发送前反向翻译：" + (reverse ? "✅ 开" : "关闭"));

        if (language != null) {
            int source = clampLanguageIndex(prefs.getInt("source_index", 2));
            int target = clampLanguageIndex(prefs.getInt("target_index", 0));
            String engine = prefs.getString("engine_id", TranslationRouter.AUTO);
            if (engine == null) engine = TranslationRouter.AUTO;
            SecureConfig secure = new SecureConfig(this);
            language.setText(
                "对方 / 原语言：" + LanguageOption.ALL[source].label + "\n"
                    + "我的 / 目标语言：" + LanguageOption.ALL[target].label + "\n"
                    + "当前引擎：" + TranslationRouter.engineLabel(engine) + "\n"
                    + "Azure 账号：" + secure.configuredAzureProfileCount() + " 个\n\n"
                    + "整屏翻译：原语言 → 目标语言。\n"
                    + "聊天输入框双击反向翻译：目标语言 → 原语言。"
            );
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("无法打开无障碍设置");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.panel);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        root.addView(card, p);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button primaryButton(String value) {
        Button b = button(value);
        b.setBackgroundResource(R.drawable.button_primary);
        return b;
    }

    private Button secondaryButton(String value) {
        Button b = button(value);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int clampLanguageIndex(int value) {
        return Math.max(0, Math.min(value, LanguageOption.ALL.length - 1));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
