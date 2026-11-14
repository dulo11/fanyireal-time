package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ASR is selected explicitly here, independently of the translation API.
 * Failure of the selected speech engine never starts another ASR engine.
 */
public final class AsrEngineSettingsActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private static final String[] IDS = {
        TranslationService.ASR_SYSTEM,
        TranslationService.ASR_WHISPER_SMALL,
        TranslationService.ASR_WHISPER_MEDIUM,
        TranslationService.ASR_OMNILINGUAL,
        TranslationService.ASR_QWEN3,
        TranslationService.ASR_SENSEVOICE,
        TranslationService.ASR_REAZON,
        TranslationService.ASR_PARAKEET,
        TranslationService.ASR_VOSK,
        TranslationService.ASR_GROQ_LARGE,
        TranslationService.ASR_GROQ_TURBO,
        TranslationService.ASR_CLOUDFLARE,
        TranslationService.ASR_YOUDAO
    };
    private static final String[] TITLES = {
        "安卓系统内置 SpeechRecognizer｜独立模式（默认）",
        "Whisper Small｜本地模型",
        "Whisper Medium｜本地模型",
        "Omnilingual 300M｜本地小语种模型",
        "Qwen3-ASR 0.6B｜本地模型",
        "SenseVoice｜本地模型",
        "ReazonSpeech｜本地日语",
        "Parakeet｜本地日语",
        "Vosk｜本地小模型",
        "Groq Whisper Large V3｜在线（需配置）",
        "Groq Whisper Large V3 Turbo｜在线（需配置）",
        "Cloudflare Whisper｜在线（需配置）",
        "有道云语音｜在线付费（需明确配置）"
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("语音识别引擎");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);

        TextView title = text("语音识别引擎（手动固定）", 24, Color.WHITE);
        root.addView(title);
        TextView tip = text(
            "一次只使用你选中的语音识别引擎。失败后直接报告原因，不自动切换 Groq、Cloudflare、Vosk 或付费接口。\n\n" +
            "安卓内置识别麦克风通常更容易成功；直播内部声音需要系统语音服务支持 Android 13+ 的外部 PCM 输入。" +
            "如果厂商不支持，软件无法强制开启。\n\n" +
            "识别引擎负责声音→文字；Azure/微软翻译等负责文字→中文，两者相互独立。",
            14, Color.rgb(201, 190, 221));
        tip.setPadding(0, dp(10), 0, dp(12));
        root.addView(tip);

        Spinner selector = new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, TITLES));
        selector.setBackgroundColor(Color.rgb(51, 45, 73));
        String old = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("asr_mode", TranslationService.ASR_SYSTEM);
        String chosen = TranslationService.strictAsrMode(old);
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(chosen)) { selector.setSelection(i); break; }
        }
        root.addView(selector, new LinearLayout.LayoutParams(-1, dp(62)));

        Button save = new Button(this);
        save.setText("保存识别引擎（下次启动实时翻译生效）");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackgroundResource(R.drawable.button_primary);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(18);
        root.addView(save, p);
        save.setOnClickListener(v -> {
            String mode = IDS[selector.getSelectedItemPosition()];
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putString("asr_mode", mode).apply();
            Toast.makeText(this, "已固定为：" + TITLES[selector.getSelectedItemPosition()]
                + "。请重新启动实时翻译。", Toast.LENGTH_LONG).show();
            finish();
        });
        setContentView(scroll);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
