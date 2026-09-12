package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Global precision and conversation-profile preferences for sherpa-onnx ASR models. */
public final class AsrPrecisionActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private SharedPreferences prefs;
    private TextView current;
    private TextView conversationCurrent;
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
            "这里分两件事：FP32 / INT8 是模型权重精度；“高精度·快语速”控制一句话如何切段。" +
            "快速聊天时，过早切句通常比 INT8/FP32 差异更影响识别结果。\n\n" +
            "普通直播和 ROOT 通话共用这些设置。",
            14, Color.rgb(205, 195, 222));
        intro.setPadding(0, dp(7), 0, dp(14));
        root.addView(intro);

        LinearLayout conversationCard = card(root);
        conversationCard.addView(section("对话识别模式"));
        conversationCurrent = text("", 15, Color.rgb(190, 165, 255));
        conversationCurrent.setPadding(0, dp(6), 0, dp(8));
        conversationCard.addView(conversationCurrent);

        Button accuracy = button("👑 高精度·快语速｜默认｜长句 + 约 0.9 秒重叠保护");
        accuracy.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_ACCURACY));
        conversationCard.addView(accuracy, params());

        Button balanced = button("⚖ 均衡对话｜中等延迟｜约 0.45 秒重叠");
        balanced.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_BALANCED));
        conversationCard.addView(balanced, params());

        Button low = button("⚡ 低延迟｜更快出字｜快语速更容易切断");
        low.setOnClickListener(v -> saveConversation(SherpaSpeechEngine.PROFILE_LOW_LATENCY));
        conversationCard.addView(low, params());

        TextView conversationTip = text(
            "高精度模式会把连续讲话的强制切段从旧版约 4.2 秒延长到约 10 秒，静音结束判断也更宽松；" +
            "必须强制切段时保留约 0.9 秒音频重叠，并自动去掉重复文字。ReazonSpeech 在高精度模式还会尝试 modified beam search。",
            13, Color.rgb(184, 174, 207));
        conversationTip.setPadding(0, dp(7), 0, 0);
        conversationCard.addView(conversationTip);

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
            "SenseVoice 完整包同时有 FP32 + INT8；ReazonSpeech、Whisper Small、Whisper Medium 的完整包也可切换。\n" +
            "Parakeet 日语、Qwen3-ASR 0.6B、Omnilingual 300M 当前一键下载包以 INT8 为主；选择 FP32 时会安全回退。\n" +
            "如果目标是快语速准确率，优先换更强模型 + 高精度快语速模式，再考虑把 INT8 切到 FP32。",
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
