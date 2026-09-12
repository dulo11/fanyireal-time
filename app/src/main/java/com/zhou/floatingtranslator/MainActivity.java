package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    private Spinner languageModeSpinner;
    private CheckBox showOriginal;
    private CheckBox showDiagnostics;
    private CheckBox preferOffline;
    private CheckBox enableOcr;
    private SeekBar fontSize;
    private TextView engineStatus;
    private TextView status;
    private TextView history;
    private UpdateChecker updateChecker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!preferences.getBoolean("v051_migrated", false)) {
            preferences.edit()
                .putBoolean("v051_migrated", true)
                .putBoolean("prefer_offline", preferences.getBoolean("prefer_offline", true))
                .putBoolean("enable_ocr", preferences.getBoolean("enable_ocr", false))
                .putBoolean("auto_mic_fallback", false)
                .putBoolean("show_diagnostics", preferences.getBoolean("show_diagnostics", true))
                .putString("language_mode", preferences.getString("language_mode", SherpaSpeechEngine.LANG_SINGLE))
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

        TextView title = text("浮译 " + BuildConfig.VERSION_NAME, 34, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("固定声音来源 · 高精度离线 ASR · ROOT 通话/VoIP · 历史/SRT", 15,
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

        Button engineSettings = secondaryButton("⚙ 翻译引擎 / API 安全中心");
        engineSettings.setOnClickListener(v -> startActivity(new Intent(this, ApiSettingsActivity.class)));
        card.addView(engineSettings, matchWrap());

        Button modelManager = secondaryButton("📦 离线模型中心 / 断点下载 / 删除 / 默认");
        modelManager.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        card.addView(modelManager, matchWrap());

        Button historyButton = secondaryButton("📝 翻译历史 / 导出 TXT / SRT");
        historyButton.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        card.addView(historyButton, matchWrap());

        Button rootCall = secondaryButton("☎ ROOT 通话 / VoIP 兼容中心");
        rootCall.setOnClickListener(v -> startActivity(new Intent(this, RootCallActivity.class)));
        card.addView(rootCall, matchWrap());

        Button update = secondaryButton("⬆ 检查 APP 更新");
        update.setOnClickListener(v -> checkUpdate(update));
        card.addView(update, matchWrap());

        card.addView(label("固定声音来源（选中后一直使用，绝不自动切换）"));
        inputModeSpinner = new Spinner(this);
        inputModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "系统内部声音｜直播/视频｜MediaProjection",
                "麦克风识别手机外放｜通话兼容备用",
                "ROOT 内部通话/VoIP｜使用已保存 App + ALSA 配置"
            }));
        inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));
        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(inputModeSpinner, matchWrap());

        card.addView(label("语音识别 ASR（可固定选择）"));
        asrModeSpinner = new Spinner(this);
        asrModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "自动推荐｜已下载高精度模型 → Vosk → 系统/有道",
                "Vosk｜省电★★★★★｜速度快｜模型小｜日语快语速一般",
                "SenseVoice INT8｜中英日韩粤｜速度快｜日英混合★★★★｜均衡推荐",
                "ReazonSpeech 日语｜日语直播★★★★★｜日语专项｜不推荐日英混说",
                "NVIDIA Parakeet 日语 0.6B INT8｜快语速/长句★★★★★｜较吃性能",
                "Whisper Small INT8｜多语言｜日英混合★★★★★｜准确率高｜耗电中等",
                "Whisper Medium INT8｜多语言高精度｜日英混合★★★★★｜耗电/内存高",
                "Qwen3-ASR 0.6B INT8｜多语言高精度｜日英混合★★★★★｜模型约 1GB",
                "Omnilingual ASR 300M INT8｜1600+语言｜小语种优先｜覆盖最广",
                "Android 系统 SpeechRecognizer｜只适合麦克风来源",
                "有道云语音 ASR｜联网备用｜需 AppKey/AppSecret"
            }));
        asrModeSpinner.setSelection(asrIndex(preferences.getString("asr_mode", TranslationService.ASR_AUTO)));
        asrModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(asrModeSpinner, matchWrap());

        card.addView(label("识别语言模式（同一句日语+英语请选择混合）"));
        languageModeSpinner = new Spinner(this);
        languageModeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "单语言｜按下面“原语言”识别",
                "日语 + 英语（混合）★ 日本直播推荐",
                "中文 + 英语（混合）",
                "韩语 + 英语（混合）",
                "自动多语言｜Qwen3 / Whisper / SenseVoice 推荐"
            }));
        languageModeSpinner.setSelection(languageModeIndex(preferences.getString(
            "language_mode", SherpaSpeechEngine.LANG_SINGLE)));
        languageModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        card.addView(languageModeSpinner, matchWrap());

        TextView asrInfo = text(
            "纯日语：ReazonSpeech / Parakeet → SenseVoice → Vosk。\n" +
            "日英混合：Qwen3-ASR / Whisper → SenseVoice。\n" +
            "ROOT 通话来源同样送入这些 ASR；必须先在 ROOT 通话兼容中心找到有声音的 PCM。",
            13, Color.rgb(184, 174, 207));
        asrInfo.setPadding(0, dp(4), 0, dp(8));
        card.addView(asrInfo);

        card.addView(label("原语言（混合模式下代表主要语言）"));
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
            saveSettings(true);
        });
        card.addView(swap, matchWrap());

        showOriginal = check("显示原文（关闭后只显示译文）",
            preferences.getBoolean("show_original", true));
        card.addView(showOriginal);

        showDiagnostics = check("显示悬浮窗诊断信息（声音 / ASR / 翻译 / OCR）",
            preferences.getBoolean("show_diagnostics", true));
        card.addView(showDiagnostics);

        enableOcr = check("开启屏幕 OCR（直播语音/ROOT 通话建议关闭）",
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

        Button save = secondaryButton("💾 保存当前全部设置");
        save.setOnClickListener(v -> saveSettings(true));
        card.addView(save, matchWrap());

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
            "0.5.3：ROOT 通话/VoIP 可作为第三种固定声音来源；支持按 App 保存 ALSA/tinycap PCM 配置。",
            14, Color.rgb(201, 190, 221));
        status.setPadding(0, dp(12), 0, 0);
        card.addView(status);

        history = text("", 14, Color.rgb(218, 209, 231));
        history.setPadding(0, dp(12), 0, 0);
        card.addView(history);
        updateHistory();

        TextView note = text(
            "ROOT 通话：先到“ROOT 通话 / VoIP 兼容中心”，让目标 App 保持通话，测试并保存一个有明显音频电平的 PCM；" +
            "再回来选择 ROOT 来源开始翻译。声音来源仍然不会自动切换。",
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
        if (TranslationService.ASR_SENSEVOICE.equals(mode)) return 2;
        if (TranslationService.ASR_REAZON.equals(mode)) return 3;
        if (TranslationService.ASR_PARAKEET.equals(mode)) return 4;
        if (TranslationService.ASR_WHISPER_SMALL.equals(mode)) return 5;
        if (TranslationService.ASR_WHISPER_MEDIUM.equals(mode)) return 6;
        if (TranslationService.ASR_QWEN3.equals(mode)) return 7;
        if (TranslationService.ASR_OMNILINGUAL.equals(mode)) return 8;
        if (TranslationService.ASR_SYSTEM.equals(mode)) return 9;
        if (TranslationService.ASR_YOUDAO.equals(mode)) return 10;
        return 0;
    }

    private String selectedAsrMode() {
        int pos = asrModeSpinner == null ? 0 : asrModeSpinner.getSelectedItemPosition();
        switch (pos) {
            case 1: return TranslationService.ASR_VOSK;
            case 2: return TranslationService.ASR_SENSEVOICE;
            case 3: return TranslationService.ASR_REAZON;
            case 4: return TranslationService.ASR_PARAKEET;
            case 5: return TranslationService.ASR_WHISPER_SMALL;
            case 6: return TranslationService.ASR_WHISPER_MEDIUM;
            case 7: return TranslationService.ASR_QWEN3;
            case 8: return TranslationService.ASR_OMNILINGUAL;
            case 9: return TranslationService.ASR_SYSTEM;
            case 10: return TranslationService.ASR_YOUDAO;
            default: return TranslationService.ASR_AUTO;
        }
    }

    private int languageModeIndex(String mode) {
        if (SherpaSpeechEngine.LANG_JA_EN.equals(mode)) return 1;
        if (SherpaSpeechEngine.LANG_ZH_EN.equals(mode)) return 2;
        if (SherpaSpeechEngine.LANG_KO_EN.equals(mode)) return 3;
        if (SherpaSpeechEngine.LANG_AUTO.equals(mode)) return 4;
        return 0;
    }

    private String selectedLanguageMode() {
        int pos = languageModeSpinner == null ? 0 : languageModeSpinner.getSelectedItemPosition();
        switch (pos) {
            case 1: return SherpaSpeechEngine.LANG_JA_EN;
            case 2: return SherpaSpeechEngine.LANG_ZH_EN;
            case 3: return SherpaSpeechEngine.LANG_KO_EN;
            case 4: return SherpaSpeechEngine.LANG_AUTO;
            default: return SherpaSpeechEngine.LANG_SINGLE;
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
            .addOnCompleteListener(t -> { button.setEnabled(true); translator.close(); });
    }

    private void testTranslationEngine(Button button) {
        saveSettings(false);
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
                preferences.edit().putString("last_original", sample)
                    .putString("last_translation", translated).apply();
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
            case "ja": return "今日は online meeting があるので、three o'clock に来てください。";
            case "vi": return "Xin chào, hôm nay thời tiết rất đẹp.";
            case "tl": return "Kumusta, maganda ang panahon ngayon.";
            case "ms": return "Helo, cuaca hari ini sangat baik.";
            case "ko": return "안녕하세요. 오늘 날씨가 좋네요.";
            default: return "Hello, it is nice to meet you.";
        }
    }

    private void checkUpdate(Button button) {
        if (updateChecker != null) updateChecker.close();
        updateChecker = new UpdateChecker(this);
        button.setEnabled(false);
        status.setText("正在检查 GitHub Releases 最新版本……");
        updateChecker.check(BuildConfig.VERSION_NAME, new UpdateChecker.Callback() {
            @Override public void onResult(String latestVersion, String pageUrl, String apkUrl, boolean newer) {
                button.setEnabled(true);
                if (newer) {
                    status.setText("发现新版本 v" + latestVersion);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("发现浮译 v" + latestVersion)
                        .setMessage("当前版本：v" + BuildConfig.VERSION_NAME + "\n可以打开固定 latest APK 下载链接更新。")
                        .setNegativeButton("稍后", null)
                        .setNeutralButton("版本页面", (d, w) -> openUrl(pageUrl))
                        .setPositiveButton("下载最新版", (d, w) -> openUrl(apkUrl))
                        .show();
                } else {
                    status.setText("✅ 当前已是最新版本 v" + BuildConfig.VERSION_NAME);
                    toast("已经是最新版");
                }
            }

            @Override public void onError(String message) {
                button.setEnabled(true);
                status.setText("检查更新失败：" + message + "\n如果当前网络无法访问 GitHub，可开代理后重试。");
            }
        });
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("无法打开链接：" + safe(e)); }
    }

    private void startCapture() {
        saveSettings(false);
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

        int sourceMode = inputModeSpinner.getSelectedItemPosition();
        boolean microphoneMode = sourceMode == 1;
        boolean rootMode = sourceMode == 2;
        String asr = selectedAsrMode();
        if (TranslationService.ASR_SYSTEM.equals(asr) && !microphoneMode) {
            toast("系统 SpeechRecognizer 只能用于麦克风来源；ROOT/内部声音请选择本地 ASR 或有道云 ASR");
            return;
        }
        if (rootMode && !RootCallProfileStore.hasSelected(this)) {
            toast("还没有 ROOT 通话配置，先进入 ROOT 通话 / VoIP 兼容中心扫描并保存");
            startActivity(new Intent(this, RootCallActivity.class));
            return;
        }

        String optionalModelId = TranslationService.modelIdForAsr(asr);
        if (optionalModelId != null) {
            OfflineModelStore store = new OfflineModelStore(this);
            boolean installed = store.isInstalled(optionalModelId);
            store.close();
            if (!installed) {
                toast("这个高精度模型还没下载，已打开离线模型中心");
                startActivity(new Intent(this, ModelManagerActivity.class));
                return;
            }
        }

        boolean needsProjection = ((!microphoneMode && !rootMode) || enableOcr.isChecked());
        if (!needsProjection) {
            startTranslationService(0, null);
            status.setText(rootMode
                ? "正在准备固定 ROOT 通话来源 + " + asrLabel(asr)
                : "正在准备固定麦克风来源 + " + asrLabel(asr));
            return;
        }

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            captureIntent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        status.setText(rootMode
            ? "ROOT 声音已经固定；因为你同时开了 OCR，请允许共享整个屏幕。"
            : "请允许共享整个屏幕；系统内部声音捕获需要该权限。");
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
        int sourceMode = inputModeSpinner.getSelectedItemPosition();
        String input = sourceMode == 1 ? TranslationService.INPUT_MICROPHONE
            : sourceMode == 2 ? TranslationService.INPUT_ROOT : TranslationService.INPUT_PLAYBACK;
        String asr = selectedAsrMode();

        Intent service = new Intent(this, TranslationService.class)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_INPUT_MODE, input)
            .putExtra(TranslationService.EXTRA_ASR_MODE, asr)
            .putExtra(TranslationService.EXTRA_LANGUAGE_MODE, selectedLanguageMode())
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_ENGINE_ID, engine)
            .putExtra(TranslationService.EXTRA_YOUDAO_SPEECH_FALLBACK, youdaoSpeech)
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_SHOW_DIAGNOSTICS, showDiagnostics.isChecked())
            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, enableOcr.isChecked())
            .putExtra(TranslationService.EXTRA_AUTO_MIC_FALLBACK, false)
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());
        if (resultData != null) service.putExtra(TranslationService.EXTRA_RESULT_DATA, resultData);
        startForegroundService(service);
    }

    private String asrLabel(String asr) {
        if (TranslationService.ASR_VOSK.equals(asr)) return "Vosk";
        if (TranslationService.ASR_SENSEVOICE.equals(asr)) return "SenseVoice";
        if (TranslationService.ASR_REAZON.equals(asr)) return "ReazonSpeech";
        if (TranslationService.ASR_PARAKEET.equals(asr)) return "Parakeet 日语";
        if (TranslationService.ASR_WHISPER_SMALL.equals(asr)) return "Whisper Small";
        if (TranslationService.ASR_WHISPER_MEDIUM.equals(asr)) return "Whisper Medium";
        if (TranslationService.ASR_QWEN3.equals(asr)) return "Qwen3-ASR";
        if (TranslationService.ASR_OMNILINGUAL.equals(asr)) return "Omnilingual ASR";
        if (TranslationService.ASR_SYSTEM.equals(asr)) return "系统 SpeechRecognizer";
        if (TranslationService.ASR_YOUDAO.equals(asr)) return "有道云 ASR";
        return "自动推荐 ASR";
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_PERMISSIONS);
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
        if (inputModeSpinner != null) inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));
        try { startService(new Intent(this, TranslationService.class).setAction(TranslationService.ACTION_UI_VISIBLE)); }
        catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        saveSettings(false);
        try { startService(new Intent(this, TranslationService.class).setAction(TranslationService.ACTION_UI_HIDDEN)); }
        catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (updateChecker != null) updateChecker.close();
        super.onDestroy();
    }

    private void updateEngineStatus() {
        if (engineStatus == null) return;
        String engine = preferences.getString("engine_id", TranslationRouter.AUTO);
        String root = RootCallProfileStore.hasSelected(this)
            ? "\nROOT 配置：" + RootCallProfileStore.selectedPackage(this) : "";
        engineStatus.setText("当前翻译：" + engineLabel(engine) + "\n默认 ASR："
            + asrLabel(preferences.getString("asr_mode", TranslationService.ASR_AUTO))
            + "\n历史：" + HistoryStore.count(this) + " 条" + root);
    }

    private String engineLabel(String engine) {
        if (TranslationRouter.AUTO.equals(engine)) return "自动（ML Kit 离线优先，在线只兜底）";
        return TranslationRouter.engineLabel(engine);
    }

    private void saveSettings(boolean notify) {
        if (sourceSpinner == null) return;
        preferences.edit()
            .putInt("source_index", sourceSpinner.getSelectedItemPosition())
            .putInt("target_index", targetSpinner.getSelectedItemPosition())
            .putInt("input_mode", inputModeSpinner.getSelectedItemPosition())
            .putString("asr_mode", selectedAsrMode())
            .putString("language_mode", selectedLanguageMode())
            .putBoolean("show_original", showOriginal.isChecked())
            .putBoolean("show_diagnostics", showDiagnostics.isChecked())
            .putBoolean("prefer_offline", preferOffline.isChecked())
            .putBoolean("enable_ocr", enableOcr.isChecked())
            .putBoolean("auto_mic_fallback", false)
            .putInt("font_size", fontSize.getProgress())
            .apply();
        updateEngineStatus();
        if (notify && status != null) {
            status.setText("✅ 当前声音来源、ASR、语言模式、显示选项和字幕设置已保存");
            toast("设置已保存");
        }
    }

    private void updateHistory() {
        if (history == null) return;
        String original = preferences.getString("last_original", "");
        String translated = preferences.getString("last_translation", "");
        history.setText(translated.isEmpty() ? "最近译文：暂无"
            : "最近原文：" + original + "\n最近译文：" + translated);
    }

    private void copyLastTranslation() {
        String translated = preferences.getString("last_translation", "");
        if (translated.isEmpty()) { toast("还没有可复制的译文"); return; }
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
