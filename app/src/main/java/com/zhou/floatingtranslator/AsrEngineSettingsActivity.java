package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * ASR is selected explicitly here, independently of the translation API.
 * Failure of the selected speech engine never starts another ASR engine.
 */
public final class AsrEngineSettingsActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private static final String[] CLOUD_IDS = {
        TranslationService.ASR_GROQ_LARGE,
        TranslationService.ASR_GROQ_TURBO,
        TranslationService.ASR_CLOUDFLARE,
        TranslationService.ASR_YOUDAO
    };
    private static final String[] CLOUD_NAMES = {
        "Groq Whisper Large V3｜15.5亿参数｜在线（需 API）",
        "Groq Whisper Large V3 Turbo｜8.09亿参数｜在线（需 API）",
        "Cloudflare Whisper Turbo｜8.09亿参数｜在线（需 API）",
        "有道云语音｜参数未公开｜在线付费（需明确配置）"
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

        final List<String> ids = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        ids.add(TranslationService.ASR_SYSTEM);
        titles.add("安卓系统内置 SpeechRecognizer｜模型大小由系统决定｜独立模式");
        for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all(this)) {
            ids.add(model.id);
            titles.add(model.name + "｜" + model.parameterText() + "｜" + model.remark);
        }
        ids.add(TranslationService.ASR_VOSK);
        titles.add("Vosk｜语言模型参数视所选包｜轻量离线");
        for (int i = 0; i < CLOUD_IDS.length; i++) {
            ids.add(CLOUD_IDS[i]);
            titles.add(CLOUD_NAMES[i]);
        }

        Spinner selector = new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, titles));
        selector.setBackgroundColor(Color.rgb(51, 45, 73));
        String old = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("asr_mode", TranslationService.ASR_SYSTEM);
        String chosen = TranslationService.strictAsrMode(old);
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equals(chosen)) { selector.setSelection(i); break; }
        }
        root.addView(selector, new LinearLayout.LayoutParams(-1, dp(62)));

        TextView source = text("", 12, Color.rgb(179, 216, 235));
        source.setPadding(0, dp(6), 0, dp(10));
        root.addView(source);
        Button official = new Button(this);
        official.setText("🌐 打开所选识别模型 / 语音服务的研发方官网");
        official.setTextColor(Color.WHITE);
        official.setAllCaps(false);
        official.setBackgroundResource(R.drawable.button_secondary);
        root.addView(official, new LinearLayout.LayoutParams(-1, -2));
        Runnable updateSource = () -> {
            String currentId = ids.get(Math.max(0, selector.getSelectedItemPosition()));
            OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, currentId);
            String link = model == null ? ModelSourceLinks.speechRecognizerUrl(currentId)
                : ModelSourceLinks.officialModelUrl(model.runtimeModelId);
            source.setText("官方出处：" + (link.isEmpty() ? "未公开或暂无准确地址" : link)
                + "\n语音识别与 Azure 等文字翻译是不同的服务。"
                + "如果选择在线识别，语音片段会上传给对应提供商。");
            official.setEnabled(!link.isEmpty());
            official.setOnClickListener(v -> ModelSourceLinks.open(this, link));
        };
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                   int position, long id) {
                updateSource.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateSource.run();

        Button privacy = new Button(this);
        privacy.setText("🔒 了解音频权限 / 数据去向 / 查看完整源码");
        privacy.setTextColor(Color.WHITE);
        privacy.setAllCaps(false);
        privacy.setBackgroundResource(R.drawable.button_secondary);
        privacy.setOnClickListener(v -> startActivity(
            new android.content.Intent(this, PrivacyAndSourcesActivity.class)));
        root.addView(privacy, new LinearLayout.LayoutParams(-1, -2));

        Button save = new Button(this);
        save.setText("保存识别引擎（下次启动实时翻译生效）");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackgroundResource(R.drawable.button_primary);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(18);
        root.addView(save, p);
        save.setOnClickListener(v -> {
            String mode = ids.get(selector.getSelectedItemPosition());
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putString("asr_mode", mode).apply();
            Toast.makeText(this, "已固定为：" + titles.get(selector.getSelectedItemPosition())
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
