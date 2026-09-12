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

    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private Spinner inputModeSpinner;
    private CheckBox showOriginal;
    private CheckBox preferOffline;
    private CheckBox enableOcr;
    private CheckBox autoMicFallback;
    private SeekBar fontSize;
    private TextView status;
    private TextView history;
    private SharedPreferences preferences;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column();
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = text("浮译 0.3.2", 34, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("系统声音 · 麦克风 · 屏幕 OCR · 实时悬浮翻译", 16,
            Color.rgb(201, 190, 221));
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.panel);
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        card.addView(label("声音来源"));
        inputModeSpinner = new Spinner(this);
        inputModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"系统内部声音（视频/直播/游戏）", "麦克风/免提通话"}));
        inputModeSpinner.setSelection(preferences.getInt("input_mode", 0));
        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(inputModeSpinner, matchWrap());

        card.addView(label("原语言（同时用于语音识别和屏幕 OCR）"));
        sourceSpinner = spinner();
        sourceSpinner.setSelection(preferences.getInt("source_index", 1));
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

        showOriginal = new CheckBox(this);
        showOriginal.setText("同时显示原文");
        showOriginal.setTextColor(Color.WHITE);
        showOriginal.setChecked(preferences.getBoolean("show_original", true));
        card.addView(showOriginal);

        enableOcr = new CheckBox(this);
        enableOcr.setText("开启屏幕 OCR（视频字幕/直播文字/评论）");
        enableOcr.setTextColor(Color.WHITE);
        enableOcr.setChecked(preferences.getBoolean("enable_ocr", true));
        card.addView(enableOcr);

        autoMicFallback = new CheckBox(this);
        autoMicFallback.setText("系统声音失败时自动切到麦克风/扬声器兜底");
        autoMicFallback.setTextColor(Color.WHITE);
        autoMicFallback.setChecked(preferences.getBoolean("auto_mic_fallback", true));
        card.addView(autoMicFallback);

        preferOffline = new CheckBox(this);
        preferOffline.setText("语音识别优先离线（准确度取决于手机语音包）");
        preferOffline.setTextColor(Color.WHITE);
        preferOffline.setChecked(preferences.getBoolean("prefer_offline", false));
        card.addView(preferOffline);

        card.addView(label("字幕大小"));
        fontSize = new SeekBar(this);
        fontSize.setMax(18);
        fontSize.setProgress(preferences.getInt("font_size", 8));
        card.addView(fontSize, matchWrap());

        Button overlay = secondaryButton("① 授予悬浮窗权限");
        overlay.setOnClickListener(v -> openOverlaySettings());
        card.addView(overlay, matchWrap());

        Button model = secondaryButton("② 下载当前翻译语言模型");
        model.setOnClickListener(v -> downloadCurrentModel(model));
        card.addView(model, matchWrap());

        Button engineTest = secondaryButton("③ 翻译引擎自检（不读取声音/屏幕）");
        engineTest.setOnClickListener(v -> testTranslationEngine(engineTest));
        card.addView(engineTest, matchWrap());

        Button start = primaryButton("④ 开始实时翻译");
        start.setOnClickListener(v -> startCapture());
        card.addView(start, matchWrap());

        Button stop = secondaryButton("停止翻译");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, TranslationService.class)
                .setAction(TranslationService.ACTION_STOP);
            startService(intent);
            status.setText("已停止");
        });
        card.addView(stop, matchWrap());

        Button copy = secondaryButton("复制最近译文");
        copy.setOnClickListener(v -> copyLastTranslation());
        card.addView(copy, matchWrap());

        Button clear = secondaryButton("清空最近记录");
        clear.setOnClickListener(v -> {
            preferences.edit().remove("last_original").remove("last_translation").apply();
            updateHistory();
        });
        card.addView(clear, matchWrap());

        status = text("建议先点“翻译引擎自检”。自检成功后再开始实时翻译。", 14,
            Color.rgb(201, 190, 221));
        status.setPadding(0, dp(14), 0, 0);
        card.addView(status);

        history = text("", 14, Color.rgb(218, 209, 231));
        history.setPadding(0, dp(14), 0, 0);
        card.addView(history);
        updateHistory();

        TextView note = text(
            "0.3.2 重点修复：\n" +
            "• Android 11+ 增加系统语音识别服务发现配置。\n" +
            "• Android 14+ 强制申请“整个屏幕”捕获，避免误选单个 App 后切换应用看不到内容。\n" +
            "• 系统声音改用系统默认 SpeechRecognizer，提高 ColorOS/OxygenOS 兼容性。\n" +
            "• 有声音但 10 秒一直识别不出文字时，也会自动切换麦克风兜底。\n" +
            "• 悬浮窗增加实时诊断：翻译、声音、语音识别、屏幕 OCR 四个环节分别显示状态。\n\n" +
            "屏幕 OCR 模型已经打包在 APK 内；文字翻译仍由 ML Kit 设备端模型完成。" +
            "受 DRM 保护的画面或主动禁止音频捕获的 App 仍可能无法直接读取，此时可使用麦克风兜底。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(dp(4), dp(22), dp(4), 0);
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

    private void downloadCurrentModel(Button button) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("原语言和目标语言不能相同");
            return;
        }

        button.setEnabled(false);
        status.setText("正在下载翻译模型，请保持网络连接……");
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag)
            .setTargetLanguage(target.mlKitTag)
            .build());

        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> {
                status.setText("翻译模型已就绪，之后可以离线翻译");
                button.setEnabled(true);
                translator.close();
            })
            .addOnFailureListener(e -> {
                status.setText("模型下载失败：" + safeMessage(e));
                button.setEnabled(true);
                translator.close();
            });
    }

    private void testTranslationEngine(Button button) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("自检前请选择不同的原语言和目标语言");
            return;
        }

        button.setEnabled(false);
        status.setText("自检中：正在加载 ML Kit " + source.mlKitTag + " → " + target.mlKitTag + "……");
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag)
            .setTargetLanguage(target.mlKitTag)
            .build());

        String sample = sampleFor(source.mlKitTag);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .continueWithTask(task -> {
                if (!task.isSuccessful()) throw task.getException();
                return translator.translate(sample);
            })
            .addOnSuccessListener(value -> {
                status.setText("✅ 翻译引擎自检成功\n测试原文：" + sample + "\n测试译文：" + value);
                preferences.edit()
                    .putString("last_original", sample)
                    .putString("last_translation", value)
                    .apply();
                updateHistory();
            })
            .addOnFailureListener(e -> status.setText("❌ 翻译引擎自检失败：" + safeMessage(e)))
            .addOnCompleteListener(task -> {
                button.setEnabled(true);
                translator.close();
            });
    }

    private String sampleFor(String language) {
        switch (language) {
            case "zh": return "你好，这是翻译测试。";
            case "ja": return "こんにちは、これは翻訳テストです。";
            case "vi": return "Xin chào, đây là bài kiểm tra dịch.";
            case "tl": return "Kumusta, ito ay pagsubok sa pagsasalin.";
            case "ms": return "Helo, ini ialah ujian terjemahan.";
            case "ko": return "안녕하세요. 번역 테스트입니다.";
            case "fr": return "Bonjour, ceci est un test de traduction.";
            case "de": return "Hallo, dies ist ein Übersetzungstest.";
            case "es": return "Hola, esta es una prueba de traducción.";
            case "ru": return "Здравствуйте, это тест перевода.";
            default: return "Hello, this is a translation test.";
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
            toast("请允许录音权限后再开始");
            return;
        }

        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("请选择不同的原语言和目标语言");
            return;
        }

        boolean microphoneMode = inputModeSpinner.getSelectedItemPosition() == 1;
        boolean needsScreenProjection = !microphoneMode || enableOcr.isChecked();
        if (!needsScreenProjection) {
            startTranslationService(0, null);
            status.setText("麦克风翻译已启动；说话后看悬浮窗诊断信息");
            toast("麦克风翻译正在运行");
            return;
        }

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Cross-app OCR needs the whole display. Restrict the consent dialog to default-display capture.
            MediaProjectionConfig config = MediaProjectionConfig.createConfigForDefaultDisplay();
            captureIntent = manager.createScreenCaptureIntent(config);
            status.setText("请允许共享整个屏幕。0.3.2 已关闭“只共享单个 App”模式。 ");
        } else {
            captureIntent = manager.createScreenCaptureIntent();
            status.setText("请允许屏幕捕获，用于 OCR/系统声音翻译。 ");
        }
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
        status.setText("实时翻译已启动。切换到视频/直播后查看悬浮窗里的四项诊断。 ");
        toast("浮译正在后台运行");
    }

    private void startTranslationService(int resultCode, Intent resultData) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();

        Intent service = new Intent(this, TranslationService.class)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_INPUT_MODE,
                inputModeSpinner.getSelectedItemPosition() == 1
                    ? TranslationService.INPUT_MICROPHONE
                    : TranslationService.INPUT_PLAYBACK)
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, enableOcr.isChecked())
            .putExtra(TranslationService.EXTRA_AUTO_MIC_FALLBACK, autoMicFallback.isChecked())
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());

        if (resultData != null) service.putExtra(TranslationService.EXTRA_RESULT_DATA, resultData);
        startForegroundService(service);
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
        if (history != null) updateHistory();
    }

    @Override protected void onPause() {
        saveSettings();
        super.onPause();
    }

    private void saveSettings() {
        if (preferences == null || sourceSpinner == null || targetSpinner == null) return;
        preferences.edit()
            .putInt("source_index", sourceSpinner.getSelectedItemPosition())
            .putInt("target_index", targetSpinner.getSelectedItemPosition())
            .putInt("input_mode", inputModeSpinner.getSelectedItemPosition())
            .putBoolean("show_original", showOriginal.isChecked())
            .putBoolean("prefer_offline", preferOffline.isChecked())
            .putBoolean("enable_ocr", enableOcr.isChecked())
            .putBoolean("auto_mic_fallback", autoMicFallback.isChecked())
            .putInt("font_size", fontSize.getProgress())
            .apply();
    }

    private void updateHistory() {
        if (history == null) return;
        String original = preferences.getString("last_original", "");
        String translated = preferences.getString("last_translation", "");
        if (translated.isEmpty()) {
            history.setText("最近译文：暂无");
        } else {
            history.setText("最近原文：" + original + "\n最近译文：" + translated);
        }
    }

    private void copyLastTranslation() {
        String translated = preferences.getString("last_translation", "");
        if (translated.isEmpty()) {
            toast("还没有可复制的译文");
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("浮译译文", translated));
        toast("已复制最近译文");
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

    private String safeMessage(Exception e) {
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
