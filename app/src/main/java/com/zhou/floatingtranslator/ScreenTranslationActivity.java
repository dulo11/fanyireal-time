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

/** Settings and onboarding page for accessibility full-screen translation. */
public final class ScreenTranslationActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private SharedPreferences prefs;
    private TextView status;
    private TextView language;
    private Button continuous;
    private Button ocrFallback;
    private Button skipTarget;

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
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("全屏翻译", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("无障碍优先 · 不占用录屏 · Azure 自动识别源语言", 14,
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
            "启用“浮译 · 全屏翻译”后，屏幕右侧会出现“译”悬浮球。\n"
                + "轻点：翻译当前屏幕；长按：开启/关闭连续翻译；拖动：移动悬浮球。",
            13, Color.rgb(185, 175, 207));
        serviceTip.setPadding(0, dp(7), 0, 0);
        serviceCard.addView(serviceTip);

        LinearLayout modeCard = card(root);
        modeCard.addView(sectionTitle("翻译方式"));
        TextView modeTip = text(
            "① 优先直接读取 App 暴露给无障碍的文字和坐标，速度快、没有 OCR 错字。\n"
                + "② 页面读不到文字时，可用无障碍截图 + ML Kit OCR 兜底。这里不使用 MediaProjection 录屏。",
            13, Color.rgb(205, 194, 224));
        modeTip.setPadding(0, dp(6), 0, dp(8));
        modeCard.addView(modeTip);

        continuous = secondaryButton("");
        continuous.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS, false);
            prefs.edit().putBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS, next).apply();
            refresh();
        });
        modeCard.addView(continuous, matchWrap());

        ocrFallback = secondaryButton("");
        ocrFallback.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, true);
            prefs.edit().putBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, next).apply();
            refresh();
        });
        modeCard.addView(ocrFallback, matchWrap());

        skipTarget = secondaryButton("");
        skipTarget.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_SKIP_TARGET, true);
            prefs.edit().putBoolean(
                ScreenTranslationAccessibilityService.PREF_SCREEN_SKIP_TARGET, next).apply();
            refresh();
        });
        modeCard.addView(skipTarget, matchWrap());

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

        LinearLayout usageCard = card(root);
        usageCard.addView(sectionTitle("怎么用"));
        TextView usage = text(
            "1. 先在本页打开系统无障碍设置并启用“浮译 · 全屏翻译”。\n"
                + "2. 返回要翻译的 App。\n"
                + "3. 轻点“译”悬浮球，译文会按原文字位置覆盖显示。\n"
                + "4. 长按悬浮球进入连续模式，页面变化后会自动重新翻译。\n"
                + "5. 切换到别的 App 时，旧覆盖层会自动清除。",
            14, Color.rgb(210, 201, 226));
        usage.setPadding(0, dp(7), 0, 0);
        usageCard.addView(usage);

        TextView note = text(
            "游戏、视频内嵌字幕、Canvas/OpenGL 等页面通常没有无障碍文字节点，会走无障碍截图 OCR。"
                + "受保护页面（例如部分银行/DRM 内容）仍可能禁止截图。",
            13, Color.rgb(176, 166, 199));
        note.setPadding(dp(2), dp(4), dp(2), 0);
        root.addView(note);

        refresh();
        return scroll;
    }

    private void refresh() {
        if (prefs == null) return;
        boolean enabled = ScreenTranslationAccessibilityService.isEnabled(this);
        String last = prefs.getString(ScreenTranslationAccessibilityService.PREF_SCREEN_LAST_STATUS, "尚未运行");
        if (status != null) {
            status.setText("状态：" + (enabled ? "✅ 已开启" : "⚠ 未开启")
                + "\n最近：" + (last == null ? "" : last));
        }

        boolean auto = prefs.getBoolean(
            ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS, false);
        boolean ocr = prefs.getBoolean(
            ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, true);
        boolean skip = prefs.getBoolean(
            ScreenTranslationAccessibilityService.PREF_SCREEN_SKIP_TARGET, true);
        if (continuous != null) continuous.setText("连续翻译：" + (auto ? "✅ 开" : "关闭"));
        if (ocrFallback != null) ocrFallback.setText("无障碍截图 OCR 兜底：" + (ocr ? "✅ 开" : "关闭"));
        if (skipTarget != null) skipTarget.setText("跳过已经是目标语言的文字：" + (skip ? "✅ 开" : "关闭"));

        if (language != null) {
            int source = clampLanguageIndex(prefs.getInt("source_index", 2));
            int target = clampLanguageIndex(prefs.getInt("target_index", 0));
            String engine = prefs.getString("engine_id", TranslationRouter.AUTO);
            if (engine == null) engine = TranslationRouter.AUTO;
            SecureConfig secure = new SecureConfig(this);
            language.setText(
                "OCR 源语言：" + LanguageOption.ALL[source].label + "\n"
                    + "目标语言：" + LanguageOption.ALL[target].label + "\n"
                    + "当前引擎：" + TranslationRouter.engineLabel(engine) + "\n"
                    + "Azure 账号：" + secure.configuredAzureProfileCount() + " 个\n\n"
                    + "只要配置了 Azure，全屏文字节点翻译会省略 from 参数，由 Azure 自动识别源语言。"
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
        t.setTypeface(null, 1);
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
