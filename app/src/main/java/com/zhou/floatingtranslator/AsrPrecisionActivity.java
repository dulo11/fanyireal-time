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

/** Global precision preference for sherpa-onnx ASR models. */
public final class AsrPrecisionActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private SharedPreferences prefs;
    private TextView current;
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

        TextView title = text("ASR 数值精度", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView intro = text(
            "FP32 是原始 32 位浮点权重，INT8 是 8 位量化权重。FP32 通常更占空间、内存和算力；INT8 更适合手机实时识别。" +
            "量化可能带来少量精度损失，但“FP32 一定明显更准”并不成立，模型大小、训练数据和语种匹配往往影响更大。\n\n" +
            "这里是全局偏好：普通直播翻译和 ROOT 通话翻译都会使用同一个设置。",
            14, Color.rgb(205, 195, 222));
        intro.setPadding(0, dp(7), 0, dp(14));
        root.addView(intro);

        LinearLayout card = card(root);
        card.addView(section("当前设置"));
        current = text("", 15, Color.rgb(190, 165, 255));
        current.setPadding(0, dp(6), 0, dp(8));
        card.addView(current);

        Button auto = button("自动｜INT8 优先，缺失时使用 FP32");
        auto.setOnClickListener(v -> save(SherpaSpeechEngine.PRECISION_AUTO));
        card.addView(auto, params());

        Button fp32 = button("FP32 原始权重｜精准优先 / 高内存占用");
        fp32.setOnClickListener(v -> save(SherpaSpeechEngine.PRECISION_FP32));
        card.addView(fp32, params());

        Button int8 = button("INT8 量化｜手机实时 / 更快 / 更省内存");
        int8.setOnClickListener(v -> save(SherpaSpeechEngine.PRECISION_INT8));
        card.addView(int8, params());

        LinearLayout modelCard = card(root);
        modelCard.addView(section("现有模型精度支持"));
        models = text("", 13, Color.rgb(218, 209, 231));
        models.setPadding(0, dp(7), 0, 0);
        modelCard.addView(models);

        TextView note = text(
            "SenseVoice 当前约 1 GB 的完整包不是白下：里面同时有约 894 MB 的 FP32 和约 228 MB 的 INT8，切换精度后会真正加载对应文件。\n" +
            "ReazonSpeech、Whisper Small、Whisper Medium 的现有完整包也同时带 FP32 + INT8。\n" +
            "Parakeet 日语、Qwen3-ASR 0.6B、Omnilingual 300M 当前 App 一键下载包以 INT8 为主；即使选择 FP32，也会安全回退到 INT8，不会启动失败。",
            13, Color.rgb(180, 170, 205));
        note.setPadding(dp(2), dp(6), dp(2), 0);
        root.addView(note);

        refresh();
        return scroll;
    }

    private void save(String value) {
        prefs.edit().putString("asr_precision", value).apply();
        refresh();
        toast("ASR 精度已保存：" + label(value));
    }

    private void refresh() {
        String selected = prefs.getString("asr_precision", SherpaSpeechEngine.PRECISION_AUTO);
        if (current != null) current.setText("当前：" + label(selected));
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

    private String label(String value) {
        if (SherpaSpeechEngine.PRECISION_FP32.equals(value)) return "FP32 原始权重";
        if (SherpaSpeechEngine.PRECISION_INT8.equals(value)) return "INT8 量化";
        return "自动（INT8 优先）";
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
