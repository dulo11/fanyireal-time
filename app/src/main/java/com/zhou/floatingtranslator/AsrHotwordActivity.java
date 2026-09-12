package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;

/** User-maintained names/terms that can bias high-accuracy recognition and review selection. */
public final class AsrHotwordActivity extends Activity {
    private static final String PREFS = "floating_translator";
    public static final String KEY_HOTWORDS = "asr_hotwords";
    public static final String KEY_SCORE = "asr_hotword_score";

    private SharedPreferences prefs;
    private EditText editor;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setTitle("ASR 热词 / 人名");
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

        TextView title = text("热词 / 人名词库", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView intro = text(
            "一行一个词，适合主播名、角色名、游戏名、地名、公司名、英文缩写等。\n" +
            "高精度模式会把这些词用于候选结果评分；ReazonSpeech 的 modified beam search 还会尝试原生 hotwords 偏置。" +
            "热词过多或权重过强也可能误识别，所以默认使用中等强度。",
            14, Color.rgb(205, 195, 222));
        intro.setPadding(0, dp(7), 0, dp(14));
        root.addView(intro);

        editor = new EditText(this);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(Color.rgb(145, 135, 165));
        editor.setHint("例：\nホロライブ\nさくらみこ\nTelegram\nOpenAI");
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(10);
        editor.setBackgroundResource(R.drawable.panel);
        editor.setPadding(dp(14), dp(12), dp(14), dp(12));
        editor.setText(prefs.getString(KEY_HOTWORDS, ""));
        root.addView(editor, new LinearLayout.LayoutParams(-1, -2));

        TextView strength = text("热词偏置强度", 18, Color.WHITE);
        strength.setTypeface(null, 1);
        strength.setPadding(0, dp(16), 0, dp(4));
        root.addView(strength);

        Button normal = button("标准 1.8｜推荐");
        normal.setOnClickListener(v -> saveScore(1.8f));
        root.addView(normal, params());
        Button strong = button("较强 2.6｜专有名词很多时");
        strong.setOnClickListener(v -> saveScore(2.6f));
        root.addView(strong, params());
        Button veryStrong = button("很强 4.0｜可能增加误判");
        veryStrong.setOnClickListener(v -> saveScore(4.0f));
        root.addView(veryStrong, params());

        Button save = button("💾 保存热词");
        save.setOnClickListener(v -> saveWords());
        root.addView(save, params());

        Button clear = button("清空热词");
        clear.setOnClickListener(v -> {
            editor.setText("");
            prefs.edit().remove(KEY_HOTWORDS).apply();
            refreshStatus();
            toast("热词已清空");
        });
        root.addView(clear, params());

        status = text("", 13, Color.rgb(190, 165, 255));
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);
        refreshStatus();
        return scroll;
    }

    private void saveWords() {
        String raw = editor.getText() == null ? "" : editor.getText().toString();
        String[] lines = raw.split("\\r?\\n");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            if (value.length() > 80) value = value.substring(0, 80);
            unique.add(value);
            if (unique.size() >= 200) break;
        }
        String joined = android.text.TextUtils.join("\n", unique);
        prefs.edit().putString(KEY_HOTWORDS, joined).apply();
        editor.setText(joined);
        editor.setSelection(joined.length());
        refreshStatus();
        toast("已保存 " + unique.size() + " 个热词");
    }

    private void saveScore(float score) {
        prefs.edit().putFloat(KEY_SCORE, score).apply();
        refreshStatus();
        toast("热词强度已设为 " + score);
    }

    private void refreshStatus() {
        if (status == null) return;
        String raw = prefs.getString(KEY_HOTWORDS, "");
        int count = 0;
        for (String line : raw.split("\\r?\\n")) if (!line.trim().isEmpty()) count++;
        float score = prefs.getFloat(KEY_SCORE, 1.8f);
        status.setText("当前：" + count + " 个热词 · 偏置强度 " + score + "\n修改后，下次重新启动 ASR 时生效。");
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
