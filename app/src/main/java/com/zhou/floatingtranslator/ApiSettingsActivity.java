package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ApiSettingsActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private SharedPreferences prefs;
    private SecureConfig secure;
    private Spinner engineSpinner;
    private CheckBox youdaoSpeechFallback;
    private CheckBox showSecrets;

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
    private TextView configuredSummary;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        secure = new SecureConfig(this);
        setTitle("浮译 0.5.0 · 翻译引擎与安全");
        setContentView(buildUi());
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("翻译引擎 / API 安全中心", 28, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView note = text(
            "默认推荐“自动”：ML Kit 本地离线优先，只有本地失败时才尝试已经配置的在线引擎。\n" +
            "API Key 都是可选备用；保存后使用 Android Keystore + AES/GCM 加密。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(5), 0, dp(16));
        root.addView(note);

        configuredSummary = text("", 14, Color.rgb(180, 220, 200));
        configuredSummary.setPadding(0, 0, 0, dp(10));
        root.addView(configuredSummary);
        refreshConfiguredSummary();

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

        showSecrets = new CheckBox(this);
        showSecrets.setText("显示 API 密钥内容（默认隐藏）");
        showSecrets.setTextColor(Color.WHITE);
        showSecrets.setChecked(false);
        showSecrets.setOnCheckedChangeListener((button, checked) -> applySecretVisibility(checked));
        root.addView(showSecrets);

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

        Button save = button("💾 保存设置");
        save.setOnClickListener(v -> save(false));
        root.addView(save, matchWrap());

        Button saveBack = button("💾 保存并返回");
        saveBack.setOnClickListener(v -> save(true));
        root.addView(saveBack, matchWrap());

        Button test = button("测试当前引擎（英语 → 中文）");
        test.setOnClickListener(v -> testCurrent(test));
        root.addView(test, matchWrap());

        root.addView(section("安全中心"));
        TextView security = text(
            "这里不会显示或导出完整密钥。把 APK 发给别人，不会把你后来在手机里填写的 Key 一起打包出去。",
            13, Color.rgb(190, 180, 215));
        root.addView(security);

        Button clear = button("清空全部 API 密钥");
        clear.setOnClickListener(v -> confirmClearAll());
        root.addView(clear, matchWrap());

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
        e.setTag(Boolean.valueOf(secretField));
        e.setText(secure.get(key));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        if (secretField) e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        return e;
    }

    private void applySecretVisibility(boolean show) {
        EditText[] fields = {baiduSecret, youdaoSecret, azureKey, deepLKey, googleKey, libreKey};
        for (EditText field : fields) {
            if (field == null) continue;
            int pos = field.getSelectionStart();
            field.setTransformationMethod(show
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
            field.setSelection(Math.max(0, Math.min(pos, field.length())));
        }
    }

    private void save(boolean finishAfter) {
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
            refreshConfiguredSummary();
            status.setText("✅ 已加密保存。在线 Key 仅作为你主动选择或离线失败后的备用。");
            toast("已保存");
            if (finishAfter) finish();
        } catch (Exception e) {
            status.setText("❌ 保存失败：" + safe(e));
        }
    }

    private void refreshConfiguredSummary() {
        if (configuredSummary == null) return;
        List<String> configured = new ArrayList<>();
        if (secure.has(SecureConfig.BAIDU_APP_ID) && secure.has(SecureConfig.BAIDU_SECRET)) configured.add("百度");
        if (secure.has(SecureConfig.YOUDAO_APP_KEY) && secure.has(SecureConfig.YOUDAO_SECRET)) configured.add("有道");
        if (secure.has(SecureConfig.AZURE_KEY)) configured.add("Azure");
        if (secure.has(SecureConfig.DEEPL_KEY)) configured.add("DeepL");
        if (secure.has(SecureConfig.GOOGLE_KEY)) configured.add("Google");
        if (secure.has(SecureConfig.LIBRE_ENDPOINT)) configured.add("LibreTranslate");
        configuredSummary.setText(configured.isEmpty()
            ? "API 安全状态：当前未保存在线 API 配置"
            : "API 安全状态：已配置 " + String.join("、", configured) + "（内容已隐藏）");
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("清空全部 API 密钥？")
            .setMessage("会删除本机保存的百度、有道、Azure、DeepL、Google、LibreTranslate 配置。离线模型不会删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (dialog, which) -> {
                secure.clearAll();
                clearFields();
                refreshConfiguredSummary();
                if (status != null) status.setText("✅ 已清空全部 API 配置");
                toast("API 密钥已清空");
            })
            .show();
    }

    private void clearFields() {
        EditText[] fields = {baiduAppId, baiduSecret, youdaoAppKey, youdaoSecret, azureKey,
            azureRegion, deepLKey, googleKey, libreEndpoint, libreKey};
        for (EditText field : fields) if (field != null) field.setText("");
    }

    private void testCurrent(Button button) {
        save(false);
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
