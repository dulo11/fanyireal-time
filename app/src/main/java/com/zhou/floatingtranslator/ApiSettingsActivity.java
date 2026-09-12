package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class ApiSettingsActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private SharedPreferences prefs;
    private SecureConfig secure;
    private Spinner engineSpinner;
    private CheckBox youdaoSpeechFallback;

    private EditText baiduAppId;
    private EditText baiduSecret;
    private EditText youdaoAppKey;
    private EditText youdaoSecret;
    private EditText azureKey;
    private EditText azureRegion;
    private EditText deepLKey;
    private EditText googleKey;
    private EditText libreEndpoint;
    private EditText libreKey;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        secure = new SecureConfig(this);
        setTitle("浮译 0.4.1 · 翻译引擎");
        setContentView(buildUi());
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("0.4.1 翻译引擎设置", 28, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView note = text(
            "默认推荐“自动”：先使用 ML Kit 本地离线翻译，只有本地失败时才尝试你已经配置的在线引擎。\n" +
            "API Key 都是可选备用，不填也不影响 Vosk 离线语音 + ML Kit 离线翻译。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(5), 0, dp(18));
        root.addView(note);

        root.addView(label("默认翻译引擎"));
        String[] labels = TranslationRouter.ENGINE_LABELS.clone();
        if (labels.length > 0) labels[0] = "自动（ML Kit 离线优先，失败再用在线备用）";
        engineSpinner = new Spinner(this);
        engineSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, labels));
        String current = prefs.getString("engine_id", TranslationRouter.AUTO);
        int selected = 0;
        for (int i = 0; i < TranslationRouter.ENGINE_IDS.length; i++) {
            if (TranslationRouter.ENGINE_IDS[i].equals(current)) selected = i;
        }
        engineSpinner.setSelection(selected);
        engineSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        root.addView(engineSpinner, matchWrap());

        youdaoSpeechFallback = new CheckBox(this);
        youdaoSpeechFallback.setText("离线语音和系统识别都不可用时，允许有道云语音最后兜底");
        youdaoSpeechFallback.setTextColor(Color.WHITE);
        youdaoSpeechFallback.setChecked(prefs.getBoolean("youdao_speech_fallback", true));
        root.addView(youdaoSpeechFallback);

        TextView offline = text(
            "离线语音无需 Key：首次使用会自动下载对应 Vosk 小模型，之后断网可用。\n" +
            "当前支持：" + OfflineSpeechEngine.supportedSummary(),
            13, Color.rgb(180, 220, 200));
        offline.setPadding(0, dp(8), 0, dp(12));
        root.addView(offline);

        root.addView(section("百度翻译（可选备用）"));
        baiduAppId = field("百度 APPID", false, SecureConfig.BAIDU_APP_ID);
        baiduSecret = field("百度密钥", true, SecureConfig.BAIDU_SECRET);
        root.addView(baiduAppId, matchWrap());
        root.addView(baiduSecret, matchWrap());

        root.addView(section("有道智云（可选备用 / 云语音兜底）"));
        youdaoAppKey = field("有道 AppKey / 应用ID", false, SecureConfig.YOUDAO_APP_KEY);
        youdaoSecret = field("有道 AppSecret / 应用密钥", true, SecureConfig.YOUDAO_SECRET);
        root.addView(youdaoAppKey, matchWrap());
        root.addView(youdaoSecret, matchWrap());

        root.addView(section("Azure Translator（可选备用）"));
        azureKey = field("Azure Subscription Key", true, SecureConfig.AZURE_KEY);
        azureRegion = field("Azure Region，例如 japaneast / eastasia", false, SecureConfig.AZURE_REGION);
        root.addView(azureKey, matchWrap());
        root.addView(azureRegion, matchWrap());

        root.addView(section("DeepL（可选备用）"));
        deepLKey = field("DeepL API Key", true, SecureConfig.DEEPL_KEY);
        root.addView(deepLKey, matchWrap());

        root.addView(section("Google Cloud Translation（可选备用）"));
        googleKey = field("Google Cloud API Key", true, SecureConfig.GOOGLE_KEY);
        root.addView(googleKey, matchWrap());

        root.addView(section("LibreTranslate（可选备用）"));
        libreEndpoint = field("Endpoint，例如 https://你的服务/", false, SecureConfig.LIBRE_ENDPOINT);
        libreKey = field("API Key（没有可留空）", true, SecureConfig.LIBRE_KEY);
        root.addView(libreEndpoint, matchWrap());
        root.addView(libreKey, matchWrap());

        Button save = button("保存设置");
        save.setOnClickListener(v -> save());
        root.addView(save, matchWrap());

        Button test = button("测试当前引擎（英语 → 中文）");
        test.setOnClickListener(v -> testCurrent(test));
        root.addView(test, matchWrap());

        status = text("", 14, Color.rgb(201, 190, 221));
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);
        return scroll;
    }

    private EditText field(String hint, boolean secretField, String key) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(155, 145, 180));
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        e.setBackgroundColor(Color.rgb(43, 38, 61));
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setText(secure.get(key));
        if (secretField) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        }
        return e;
    }

    private void save() {
        int pos = engineSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= TranslationRouter.ENGINE_IDS.length) pos = 0;
        prefs.edit()
            .putString("engine_id", TranslationRouter.ENGINE_IDS[pos])
            .putBoolean("youdao_speech_fallback", youdaoSpeechFallback.isChecked())
            .apply();

        try {
            secure.put(SecureConfig.BAIDU_APP_ID, baiduAppId.getText().toString());
            secure.put(SecureConfig.BAIDU_SECRET, baiduSecret.getText().toString());
            secure.put(SecureConfig.YOUDAO_APP_KEY, youdaoAppKey.getText().toString());
            secure.put(SecureConfig.YOUDAO_SECRET, youdaoSecret.getText().toString());
            secure.put(SecureConfig.AZURE_KEY, azureKey.getText().toString());
            secure.put(SecureConfig.AZURE_REGION, azureRegion.getText().toString());
            secure.put(SecureConfig.DEEPL_KEY, deepLKey.getText().toString());
            secure.put(SecureConfig.GOOGLE_KEY, googleKey.getText().toString());
            secure.put(SecureConfig.LIBRE_ENDPOINT, libreEndpoint.getText().toString());
            secure.put(SecureConfig.LIBRE_KEY, libreKey.getText().toString());
            status.setText("✅ 已保存。在线 Key 仅作为你主动选择或离线失败后的备用。");
            toast("已保存");
        } catch (Exception e) {
            status.setText("❌ 保存失败：" + safe(e));
        }
    }

    private void testCurrent(Button button) {
        save();
        int pos = engineSpinner.getSelectedItemPosition();
        String engine = TranslationRouter.ENGINE_IDS[
            Math.max(0, Math.min(pos, TranslationRouter.ENGINE_IDS.length - 1))];
        OfflineFirstTranslationRouter router = new OfflineFirstTranslationRouter(this, "en", "zh", engine);
        button.setEnabled(false);
        status.setText("正在测试……");
        router.translate("Hello, nice to meet you.", new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                status.setText("✅ " + engineName + " 正常\n" + translated);
                button.setEnabled(true);
                router.close();
            }

            @Override public void onError(String message) {
                status.setText("❌ 测试失败：" + message);
                button.setEnabled(true);
                router.close();
            }
        });
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, 1);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 14, Color.rgb(201, 190, 221));
        t.setPadding(0, dp(5), 0, dp(3));
        return t;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
