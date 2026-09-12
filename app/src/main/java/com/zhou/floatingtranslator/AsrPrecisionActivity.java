package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Global precision and fast-speech preferences for sherpa-onnx ASR models. */
public final class AsrPrecisionActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private SharedPreferences prefs;
    private TextView current;
    private TextView conversationCurrent;
    private TextView advancedStatus;
    private TextView models;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setTitle("ASR 精度");
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(30));
        scroll.addView(root);

        TextView title = text("ASR 精度与快语速", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView intro = text(
            "目标是快速聊天也尽量听完整：高精度模式优先使用 Silero 神经网络 VAD 切句，保留前文音频上下文，" +
            "长句或可疑结果会自动再识别一次。普通直播和 ROOT 通话共用这些设置。",
            14, Color.rgb(205, 195, 222));
        intro.setPadding(0, dp(7), 0, dp(14));
        root.addView(intro);

        LinearLayout conversationCard = card(root);
        conversationCard.addView(section("对话识别模式"));
        conversationCurrent = text("", 15, Color.rgb(190, 165, 255));
        conversationCurrent.setPadding(0, dp(6), 0, dp(8));
        conversationCard.addView(conversationCurrent);

        Button accuracy = button("👑 高精度·快语速｜默认｜Silero VAD + 上下文 + 二次校正");
        accuracy.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_ACCURACY));
        conversationCard.addView(accuracy, params());

        Button balanced = button("⚖ 均衡对话｜Silero VAD + 较短上下文");
        balanced.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_BALANCED));
        conversationCard.addView(balanced, params());

        Button low = button("⚡ 低延迟｜音量切句｜更快出字");
        low.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_LOW_LATENCY));
        conversationCard.addView(low, params());

        advancedStatus = text("", 13, Color.rgb(184, 174, 207));
        advancedStatus.setPadding(0, dp(7), 0, dp(4));
        conversationCard.addView(advancedStatus);

        Button hotwords = button("🧠 热词 / 人名词库");
        hotwords.setOnClickListener(v -> startActivity(new Intent(this, AsrHotwordActivity.class)));
        conversationCard.addView(hotwords, params());

        LinearLayout card = card(root);
        card.addView(section("模型权重精度"));
        current = text("", 15, Color.rgb(190, 165, 255));
        current.setPadding(0, dp(6), 0, dp(8));
        card.addView(current);

        Button auto = button("自动｜INT8 优先，缺失时使用 FP32");
        auto.setOnClickListener(v -> savePrecision(SherpaSpeechEngine.PRECISION_AUTO));
        card.addView(auto, params());

        Button fp32 = button("FP32 原始权重｜精度优先 / 高内存占用");
        fp32.setOnClickListener(v -> savePrecision(SherpaSpeechEngine.PRECISION_FP32));
        card.addView(fp32, params());

        Button int8 = button("INT8 量化｜速度 / 内存优先");
        int8.setOnClickListener(v -> savePrecision(SherpaSpeechEngine.PRECISION_INT8));
        card.addView(int8, params());

        LinearLayout modelCard = card(root);
        modelCard.addView(section("现有模型精度支持"));
        models = text("", 13, Color.rgb(218, 209, 231));
        models.setPadding(0, dp(7), 0, 0);
        modelCard.addView(models);

        TextView note = text(
            "SenseVoice、ReazonSpeech、Whisper Small / Medium 的完整包可切换 FP32 + INT8；" +
            "Parakeet 日语、Qwen3-ASR 0.6B、Omnilingual 300M 当前一键包以 INT8 为主。\n" +
            "快语速准确率通常先受模型能力、切句和上下文影响，再受 FP32/INT8 影响。",
            13, Color.rgb(180, 170, 205));
        note.setPadding(dp(2), dp(6), dp(2), 0);
        root.addView(note);

        refresh();
        return scroll;
    }

    private void savePrecision(String value) {
        prefs.edit().putString("asr_precision", value).apply();
        refresh();
        toast("ASR 权重精度已保存：" + precisionLabel(value));
    }

    private void saveConversation(String value) {
        prefs.edit().putString("asr_conversation_profile", value).apply();
        refresh();
        toast("对话识别模式已保存：" + conversationLabel(value));
    }

    private void refresh() {
        String selected = prefs.getString("asr_precision", SherpaSpeechEngine.PRECISION_AUTO);
        String profile = prefs.getString("asr_conversation_profile", SherpaSpeechEngine.PROFILE_ACCURACY);
        if (current != null) current.setText("当前：" + precisionLabel(selected));
        if (conversationCurrent != null) conversationCurrent.setText("当前：" + conversationLabel(profile));
        if (advancedStatus != null) {
            String raw = prefs.getString(AsrHotwordActivity.KEY_HOTWORDS, "");
            int count = 0;
            for (String line : raw.split("\\r?\\n")) if (!line.trim().isEmpty()) count++;
            advancedStatus.setText(
                "Silero VAD：" + (VadModelStore.isReady(this) ? "✅ 已准备" : "○ 首次高精度识别时自动下载") +
                "\n热词：" + count + " 个 · 高精度长句会按需要自动二次上下文校正");
        }
        if (models == null) return;
        OfflineModelStore store = new OfflineModelStore(this);
        StringBuilder out = new StringBuilder();
        for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all()) {
            boolean installed = store.isInstalled(model.id);
            out.append(installed ? "✅ " : "○ ")
                .append(model.name)
                .append("：")
                .append(model.precisionText())
                .append('\n');
        }
        store.close();
        models.setText(out.toString().trim());
    }

    private String precisionLabel(String value) {
        if (SherpaSpeechEngine.PRECISION_FP32.equals(value)) return "FP32 原始权重";
        if (SherpaSpeechEngine.PRECISION_INT8.equals(value)) return "INT8 量化";
        return "自动（INT8 优先）";
    }

    private String conversationLabel(String value) {
        if (SherpaSpeechEngine.PROFILE_LOW_LATENCY.equals(value)) return "低延迟";
        if (SherpaSpeechEngine.PROFILE_BALANCED.equals(value)) return "均衡对话";
        return "高精度·快语速";
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.NO_GRAVITY);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.panel);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        root.addView(card, p);
        return card;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, 1);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
