package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_CAPTURE = 11;
    private static final String PREFS = "floating_translator";

    private SharedPreferences preferences;
    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private Spinner inputModeSpinner;
    private Spinner asrModeSpinner;
    private CheckBox showOriginal;
    private CheckBox preferOffline;
    private CheckBox enableOcr;
    private SeekBar fontSize;
    private TextView engineStatus;
    private TextView status;
    private TextView history;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean("v043_migrated", false)) {
            preferences.edit()
                .putBoolean("v043_migrated", true)
                .putBoolean("prefer_offline", true)
                .putBoolean("enable_ocr", false)
                .putBoolean("auto_mic_fallback", false)
                .putString("asr_mode", TranslationService.ASR_AUTO)
                .apply();
        }
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column();
        root.setPadding(dp(20), dp(28), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("浮译 0.4.3", 34, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("固定声音来源 · ASR 可选 · 流式识别翻译 · 离线优先", 15,
            Color.rgb(201, 190, 221));
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.panel);
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        engineStatus = text("", 14, Color.rgb(190, 165, 255));
        engineStatus.setPadding(0, 0, 0, dp(8));
        card.addView(engineStatus);
        updateEngineStatus();

        Button engineSettings = secondaryButton("⚙ 翻译引擎 / API 备用设置");
        engineSettings.setOnClickListener(v -> startActivity(new Intent(this, ApiSettingsActivity.class)));
        card.addView(engineSettings, matchWrap());

        Button modelManager = secondaryButton("📦 离线模型管理 / 删除下载包");
        modelManager.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        card.addView(modelManager, matchWrap());

        card.addView(label("固定声音来源（选中后一直使用，绝不自动切换）"));
        inputModeSpinner = new Spinner(this);
        inputModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "系统内部声音（直播/视频）",
                "麦克风识别手机外放"
            }));
        inputModeSpinner.setSelection(Math.min(1, preferences.getInt("input_mode", 0)));
        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(inputModeSpinner, matchWrap());

        card.addView(label("语音识别 ASR（全部显示，可固定选择）"));
        asrModeSpinner = new Spinner(this);
        asrModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "自动：Vosk 离线 → 系统识别 → 有道云",
                "Vosk 内置离线 ASR",
                "Android 系统 SpeechRecognizer",
                "有道云语音 ASR（需 AppKey/AppSecret）"
            }));
        asrModeSpinner.setSelection(asrIndex(preferences.getString("asr_mode", TranslationService.ASR_AUTO)));
        asrModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(asrModeSpinner, matchWrap());

        TextView asrInfo = text(
            "已接入：Vosk 离线 ASR、Android 系统 SpeechRecognizer、有道云语音 ASR。\n" +
            "未接入：sherpa-onnx / Whisper（后续大模型页再加入，不假装已经可用）。\n" +
            "注意：系统 SpeechRecognizer 只能稳定用于麦克风；系统内部声音建议选 Vosk 或有道云。",
            13, Color.rgb(184, 174, 207));
        asrInfo.setPadding(0, dp(4), 0, dp(8));
        card.addView(asrInfo);

        card.addView(label("原语言"));
        sourceSpinner = spinner();
        sourceSpinner.setSelection(preferences.getInt("source_index", 2));
        card.addView(sourceSpinner, matchWrap());

        card.addView(label("翻译为"));
        targetSpinner = spinner();
        targetSpinner.setSelection(preferences.getInt("target_index", 0));
        card.addView(targetSpinner, matchWrap());

        Button swap = secondaryButton("⇄ 一键互换语言");
        swap.setOnClickListener(v -> {
            int source = sourceSpinner.getSelectedItemPosition();
            sourceSpinner.setSelection(targetSpinner.getSelectedItemPosition());
            targetSpinner.setSelection(source);
            saveSettings();
        });
        card.addView(swap, matchWrap());

        showOriginal = check("同时显示原文", preferences.getBoolean("show_original", true));
        card.addView(showOriginal);

        enableOcr = check("开启屏幕 OCR（直播语音建议关闭）",
            preferences.getBoolean("enable_ocr", false));
        card.addView(enableOcr);

        preferOffline = check("系统 SpeechRecognizer 也请求离线模式",
            preferences.getBoolean("prefer_offline", true));
        card.addView(preferOffline);

        card.addView(label("字幕大小"));
        fontSize = new SeekBar(this);
        fontSize.setMax(18);
        fontSize.setProgress(preferences.getInt("font_size", 8));
        card.addView(fontSize, matchWrap());

        Button overlay = secondaryButton("① 授予悬浮窗权限");
        overlay.setOnClickListener(v -> openOverlaySettings());
        card.addView(overlay, matchWrap());

        Button model = secondaryButton("② 下载当前 ML Kit 离线翻译模型");
        model.setOnClickListener(v -> downloadCurrentModel(model));
        card.addView(model, matchWrap());

        Button engineTest = secondaryButton("③ 测试当前翻译引擎");
        engineTest.setOnClickListener(v -> testTranslationEngine(engineTest));
        card.addView(engineTest, matchWrap());

        Button start = primaryButton("④ 开始实时翻译");
        start.setOnClickListener(v -> startCapture());
        card.addView(start, matchWrap());

        Button stop = secondaryButton("停止翻译");
        stop.setOnClickListener(v -> {
            startService(new Intent(this, TranslationService.class)
                .setAction(TranslationService.ACTION_STOP));
            status.setText("已停止");
        });
        card.addView(stop, matchWrap());

        Button copy = secondaryButton("复制最近译文");
        copy.setOnClickListener(v -> copyLastTranslation());
        card.addView(copy, matchWrap());

        status = text(
            "0.4.3：声音来源固定；ASR 可固定选择；Vosk 的中间识别结果也会流式翻译，不再必须等对方停下来才出译文。",
            14, Color.rgb(201, 190, 221));
        status.setPadding(0, dp(12), 0, 0);
        card.addView(status);

        history = text("", 14, Color.rgb(218, 209, 231));
        history.setPadding(0, dp(12), 0, 0);
        card.addView(history);
        updateHistory();

        TextView note = text(
            "日语直播推荐：系统内部声音 + Vosk 内置离线 ASR + 日语→中文 + OCR 关闭。\n" +
            "如果某个 App 禁止内录，再手动切成“麦克风识别手机外放”。\n\n" +
            "快语速/长句：现在每约 1.2 秒会对最新稳定的 Vosk partial 做一次流式翻译，最终结果出来后再覆盖成完整译文；长文本还会分段翻译后合并。",
            13, Color.rgb(180, 170, 205));
        note.setPadding(dp(4), dp(20), dp(4), 0);
        root.addView(note);

        return scroll;
    }

    private Spinner spinner() {
        Spinner s = new Spinner(this);
        ArrayAdapter<LanguageOption> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL);
        s.setAdapter(adapter);
        s.setBackgroundColor(Color.rgb(51, 45, 73));
        return s;
    }

    private int asrIndex(String mode) {
        if (TranslationService.ASR_VOSK.equals(mode)) return 1;
        if (TranslationService.ASR_SYSTEM.equals(mode)) return 2;
        if (TranslationService.ASR_YOUDAO.equals(mode)) return 3;
        return 0;
    }

    private String selectedAsrMode() {
        int pos = asrModeSpinner == null ? 0 : asrModeSpinner.getSelectedItemPosition();
        switch (pos) {
            case 1: return TranslationService.ASR_VOSK;
            case 2: return TranslationService.ASR_SYSTEM;
            case 3: return TranslationService.ASR_YOUDAO;
            default: return TranslationService.ASR_AUTO;
        }
    }

    private void downloadCurrentModel(Button button) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("原语言和目标语言不能相同");
            return;
        }
        button.setEnabled(false);
        status.setText("正在下载 ML Kit 离线翻译模型……");
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag)
            .setTargetLanguage(target.mlKitTag)
            .build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> status.setText("✅ ML Kit 离线翻译模型已就绪"))
            .addOnFailureListener(e -> status.setText("❌ 模型下载失败：" + safe(e)))
            .addOnCompleteListener(t -> {
                button.setEnabled(true);
                translator.close();
            });
    }

    private void testTranslationEngine(Button button) {
        saveSettings();
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("请选择不同语言");
            return;
        }
        String engine = preferences.getString("engine_id", TranslationRouter.AUTO);
        OfflineFirstTranslationRouter router = new OfflineFirstTranslationRouter(
            this, source.mlKitTag, target.mlKitTag, engine);
        String sample = sampleFor(source.mlKitTag);
        button.setEnabled(false);
        status.setText("测试中：" + engineLabel(engine));
        router.translate(sample, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                status.setText("✅ " + engineName + "\n原文：" + sample + "\n译文：" + translated);
                preferences.edit()
                    .putString("last_original", sample)
                    .putString("last_translation", translated)
                    .apply();
                updateHistory();
                button.setEnabled(true);
                router.close();
            }

            @Override public void onError(String message) {
                status.setText("❌ 翻译引擎测试失败：" + message);
                button.setEnabled(true);
                router.close();
            }
        });
    }

    private String sampleFor(String language) {
        switch (language) {
            case "zh": return "你好，这是离线翻译测试。";
            case "ja": return "こんにちは、今日はいい天気ですね。";
            case "vi": return "Xin chào, hôm nay thời tiết rất đẹp.";
            case "tl": return "Kumusta, maganda ang panahon ngayon.";
            case "ms": return "Helo, cuaca hari ini sangat baik.";
            case "ko": return "안녕하세요. 오늘 날씨가 좋네요.";
            default: return "Hello, it is nice to meet you.";
        }
    }

    private void startCapture() {
        saveSettings();
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            openOverlaySettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            toast("请允许录音权限");
            return;
        }

        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("请选择不同语言");
            return;
        }

        boolean microphoneMode = inputModeSpinner.getSelectedItemPosition() == 1;
        String asr = selectedAsrMode();
        if (TranslationService.ASR_SYSTEM.equals(asr) && !microphoneMode) {
            toast("系统 SpeechRecognizer 只能用于麦克风，请改声音来源或改用 Vosk");
            return;
        }

        boolean needsProjection = !microphoneMode || enableOcr.isChecked();
        if (!needsProjection) {
            startTranslationService(0, null);
            status.setText("正在准备固定麦克风来源 + " + asrLabel(asr));
            return;
        }

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            captureIntent = manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        status.setText("请允许共享整个屏幕；系统内部声音捕获需要该权限。");
        startActivityForResult(captureIntent, REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("你取消了屏幕/声音捕获授权");
            return;
        }
        startTranslationService(resultCode, data);
        status.setText("实时翻译正在准备；声音来源不会自动改变。");
    }

    private void startTranslationService(int resultCode, Intent resultData) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        String engine = preferences.getString("engine_id", TranslationRouter.AUTO);
        boolean youdaoSpeech = preferences.getBoolean("youdao_speech_fallback", true);
        boolean microphone = inputModeSpinner.getSelectedItemPosition() == 1;
        String asr = selectedAsrMode();

        Intent service = new Intent(this, TranslationService.class)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_INPUT_MODE,
                microphone ? TranslationService.INPUT_MICROPHONE : TranslationService.INPUT_PLAYBACK)
            .putExtra(TranslationService.EXTRA_ASR_MODE, asr)
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_ENGINE_ID, engine)
            .putExtra(TranslationService.EXTRA_YOUDAO_SPEECH_FALLBACK, youdaoSpeech)
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, enableOcr.isChecked())
            .putExtra(TranslationService.EXTRA_AUTO_MIC_FALLBACK, false)
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());
        if (resultData != null) service.putExtra(TranslationService.EXTRA_RESULT_DATA, resultData);
        startForegroundService(service);
    }

    private String asrLabel(String asr) {
        if (TranslationService.ASR_VOSK.equals(asr)) return "Vosk 离线 ASR";
        if (TranslationService.ASR_SYSTEM.equals(asr)) return "系统 SpeechRecognizer";
        if (TranslationService.ASR_YOUDAO.equals(asr)) return "有道云 ASR";
        return "自动 ASR";
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            }, REQUEST_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
        }
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
    }

    @Override protected void onResume() {
        super.onResume();
        updateEngineStatus();
        if (history != null) updateHistory();
    }

    @Override protected void onPause() {
        saveSettings();
        super.onPause();
    }

    private void updateEngineStatus() {
        if (engineStatus == null) return;
        String engine = preferences.getString("engine_id", TranslationRouter.AUTO);
        engineStatus.setText("当前翻译：" + engineLabel(engine));
    }

    private String engineLabel(String engine) {
        if (TranslationRouter.AUTO.equals(engine)) return "自动（ML Kit 离线优先，在线只兜底）";
        return TranslationRouter.engineLabel(engine);
    }

    private void saveSettings() {
        if (sourceSpinner == null) return;
        preferences.edit()
            .putInt("source_index", sourceSpinner.getSelectedItemPosition())
            .putInt("target_index", targetSpinner.getSelectedItemPosition())
            .putInt("input_mode", inputModeSpinner.getSelectedItemPosition())
            .putString("asr_mode", selectedAsrMode())
            .putBoolean("show_original", showOriginal.isChecked())
            .putBoolean("prefer_offline", preferOffline.isChecked())
            .putBoolean("enable_ocr", enableOcr.isChecked())
            .putBoolean("auto_mic_fallback", false)
            .putInt("font_size", fontSize.getProgress())
            .apply();
    }

    private void updateHistory() {
        if (history == null) return;
        String original = preferences.getString("last_original", "");
        String translated = preferences.getString("last_translation", "");
        history.setText(translated.isEmpty()
            ? "最近译文：暂无"
            : "最近原文：" + original + "\n最近译文：" + translated);
    }

    private void copyLastTranslation() {
        String translated = preferences.getString("last_translation", "");
        if (translated.isEmpty()) {
            toast("还没有可复制的译文");
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("浮译译文", translated));
        toast("已复制");
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.WHITE);
        c.setChecked(checked);
        return c;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        return layout;
    }

    private TextView label(String value) {
        TextView t = text(value, 14, Color.rgb(201, 190, 221));
        t.setPadding(0, dp(5), 0, dp(2));
        return t;
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
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
