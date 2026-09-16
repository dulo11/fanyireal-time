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
    private static final String PREF_AZURE_PROFILE_COUNT = "azure_profile_count";

    private SharedPreferences prefs;
    private SecureConfig secure;
    private Spinner engineSpinner;
    private Spinner azureStrategySpinner;
    private CheckBox youdaoSpeechFallback;
    private CheckBox showSecrets;

    private EditText baiduAppId;
    private EditText baiduSecret;
    private final List<EditText> azureKeys = new ArrayList<>();
    private final List<EditText> azureRegions = new ArrayList<>();
    private final List<LinearLayout> azureRows = new ArrayList<>();
    private LinearLayout azureContainer;
    private EditText aliyunAccessKeyId;
    private EditText aliyunAccessKeySecret;
    private EditText youdaoAppKey;
    private EditText youdaoSecret;
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
        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · 翻译引擎与 API");
        setContentView(buildUi());
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("翻译引擎 / API 配置", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = text(
            "默认推荐“自动”：ML Kit 本地离线优先；只有本地失败时才依次尝试已配置的百度、Azure、阿里云。\n" +
            "Azure 改为账号池：需要几套就点“添加账号”，不再限制 1-4。可选轮番使用，或只在配额/限流/订阅错误后自动切换。所有 Key 仅普通保存在本机 SharedPreferences，不做额外加密。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(5), 0, dp(16));
        root.addView(note);

        configuredSummary = text("", 14, Color.rgb(180, 220, 200));
        configuredSummary.setPadding(0, 0, 0, dp(10));
        root.addView(configuredSummary);
        refreshConfiguredSummary();

        root.addView(label("默认翻译引擎"));
        String[] labels = TranslationRouter.ENGINE_LABELS.clone();
        if (labels.length > 0) labels[0] = "自动（ML Kit 离线优先；百度 / Azure / 阿里云兜底）";
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

        root.addView(section("百度翻译（主力在线备用）"));
        baiduAppId = field("百度 APPID", false, SecureConfig.BAIDU_APP_ID);
        baiduSecret = field("百度密钥", true, SecureConfig.BAIDU_SECRET);
        root.addView(baiduAppId, matchWrap());
        root.addView(baiduSecret, matchWrap());

        root.addView(section("Azure Translator 账号池（可继续添加）"));
        TextView azureTip = text(
            "模式一：当前账号正常就一直用，出现 HTTP 401/403/429、quota/exceeded/limit/subscription/rate 等错误后切下一个。\n" +
            "模式二：轮番使用，每次翻译成功后自动换到下一套；如果某套额度用完，也会直接跳过并继续下一套。Region 单服务 Translator 通常可留空。",
            12, Color.rgb(174, 164, 198));
        root.addView(azureTip);

        root.addView(label("Azure 切换策略"));
        azureStrategySpinner = new Spinner(this);
        azureStrategySpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "额度/限流出错后再切换",
                "轮番使用｜每次成功后切下一个"
            }));
        String rotationMode = prefs.getString(TranslationRouter.PREF_AZURE_ROTATION_MODE,
            TranslationRouter.AZURE_ROUND_ROBIN);
        azureStrategySpinner.setSelection(TranslationRouter.AZURE_ROTATE_ON_LIMIT.equals(rotationMode) ? 0 : 1);
        azureStrategySpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        root.addView(azureStrategySpinner, matchWrap());

        azureContainer = new LinearLayout(this);
        azureContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(azureContainer);

        int savedCount = Math.max(1, prefs.getInt(PREF_AZURE_PROFILE_COUNT, 1));
        int highestConfigured = secure.highestConfiguredAzureSlot();
        int initialCount = Math.max(savedCount, Math.max(1, highestConfigured));
        prefs.edit().putInt(PREF_AZURE_PROFILE_COUNT, initialCount).apply();
        for (int slot = 1; slot <= initialCount; slot++) addAzureProfileRow(slot);

        LinearLayout azureButtons = new LinearLayout(this);
        azureButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button addAzure = button("＋ 添加 Azure 账号");
        addAzure.setOnClickListener(v -> {
            int slot = azureKeys.size() + 1;
            addAzureProfileRow(slot);
            prefs.edit().putInt(PREF_AZURE_PROFILE_COUNT, azureKeys.size()).apply();
            toast("已添加 Azure 账号 " + slot);
        });
        Button removeAzure = button("－ 删除最后一个");
        removeAzure.setOnClickListener(v -> removeLastAzureProfile());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1f);
        left.setMargins(0, dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1f);
        right.setMargins(dp(4), dp(6), 0, dp(6));
        azureButtons.addView(addAzure, left);
        azureButtons.addView(removeAzure, right);
        root.addView(azureButtons);

        root.addView(section("阿里云机器翻译（主力在线备用）"));
        aliyunAccessKeyId = field("阿里云 AccessKey ID", false, SecureConfig.ALIYUN_ACCESS_KEY_ID);
        aliyunAccessKeySecret = field("阿里云 AccessKey Secret", true,
            SecureConfig.ALIYUN_ACCESS_KEY_SECRET);
        root.addView(aliyunAccessKeyId, matchWrap());
        root.addView(aliyunAccessKeySecret, matchWrap());
        TextView aliyunTip = text(
            "使用阿里云机器翻译通用版，客户端按 ROA HMAC-SHA1 规则签名；Key 只保存在本机普通配置中。",
            12, Color.rgb(174, 164, 198));
        root.addView(aliyunTip);

        root.addView(section("有道智云（旧兼容 / 云语音兜底）"));
        youdaoAppKey = field("有道 AppKey / 应用ID", false, SecureConfig.YOUDAO_APP_KEY);
        youdaoSecret = field("有道 AppSecret / 应用密钥", true, SecureConfig.YOUDAO_SECRET);
        root.addView(youdaoAppKey, matchWrap());
        root.addView(youdaoSecret, matchWrap());

        root.addView(section("DeepL（旧兼容备用）"));
        deepLKey = field("DeepL API Key", true, SecureConfig.DEEPL_KEY);
        root.addView(deepLKey, matchWrap());

        root.addView(section("Google Cloud Translation（旧兼容备用）"));
        googleKey = field("Google Cloud API Key", true, SecureConfig.GOOGLE_KEY);
        root.addView(googleKey, matchWrap());

        root.addView(section("LibreTranslate（旧兼容备用）"));
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

        root.addView(section("本机 API 配置"));
        TextView security = text(
            "API Key 只保存在当前手机，不会被写进 APK；当前使用普通本地保存，不做额外加密。",
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

    private void addAzureProfileRow(int slot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundColor(Color.rgb(34, 30, 49));

        TextView title = label("Azure 账号 " + slot);
        row.addView(title);
        EditText key = field("账号" + slot + " · Subscription Key", true,
            SecureConfig.azureKeyName(slot));
        EditText region = field("账号" + slot + " · Region（可留空）", false,
            SecureConfig.azureRegionName(slot));
        row.addView(key, matchWrap());
        row.addView(region, matchWrap());

        azureKeys.add(key);
        azureRegions.add(region);
        azureRows.add(row);
        azureContainer.addView(row, matchWrap());

        if (showSecrets != null && showSecrets.isChecked()) applySecretVisibility(true);
    }

    private void removeLastAzureProfile() {
        if (azureKeys.size() <= 1) {
            toast("至少保留一个 Azure 输入位；不用时留空即可");
            return;
        }
        int slot = azureKeys.size();
        boolean hasValue = !azureKeys.get(slot - 1).getText().toString().trim().isEmpty()
            || !azureRegions.get(slot - 1).getText().toString().trim().isEmpty();
        if (hasValue || secure.has(SecureConfig.azureKeyName(slot))) {
            new AlertDialog.Builder(this)
                .setTitle("删除 Azure 账号 " + slot + "？")
                .setMessage("会同时删除这套账号在本机保存的 Key / Region。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> removeLastAzureProfileNow())
                .show();
        } else {
            removeLastAzureProfileNow();
        }
    }

    private void removeLastAzureProfileNow() {
        int slot = azureKeys.size();
        if (slot <= 1) return;
        secure.put(SecureConfig.azureKeyName(slot), "");
        secure.put(SecureConfig.azureRegionName(slot), "");
        LinearLayout row = azureRows.remove(slot - 1);
        azureContainer.removeView(row);
        azureKeys.remove(slot - 1);
        azureRegions.remove(slot - 1);
        prefs.edit()
            .putInt(PREF_AZURE_PROFILE_COUNT, azureKeys.size())
            .putInt(TranslationRouter.PREF_AZURE_ACTIVE_SLOT, 1)
            .apply();
        refreshConfiguredSummary();
        toast("已删除最后一个 Azure 账号位");
    }

    private EditText field(String hint, boolean secretField, String key) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(155, 145, 180));
        edit.setTextColor(Color.WHITE);
        edit.setSingleLine(true);
        edit.setBackgroundColor(Color.rgb(43, 38, 61));
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        edit.setTag(Boolean.valueOf(secretField));
        edit.setText(secure.get(key));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        if (secretField) edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
        return edit;
    }

    private void applySecretVisibility(boolean show) {
        List<EditText> fields = new ArrayList<>();
        fields.add(baiduSecret);
        fields.addAll(azureKeys);
        fields.add(aliyunAccessKeySecret);
        fields.add(youdaoSecret);
        fields.add(deepLKey);
        fields.add(googleKey);
        fields.add(libreKey);
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
        String azureMode = azureStrategySpinner.getSelectedItemPosition() == 0
            ? TranslationRouter.AZURE_ROTATE_ON_LIMIT : TranslationRouter.AZURE_ROUND_ROBIN;
        prefs.edit()
            .putString("engine_id", TranslationRouter.ENGINE_IDS[pos])
            .putBoolean("youdao_speech_fallback", youdaoSpeechFallback.isChecked())
            .putInt(PREF_AZURE_PROFILE_COUNT, azureKeys.size())
            .putString(TranslationRouter.PREF_AZURE_ROTATION_MODE, azureMode)
            .apply();

        try {
            secure.put(SecureConfig.BAIDU_APP_ID, baiduAppId.getText().toString());
            secure.put(SecureConfig.BAIDU_SECRET, baiduSecret.getText().toString());
            for (int i = 0; i < azureKeys.size(); i++) {
                int slot = i + 1;
                secure.put(SecureConfig.azureKeyName(slot), azureKeys.get(i).getText().toString());
                secure.put(SecureConfig.azureRegionName(slot), azureRegions.get(i).getText().toString());
            }
            secure.put(SecureConfig.ALIYUN_ACCESS_KEY_ID, aliyunAccessKeyId.getText().toString());
            secure.put(SecureConfig.ALIYUN_ACCESS_KEY_SECRET, aliyunAccessKeySecret.getText().toString());
            secure.put(SecureConfig.YOUDAO_APP_KEY, youdaoAppKey.getText().toString());
            secure.put(SecureConfig.YOUDAO_SECRET, youdaoSecret.getText().toString());
            secure.put(SecureConfig.DEEPL_KEY, deepLKey.getText().toString());
            secure.put(SecureConfig.GOOGLE_KEY, googleKey.getText().toString());
            secure.put(SecureConfig.LIBRE_ENDPOINT, libreEndpoint.getText().toString());
            secure.put(SecureConfig.LIBRE_KEY, libreKey.getText().toString());
            refreshConfiguredSummary();
            status.setText("✅ 已保存到本机。Azure 账号池：" + secure.configuredAzureProfileCount()
                + " 套；策略：" + (TranslationRouter.AZURE_ROUND_ROBIN.equals(azureMode)
                ? "轮番使用" : "额度/限流后切换") + "。");
            toast("已保存");
            if (finishAfter) finish();
        } catch (Exception e) {
            status.setText("❌ 保存失败：" + safe(e));
        }
    }

    private void refreshConfiguredSummary() {
        if (configuredSummary == null) return;
        List<String> configured = new ArrayList<>();
        if (secure.has(SecureConfig.BAIDU_APP_ID) && secure.has(SecureConfig.BAIDU_SECRET)) {
            configured.add("百度");
        }
        int azureCount = secure.configuredAzureProfileCount();
        if (azureCount > 0) configured.add("Azure×" + azureCount);
        if (secure.has(SecureConfig.ALIYUN_ACCESS_KEY_ID)
            && secure.has(SecureConfig.ALIYUN_ACCESS_KEY_SECRET)) configured.add("阿里云");
        if (secure.has(SecureConfig.YOUDAO_APP_KEY) && secure.has(SecureConfig.YOUDAO_SECRET)) {
            configured.add("有道(兼容)");
        }
        if (secure.has(SecureConfig.DEEPL_KEY)) configured.add("DeepL(兼容)");
        if (secure.has(SecureConfig.GOOGLE_KEY)) configured.add("Google(兼容)");
        if (secure.has(SecureConfig.LIBRE_ENDPOINT)) configured.add("LibreTranslate(兼容)");
        configuredSummary.setText(configured.isEmpty()
            ? "API 配置状态：当前未保存在线 API 配置"
            : "API 配置状态：已配置 " + String.join("、", configured) + "（输入框默认隐藏密钥）");
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("清空全部 API 密钥？")
            .setMessage("会删除本机保存的百度、全部 Azure 账号、阿里云及其他兼容 API 配置。离线模型不会删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (dialog, which) -> {
                secure.clearAll();
                clearFields();
                prefs.edit().remove(TranslationRouter.PREF_AZURE_ACTIVE_SLOT).apply();
                refreshConfiguredSummary();
                if (status != null) status.setText("✅ 已清空全部 API 配置");
                toast("API 密钥已清空");
            })
            .show();
    }

    private void clearFields() {
        List<EditText> fields = new ArrayList<>();
        fields.add(baiduAppId);
        fields.add(baiduSecret);
        fields.addAll(azureKeys);
        fields.addAll(azureRegions);
        fields.add(aliyunAccessKeyId);
        fields.add(aliyunAccessKeySecret);
        fields.add(youdaoAppKey);
        fields.add(youdaoSecret);
        fields.add(deepLKey);
        fields.add(googleKey);
        fields.add(libreEndpoint);
        fields.add(libreKey);
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
        TextView text = text(value, 18, Color.WHITE);
        text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(18), 0, dp(4));
        return text;
    }

    private TextView label(String value) {
        TextView text = text(value, 14, Color.rgb(201, 190, 221));
        text.setPadding(0, dp(5), 0, dp(3));
        return text;
    }

    private TextView text(String value, float sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        return text;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.button_secondary);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
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
