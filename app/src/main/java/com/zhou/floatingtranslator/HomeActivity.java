package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Compact home dashboard. Detailed translation controls remain in MainActivity. */
public final class HomeActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private SharedPreferences preferences;
    private TextView quickStatus;
    private TextView recent;
    private UpdateChecker updateChecker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));

        LinearLayout root = column();
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(dp(18), dp(24), dp(18), dp(30));
        scroll.addView(root);

        TextView title = text("浮译", 34, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView version = text("FloatingTranslator v" + BuildConfig.VERSION_NAME,
            14, Color.rgb(190, 165, 255));
        version.setPadding(0, dp(2), 0, dp(3));
        root.addView(version);

        TextView subtitle = text("实时语音翻译 · 离线 ASR · ROOT 通话 · 悬浮字幕", 14,
            Color.rgb(201, 190, 221));
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

        LinearLayout statusCard = card(root);
        statusCard.addView(sectionTitle("当前状态"));
        quickStatus = text("", 14, Color.rgb(224, 217, 238));
        quickStatus.setPadding(0, dp(7), 0, 0);
        statusCard.addView(quickStatus);

        LinearLayout startCard = card(root);
        startCard.addView(sectionTitle("实时翻译"));
        TextView startTip = text("常用功能放在这里；复杂选项收到详细设置里。", 13,
            Color.rgb(184, 174, 207));
        startTip.setPadding(0, dp(4), 0, dp(8));
        startCard.addView(startTip);

        Button start = primaryButton("▶ 打开实时翻译控制台");
        start.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        startCard.addView(start, matchWrap());

        Button models = secondaryButton("📦 离线模型中心");
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        startCard.addView(models, matchWrap());

        LinearLayout toolsCard = card(root);
        toolsCard.addView(sectionTitle("工具"));
        toolsCard.addView(toolRow(
            toolButton("📝 历史", v -> startActivity(new Intent(this, HistoryActivity.class))),
            toolButton("☎ ROOT 通话", v -> startActivity(new Intent(this, RootCallActivity.class)))
        ));
        toolsCard.addView(toolRow(
            toolButton("🎚 ASR 精度", v -> startActivity(new Intent(this, AsrPrecisionActivity.class))),
            toolButton("⚙ 翻译引擎", v -> startActivity(new Intent(this, ApiSettingsActivity.class)))
        ));
        Button update = secondaryButton("⬆ 检查更新");
        update.setOnClickListener(v -> checkUpdate());
        toolsCard.addView(update, matchWrap());

        LinearLayout recentCard = card(root);
        recentCard.addView(sectionTitle("最近翻译"));
        recent = text("暂无", 14, Color.rgb(218, 209, 231));
        recent.setPadding(0, dp(6), 0, dp(8));
        recentCard.addView(recent);
        Button copy = secondaryButton("复制最近译文");
        copy.setOnClickListener(v -> copyRecent());
        recentCard.addView(copy, matchWrap());

        TextView note = text(
            "纯日语优先 ReazonSpeech；日英混合优先 Qwen3-ASR / Whisper。\n" +
            "SenseVoice、ReazonSpeech、Whisper 的完整包可在 FP32 / INT8 间切换；FP32 更吃内存，不代表所有场景都会明显更准。",
            13, Color.rgb(180, 170, 205));
        note.setPadding(dp(3), dp(12), dp(3), 0);
        root.addView(note);

        refreshStatus();
        return scroll;
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = column();
        card.setGravity(Gravity.NO_GRAVITY);
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

    private LinearLayout toolRow(Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, dp(4), dp(4), dp(4));
        row.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.setMargins(dp(4), dp(4), 0, dp(4));
        row.addView(right, rp);
        return row;
    }

    private Button toolButton(String value, View.OnClickListener listener) {
        Button b = secondaryButton(value);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        return b;
    }

    private void refreshStatus() {
        if (quickStatus == null) return;
        String engine = preferences.getString("engine_id", TranslationRouter.AUTO);
        String asr = preferences.getString("asr_mode", TranslationService.ASR_AUTO);
        String precision = preferences.getString("asr_precision", SherpaSpeechEngine.PRECISION_AUTO);
        int input = Math.min(2, preferences.getInt("input_mode", 0));
        boolean overlay = Settings.canDrawOverlays(this);
        String source = input == 2 ? "ROOT 通话/VoIP" : input == 1 ? "麦克风" : "系统内部声音";
        quickStatus.setText(
            "声音：" + source + "\n" +
            "ASR：" + asrLabel(asr) + "\n" +
            "ASR 精度：" + precisionLabel(precision) + "\n" +
            "翻译：" + engineLabel(engine) + "\n" +
            "悬浮窗：" + (overlay ? "✅ 已授权" : "⚠ 未授权") + "\n" +
            "历史：" + HistoryStore.count(this) + " 条"
        );

        if (recent != null) {
            String original = preferences.getString("last_original", "");
            String translated = preferences.getString("last_translation", "");
            recent.setText(translated.isEmpty()
                ? "暂无翻译记录"
                : (original.isEmpty() ? "译文：" + translated : "原文：" + original + "\n译文：" + translated));
        }
    }

    private String precisionLabel(String value) {
        if (SherpaSpeechEngine.PRECISION_FP32.equals(value)) return "FP32 原始权重";
        if (SherpaSpeechEngine.PRECISION_INT8.equals(value)) return "INT8 量化";
        return "自动（INT8 优先）";
    }

    private void checkUpdate() {
        if (updateChecker != null) updateChecker.close();
        updateChecker = new UpdateChecker(this);
        toast("正在检查更新……");
        updateChecker.check(BuildConfig.VERSION_NAME, new UpdateChecker.Callback() {
            @Override public void onResult(String latestVersion, String pageUrl, String apkUrl, boolean newer) {
                if (!newer) {
                    toast("当前已是最新版 v" + BuildConfig.VERSION_NAME);
                    return;
                }
                new AlertDialog.Builder(HomeActivity.this)
                    .setTitle("发现浮译 v" + latestVersion)
                    .setMessage("当前版本：v" + BuildConfig.VERSION_NAME + "\n仅提供通用 APK。")
                    .setNegativeButton("稍后", null)
                    .setNeutralButton("版本页面", (d, w) -> openUrl(pageUrl))
                    .setPositiveButton("下载最新版", (d, w) -> openUrl(apkUrl))
                    .show();
            }

            @Override public void onError(String message) {
                toast("检查更新失败：" + message);
            }
        });
    }

    private void copyRecent() {
        String translated = preferences.getString("last_translation", "");
        if (translated.isEmpty()) {
            toast("还没有可复制的译文");
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("浮译译文", translated));
        toast("已复制");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开链接");
        }
    }

    private String engineLabel(String engine) {
        if (TranslationRouter.AUTO.equals(engine)) return "自动 · ML Kit 离线优先";
        return TranslationRouter.engineLabel(engine);
    }

    private String asrLabel(String asr) {
        if (TranslationService.ASR_VOSK.equals(asr)) return "Vosk";
        if (TranslationService.ASR_SENSEVOICE.equals(asr)) return "SenseVoice";
        if (TranslationService.ASR_REAZON.equals(asr)) return "ReazonSpeech";
        if (TranslationService.ASR_PARAKEET.equals(asr)) return "Parakeet 日语";
        if (TranslationService.ASR_WHISPER_SMALL.equals(asr)) return "Whisper Small";
        if (TranslationService.ASR_WHISPER_MEDIUM.equals(asr)) return "Whisper Medium";
        if (TranslationService.ASR_QWEN3.equals(asr)) return "Qwen3-ASR";
        if (TranslationService.ASR_OMNILINGUAL.equals(asr)) return "Omnilingual ASR";
        if (TranslationService.ASR_SYSTEM.equals(asr)) return "系统 SpeechRecognizer";
        if (TranslationService.ASR_YOUDAO.equals(asr)) return "有道云 ASR";
        return "自动推荐";
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override protected void onDestroy() {
        if (updateChecker != null) updateChecker.close();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
