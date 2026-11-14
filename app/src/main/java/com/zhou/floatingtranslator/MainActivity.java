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
import android.widget.AdapterView;
import java.util.ArrayList;
import java.util.List;
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

/** Detailed translation console. HomeActivity is the compact launcher. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_CAPTURE = 11;
    private static final String PREFS = "floating_translator";

    private SharedPreferences preferences;
    private Spinner inputModeSpinner;
    private Spinner micProcessingSpinner;
    private Spinner languageModeSpinner;
    private Button customLanguageButton;
    private List<String> chosenCustomLanguages = new ArrayList<>();
    private Spinner sourceSpinner;
    private TextView singleSourceLabel;
    private Button swapLanguageButton;
    private Spinner targetSpinner;
    private CheckBox showOriginal;
    private CheckBox showDiagnostics;
    private CheckBox translationOnlyOverlay;
    private CheckBox smartSentenceTranslation;
    private CheckBox preferOffline;
    private CheckBox enableOcr;
    private SeekBar fontSize;
    private TextView currentAsr;
    private TextView summary;
    private TextView status;
    private TextView recent;
    private UpdateChecker updateChecker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        chosenCustomLanguages = SelectedSpeechLanguages.forLegacy(
            preferences.getString("language_mode", SherpaSpeechEngine.LANG_SINGLE),
            preferences.getString(SelectedSpeechLanguages.PREF_CUSTOM_CODES, ""));
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column();
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        scroll.addView(root);

        TextView title = text("实时翻译控制台", 30, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = text("浮译 v" + BuildConfig.VERSION_NAME + " · 声音来源固定，不会自动乱切", 13,
            Color.rgb(190, 165, 255));
        sub.setPadding(0, dp(3), 0, dp(14));
        root.addView(sub);

        LinearLayout quick = card(root);
        quick.addView(section("当前配置"));
        summary = text("", 13, Color.rgb(220, 212, 235));
        summary.setPadding(0, dp(5), 0, dp(7));
        quick.addView(summary);
        Button rootLab = secondaryButton("☎ 通话内部声音兼容中心｜ROOT / Shizuku");
        rootLab.setOnClickListener(v -> startActivity(new Intent(this, RootCallActivity.class)));
        quick.addView(rootLab, params());
        Button mediaLab = secondaryButton("🎧 ROOT 直播/视频音频｜无屏幕共享授权（实验）");
        mediaLab.setOnClickListener(v -> openRootAudioSettings(true));
        quick.addView(mediaLab, params());
        Button models = secondaryButton("📦 离线模型中心");
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        quick.addView(models, params());

        LinearLayout audioCard = card(root);
        audioCard.addView(section("声音与识别"));
        audioCard.addView(label("固定声音来源"));
        inputModeSpinner = new Spinner(this);
        inputModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "系统内部声音｜仅音频（仍需系统共享授权）",
                "麦克风｜通话外放兼容",
                "ROOT / Shizuku 内部通话/VoIP｜按 App 保存 PCM",
                "ROOT 直播/视频内部声音｜无屏幕授权（需先实测 PCM）"
            }));
        inputModeSpinner.setSelection(Math.min(3, preferences.getInt("input_mode", 0)));
        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(inputModeSpinner, params());
        audioCard.addView(label(
            "直播/视频：普通系统内部声音模式只抓取音频；OCR 关闭时不读取屏幕画面，但 Android 仍会显示屏幕共享授权。"
            + " ROOT 直播模式不申请 MediaProjection，但仅限通过 tinycap 实机测试可读取声音的设备，"
            + " 不保证当前 PCM 属于指定 App。请用播放/暂停对照验证。"
        ));

        audioCard.addView(label("麦克风通话处理｜仅麦克风 + 本地/有道 PCM ASR"));
        micProcessingSpinner = new Spinner(this);
        micProcessingSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "远端外放优先｜NS + AGC，AEC关闭（推荐）",
                "近端说话优先｜AEC + NS + AGC",
                "原始麦克风｜AEC/NS/AGC 全关闭"
            }));
        micProcessingSpinner.setSelection(micProcessingIndex(preferences.getString(
            "mic_processing", MicAudioEffects.MODE_REMOTE)));
        micProcessingSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(micProcessingSpinner, params());
        TextView micTip = text(
            "语音识别引擎由设置页手动指定。安卓内置识别麦克风可直接使用；系统内部声音需要厂商支持外部 PCM。失败不会自动切换其他识别引擎。",
            12, Color.rgb(184, 174, 207));
        audioCard.addView(micTip);

        audioCard.addView(label("ASR 识别引擎｜仅在设置中切换"));
        currentAsr = text(
            "当前：" + asrLabel(selectedAsrMode()) + "（固定，不自动回退）",
            14, Color.rgb(220, 212, 235));
        audioCard.addView(currentAsr, params());
        Button asrSettings = secondaryButton("⚙ 设置 / 手动选择语音识别引擎");
        asrSettings.setOnClickListener(v ->
            startActivity(new Intent(this, AsrEngineSettingsActivity.class)));
        audioCard.addView(asrSettings, params());

        audioCard.addView(label("语音识别语言模式（自主选择）"));
        languageModeSpinner = new Spinner(this);
        languageModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "单语言｜从全部语言中选择一种",
                "自选混合｜选择 2、3 或更多种语言"
            }));
        languageModeSpinner.setSelection(languageModeIndex(preferences.getString(
            "language_mode", SherpaSpeechEngine.LANG_SINGLE)));
        languageModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(languageModeSpinner, params());
        customLanguageButton = secondaryButton("");
        customLanguageButton.setOnClickListener(v -> chooseCustomLanguages());
        audioCard.addView(customLanguageButton, params());
        updateCustomLanguageButton();
        languageModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                updateCustomLanguageButton();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        audioCard.addView(label(
            "单语言：下面的「原语言」下拉列表可选择全部支持的语言。\n"
            + "混合：点击上方按钮，自选英语+日语、英语+马来语+越南语等任意组合；"
            + "只把选定语种送去翻译，无法判断的片段直接提示，不擅自猜其他语种。"
            + "注意 Omnilingual 等多语言 ASR 不支持在模型内部锁定这些语言。"
        ));

        TextView rootTip = text(
            "内部通话模式：在兼容中心明确选择固定 ROOT 或固定 Shizuku，再扫描/测试 PCM 并按 App 保存。" +
            "选安卓内置时才会尝试把 16k PCM 送给系统识别器；不支持则报告错误，不切免费在线/本地。Shizuku 仍受 shell/SELinux 限制。",
            12, Color.rgb(184, 174, 207));
        rootTip.setPadding(0, dp(6), 0, 0);
        audioCard.addView(rootTip);

        LinearLayout languageCard = card(root);
        languageCard.addView(section("翻译语言"));
        singleSourceLabel = label("原语言（单语言模式可选择全部语言）");
        languageCard.addView(singleSourceLabel);
        sourceSpinner = languageSpinner();
        sourceSpinner.setSelection(preferences.getInt("source_index", 2));
        languageCard.addView(sourceSpinner, params());
        languageCard.addView(label("翻译为"));
        targetSpinner = languageSpinner();
        targetSpinner.setSelection(preferences.getInt("target_index", 0));
        languageCard.addView(targetSpinner, params());
        swapLanguageButton = secondaryButton("⇄ 互换语言");
        swapLanguageButton.setOnClickListener(v -> {
            int s = sourceSpinner.getSelectedItemPosition();
            sourceSpinner.setSelection(targetSpinner.getSelectedItemPosition());
            targetSpinner.setSelection(s);
            saveSettings(false);
        });
        languageCard.addView(swapLanguageButton, params());
        updateCustomLanguageButton();

        LinearLayout displayCard = card(root);
        displayCard.addView(section("字幕与显示"));
        showOriginal = check("显示原文", preferences.getBoolean("show_original", true));
        showDiagnostics = check("显示诊断（声音 / ASR / 翻译）",
            preferences.getBoolean("show_diagnostics", true));
        translationOnlyOverlay = check("悬浮窗仅显示译文（隐藏原文、诊断、暂停/关闭按钮）",
            preferences.getBoolean("translation_only_overlay", false));
        smartSentenceTranslation = check("智能拼句修正（不使用 AI；先立即翻译，后续片段可自动合并重译）",
            preferences.getBoolean("smart_sentence_translation", true));
        enableOcr = check("屏幕 OCR（默认关闭；ROOT 内部音频模式忽略）",
            preferences.getBoolean("enable_ocr", false));
        preferOffline = check("手动选择系统 SpeechRecognizer 时强制请求离线（自动模式忽略此项，优先在线质量）",
            preferences.getBoolean("prefer_offline", false));
        displayCard.addView(showOriginal);
        displayCard.addView(showDiagnostics);
        displayCard.addView(translationOnlyOverlay);
        displayCard.addView(smartSentenceTranslation);
        displayCard.addView(enableOcr);
        displayCard.addView(preferOffline);
        displayCard.addView(label("字幕大小"));
        fontSize = new SeekBar(this);
        fontSize.setMax(18);
        fontSize.setProgress(preferences.getInt("font_size", 8));
        displayCard.addView(fontSize, params());

        LinearLayout actions = card(root);
        actions.addView(section("开始前准备"));
        Button save = secondaryButton("💾 保存当前设置");
        save.setOnClickListener(v -> saveSettings(true));
        actions.addView(save, params());
        Button overlay = secondaryButton("① 授予悬浮窗权限");
        overlay.setOnClickListener(v -> openOverlaySettings());
        actions.addView(overlay, params());
        Button model = secondaryButton("② 下载当前 ML Kit 离线翻译模型");
        model.setOnClickListener(v -> downloadCurrentModel(model));
        actions.addView(model, params());
        Button test = secondaryButton("③ 测试翻译引擎");
        test.setOnClickListener(v -> testTranslationEngine(test));
        actions.addView(test, params());
        Button start = primaryButton("④ 开始实时翻译");
        start.setOnClickListener(v -> startCapture());
        actions.addView(start, params());
        Button stop = secondaryButton("停止全部翻译");
        stop.setOnClickListener(v -> stopAllServices());
        actions.addView(stop, params());

        LinearLayout resultCard = card(root);
        resultCard.addView(section("状态 / 最近翻译"));
        status = text("v" + BuildConfig.VERSION_NAME + " · 固定声音来源，完整句依次翻译。", 13,
            Color.rgb(190, 165, 255));
        resultCard.addView(status);
        recent = text("", 13, Color.rgb(218, 209, 231));
        recent.setPadding(0, dp(8), 0, dp(6));
        resultCard.addView(recent);
        Button copy = secondaryButton("复制最近译文");
        copy.setOnClickListener(v -> copyLastTranslation());
        resultCard.addView(copy, params());
        Button history = secondaryButton("📝 历史 / TXT / SRT");
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        resultCard.addView(history, params());
        Button engine = secondaryButton("⚙ 翻译引擎 / API");
        engine.setOnClickListener(v -> startActivity(new Intent(this, ApiSettingsActivity.class)));
        resultCard.addView(engine, params());
        Button update = secondaryButton("⬆ 检查更新");
        update.setOnClickListener(v -> checkUpdate(update));
        resultCard.addView(update, params());

        refreshSummary();
        updateRecent();
        return scroll;
    }

    private Spinner languageSpinner() {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL));
        spinner.setBackgroundColor(Color.rgb(51, 45, 73));
        return spinner;
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
        if (!SelectedSpeechLanguages.MODE_CUSTOM.equals(selectedLanguageMode())
            && source.mlKitTag.equals(target.mlKitTag)) {
            toast("单语言原语言和目标语言不能相同");
            return;
        }

        if (SelectedSpeechLanguages.MODE_CUSTOM.equals(selectedLanguageMode())
            && chosenCustomLanguages.size() < 2) {
            toast("混合模式至少选两种语言");
            chooseCustomLanguages();
            return;
        }
        int mode = inputModeSpinner.getSelectedItemPosition();
        boolean mic = mode == 1;
        boolean root = mode == 2 || mode == 3;
        boolean rootMedia = mode == 3;
        String asr = selectedAsrMode();
        if (SelectedSpeechLanguages.MODE_CUSTOM.equals(selectedLanguageMode())
            && TranslationService.ASR_VOSK.equals(asr)) {
            toast("Vosk 只能同时加载一种语言；自选混合请改选 Whisper 或 Omnilingual 等多语种 ASR");
            return;
        }

        if (root && !(rootMedia
            ? RootCallProfileStore.hasSelectedMedia(this) : RootCallProfileStore.hasSelected(this))) {
            toast(rootMedia ? "请先测试并保存直播内部 PCM 配置" : "请先保存通话 PCM 配置");
            openRootAudioSettings(rootMedia);
            return;
        }
        if ((TranslationService.ASR_FREE_ONLINE.equals(asr)
            || TranslationService.ASR_GROQ_LARGE.equals(asr)
            || TranslationService.ASR_GROQ_TURBO.equals(asr)
            || TranslationService.ASR_CLOUDFLARE.equals(asr))
            && !FreeOnlineSpeechEngine.hasAnyConfigured(this)) {
            toast("还没配置免费在线 ASR，已打开 API 设置");
            startActivity(new Intent(this, ApiSettingsActivity.class));
            return;
        }

        String modelId = TranslationService.modelIdForAsr(asr);
        if (modelId != null) {
            OfflineModelStore store = new OfflineModelStore(this);
            boolean installed = store.isInstalled(modelId);
            store.close();
            if (!installed) {
                toast("这个高精度模型还没下载，已打开离线模型中心");
                startActivity(new Intent(this, ModelManagerActivity.class));
                return;
            }
        }

        if (root) {
            if (enableOcr.isChecked()) toast("ROOT 内部音频模式只采声音，不使用 OCR");
            startRootTranslationService(rootMedia);
            RootCallProfileStore.Profile p = rootMedia ? RootCallProfileStore.loadSelectedMedia(this)
                : RootCallProfileStore.loadSelected(this);
            status.setText("正在启动 " + (p == null ? "内部" : p.sourceLabel())
                + (rootMedia ? " 直播音频翻译；不请求屏幕共享权限。" : " 通话翻译；保持通话即可。"));
            return;
        }

        boolean needsProjection = !mic || enableOcr.isChecked();
        if (!needsProjection) {
            startNormalTranslationService(0, null);
            status.setText("正在准备固定麦克风来源 + " + asrLabel(asr));
            return;
        }

        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent captureIntent = Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
        status.setText(enableOcr.isChecked()
            ? "已开启 OCR：系统需要屏幕共享授权，并且会读取画面中的文字。"
            : "音频专用：只采集直播音频，不抓取屏幕画面。Android 仍要求显示屏幕共享授权。");
        startActivityForResult(captureIntent, REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("你取消了屏幕/声音捕获授权");
            return;
        }
        startNormalTranslationService(resultCode, data);
        status.setText("实时翻译正在准备；声音来源不会自动改变。");
    }

    private Intent baseServiceIntent(Class<?> serviceClass) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        return new Intent(this, serviceClass)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_ASR_MODE, selectedAsrMode())
            .putExtra(TranslationService.EXTRA_LANGUAGE_MODE, selectedLanguageMode())
            .putExtra(SelectedSpeechLanguages.EXTRA_CUSTOM_CODES,
                SelectedSpeechLanguages.serialize(chosenCustomLanguages))
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_ENGINE_ID,
                preferences.getString("engine_id", TranslationRouter.AUTO))
            .putExtra(TranslationService.EXTRA_YOUDAO_SPEECH_FALLBACK,
                preferences.getBoolean("youdao_speech_fallback", false))
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_SHOW_DIAGNOSTICS, showDiagnostics.isChecked())
            .putExtra(TranslationService.EXTRA_TRANSLATION_ONLY, translationOnlyOverlay.isChecked())
            .putExtra(TranslationService.EXTRA_SMART_SENTENCE, smartSentenceTranslation.isChecked())
            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())
            .putExtra(TranslationService.EXTRA_MIC_PROCESSING, selectedMicProcessing())
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());
    }

    private void openRootAudioSettings(boolean media) {
        startActivity(new Intent(this, RootCallActivity.class)
            .putExtra(RootCallActivity.EXTRA_MEDIA_MODE, media));
    }

    private void startRootTranslationService(boolean media) {
        stopService(new Intent(this, TranslationService.class));
        Intent service = baseServiceIntent(RootCallTranslationService.class)
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, false)
            .putExtra(TranslationService.EXTRA_INPUT_MODE, media ? "root_media" : "root");
        startForegroundService(service);
    }

    private void startNormalTranslationService(int resultCode, Intent resultData) {
        stopService(new Intent(this, RootCallTranslationService.class));
        boolean mic = inputModeSpinner.getSelectedItemPosition() == 1;
        Intent service = baseServiceIntent(TranslationService.class)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_INPUT_MODE,
                mic ? TranslationService.INPUT_MICROPHONE : TranslationService.INPUT_PLAYBACK)
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, enableOcr.isChecked())
            .putExtra(TranslationService.EXTRA_AUTO_MIC_FALLBACK, false);
        if (resultData != null) service.putExtra(TranslationService.EXTRA_RESULT_DATA, resultData);
        startForegroundService(service);
    }

    private void stopAllServices() {
        try { startService(new Intent(this, TranslationService.class).setAction(TranslationService.ACTION_STOP)); }
        catch (Exception ignored) {}
        try { startService(new Intent(this, RootCallTranslationService.class).setAction(TranslationService.ACTION_STOP)); }
        catch (Exception ignored) {}
        status.setText("已停止全部翻译");
    }

    private void downloadCurrentModel(Button button) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) { toast("请选择不同语言"); return; }
        button.setEnabled(false);
        status.setText("正在下载 ML Kit 离线翻译模型……");
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag)
            .setTargetLanguage(target.mlKitTag)
            .build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> status.setText("✅ ML Kit 离线翻译模型已就绪"))
            .addOnFailureListener(e -> status.setText("❌ 模型下载失败：" + safe(e)))
            .addOnCompleteListener(x -> { button.setEnabled(true); translator.close(); });
    }

    private void testTranslationEngine(Button button) {
        saveSettings(false);
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) { toast("请选择不同语言"); return; }
        OfflineFirstTranslationRouter router = new OfflineFirstTranslationRouter(this,
            source.mlKitTag, target.mlKitTag, preferences.getString("engine_id", TranslationRouter.AUTO));
        String sample = sampleFor(source.mlKitTag);
        button.setEnabled(false);
        status.setText("正在测试翻译引擎……");
        router.translate(sample, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String out, String engineName) {
                status.setText("✅ " + engineName + "\n原文：" + sample + "\n译文：" + out);
                preferences.edit().putString("last_original", sample).putString("last_translation", out).apply();
                button.setEnabled(true);
                updateRecent();
                router.close();
            }
            @Override public void onError(String message) {
                status.setText("❌ 测试失败：" + message);
                button.setEnabled(true);
                router.close();
            }
        });
    }

    private String sampleFor(String language) {
        switch (language) {
            case "zh": return "你好，这是翻译测试。";
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
        status.setText("正在检查更新……");
        updateChecker.check(BuildConfig.VERSION_NAME, new UpdateChecker.Callback() {
            @Override public void onResult(String latestVersion, String pageUrl, String apkUrl, boolean newer) {
                button.setEnabled(true);
                if (!newer) { status.setText("✅ 当前已是最新版 v" + BuildConfig.VERSION_NAME); return; }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("发现浮译 v" + latestVersion)
                    .setMessage("当前版本：v" + BuildConfig.VERSION_NAME + "\n仅发布通用 APK。")
                    .setNegativeButton("稍后", null)
                    .setNeutralButton("版本页面", (d, w) -> openUrl(pageUrl))
                    .setPositiveButton("下载最新版", (d, w) -> openUrl(apkUrl))
                    .show();
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                status.setText("检查更新失败：" + message);
            }
        });
    }

    private void saveSettings(boolean notify) {
        if (sourceSpinner == null) return;
        preferences.edit()
            .putInt("input_mode", inputModeSpinner.getSelectedItemPosition())
            .putString("language_mode", selectedLanguageMode())
            .putString(SelectedSpeechLanguages.PREF_CUSTOM_CODES,
                SelectedSpeechLanguages.serialize(chosenCustomLanguages))
            .putInt("source_index", sourceSpinner.getSelectedItemPosition())
            .putInt("target_index", targetSpinner.getSelectedItemPosition())
            .putBoolean("show_original", showOriginal.isChecked())
            .putBoolean("show_diagnostics", showDiagnostics.isChecked())
            .putBoolean("translation_only_overlay", translationOnlyOverlay.isChecked())
            .putBoolean("smart_sentence_translation", smartSentenceTranslation.isChecked())
            .putBoolean("prefer_offline", preferOffline.isChecked())
            .putString("mic_processing", selectedMicProcessing())
            .putBoolean("enable_ocr", enableOcr.isChecked())
            .putBoolean("auto_mic_fallback", false)
            .putInt("font_size", fontSize.getProgress())
            .apply();
        refreshSummary();
        if (notify) toast("设置已保存");
    }

    private void refreshSummary() {
        if (summary == null) return;
        int mode = inputModeSpinner == null ? preferences.getInt("input_mode", 0) : inputModeSpinner.getSelectedItemPosition();
        RootCallProfileStore.Profile profile = RootCallProfileStore.loadSelected(this);
        if (mode == 3) profile = RootCallProfileStore.loadSelectedMedia(this);
        String source = mode == 3 ? "ROOT 直播内部声音（无屏幕授权·实验）"
            : mode == 2 ? ((profile == null ? "ROOT/Shizuku" : profile.sourceLabel()) + " 通话/VoIP")
            : mode == 1 ? "麦克风" : "系统内部声音（授权后仅采音频）";
        String internal = mode < 2 || profile == null ? "" : "\n内部 PCM 配置：" + profile.packageName
            + " · " + profile.sourceLabel();
        String mic = mode == 1 ? "\n麦克风处理：" + micProcessingLabel(preferences.getString(
            "mic_processing", MicAudioEffects.MODE_REMOTE)) : "";
        summary.setText("声音：" + source + "\nASR：" + asrLabel(TranslationService.strictAsrMode(preferences.getString("asr_mode", TranslationService.ASR_SYSTEM)))
            + "\n语言：" + (SelectedSpeechLanguages.MODE_CUSTOM.equals(selectedLanguageMode())
                ? "自选混合 " + SelectedSpeechLanguages.display(chosenCustomLanguages)
                : "单语言 " + (((LanguageOption) sourceSpinner.getSelectedItem()).label))
            + "\n历史：" + HistoryStore.count(this) + " 条" + internal + mic);
    }

    private void updateRecent() {
        if (recent == null) return;
        String o = preferences.getString("last_original", "");
        String t = preferences.getString("last_translation", "");
        recent.setText(t.isEmpty() ? "暂无最近译文" : "原文：" + o + "\n译文：" + t);
    }

    private void copyLastTranslation() {
        String t = preferences.getString("last_translation", "");
        if (t.isEmpty()) { toast("还没有可复制的译文"); return; }
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("浮译译文", t));
        toast("已复制");
    }

    private String selectedAsrMode() {
        return TranslationService.strictAsrMode(
            preferences.getString("asr_mode", TranslationService.ASR_SYSTEM));
    }

    private int micProcessingIndex(String mode) {
        String normalized = MicAudioEffects.normalize(mode);
        if (MicAudioEffects.MODE_NEAR.equals(normalized)) return 1;
        if (MicAudioEffects.MODE_RAW.equals(normalized)) return 2;
        return 0;
    }

    private String selectedMicProcessing() {
        if (micProcessingSpinner == null) return MicAudioEffects.MODE_REMOTE;
        switch (micProcessingSpinner.getSelectedItemPosition()) {
            case 1: return MicAudioEffects.MODE_NEAR;
            case 2: return MicAudioEffects.MODE_RAW;
            default: return MicAudioEffects.MODE_REMOTE;
        }
    }

    private String micProcessingLabel(String mode) {
        String normalized = MicAudioEffects.normalize(mode);
        if (MicAudioEffects.MODE_NEAR.equals(normalized)) return "近端优先 AEC+NS+AGC";
        if (MicAudioEffects.MODE_RAW.equals(normalized)) return "原始麦克风";
        return "远端外放优先 NS+AGC/AEC关";
    }

    private int languageModeIndex(String mode) {
        return SelectedSpeechLanguages.MODE_CUSTOM.equals(mode)
            || SherpaSpeechEngine.LANG_JA_EN.equals(mode)
            || SherpaSpeechEngine.LANG_ZH_EN.equals(mode)
            || SherpaSpeechEngine.LANG_KO_EN.equals(mode) ? 1 : 0;
    }

    private String selectedLanguageMode() {
        return languageModeSpinner != null && languageModeSpinner.getSelectedItemPosition() == 1
            ? SelectedSpeechLanguages.MODE_CUSTOM : SherpaSpeechEngine.LANG_SINGLE;
    }

    private void updateCustomLanguageButton() {
        if (customLanguageButton == null) return;
        boolean mixed = SelectedSpeechLanguages.MODE_CUSTOM.equals(selectedLanguageMode());
        customLanguageButton.setVisibility(mixed ? View.VISIBLE : View.GONE);
        customLanguageButton.setText("＋ 选择混合识别语言："
            + SelectedSpeechLanguages.display(chosenCustomLanguages));
        if (singleSourceLabel != null) singleSourceLabel.setVisibility(mixed ? View.GONE : View.VISIBLE);
        if (sourceSpinner != null) sourceSpinner.setVisibility(mixed ? View.GONE : View.VISIBLE);
        if (swapLanguageButton != null) swapLanguageButton.setVisibility(mixed ? View.GONE : View.VISIBLE);
    }

    private void chooseCustomLanguages() {
        final LanguageOption[] options = LanguageOption.ALL;
        final String[] names = new String[options.length];
        final boolean[] checked = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            names[i] = options[i].label;
            checked[i] = chosenCustomLanguages.contains(options[i].mlKitTag);
        }
        new AlertDialog.Builder(this)
            .setTitle("选择 2 种或更多语言｜仅翻译选中的语种")
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) ->
                checked[which] = isChecked)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> {
                List<String> selected = new ArrayList<>();
                for (int i = 0; i < options.length; i++) {
                    if (checked[i]) selected.add(options[i].mlKitTag);
                }
                if (selected.size() < 2) {
                    toast("至少选择两种语言，再点击保存");
                    return;
                }
                chosenCustomLanguages = selected;
                updateCustomLanguageButton();
                saveSettings(false);
            })
            .show();
    }

    private String asrLabel(String mode) {
        if (TranslationService.ASR_FREE_ONLINE.equals(mode)) return "免费在线自动";
        if (TranslationService.ASR_GROQ_LARGE.equals(mode)) return "Groq Free · Whisper Large V3";
        if (TranslationService.ASR_GROQ_TURBO.equals(mode)) return "Groq Free · Whisper Turbo";
        if (TranslationService.ASR_CLOUDFLARE.equals(mode)) return "Cloudflare Workers AI Free";
        if (TranslationService.ASR_VOSK.equals(mode)) return "Vosk";
        if (TranslationService.ASR_SENSEVOICE.equals(mode)) return "SenseVoice";
        if (TranslationService.ASR_REAZON.equals(mode)) return "ReazonSpeech";
        if (TranslationService.ASR_PARAKEET.equals(mode)) return "Parakeet 日语";
        if (TranslationService.ASR_WHISPER_SMALL.equals(mode)) return "Whisper Small";
        if (TranslationService.ASR_WHISPER_MEDIUM.equals(mode)) return "Whisper Medium";
        if (TranslationService.ASR_QWEN3.equals(mode)) return "Qwen3-ASR";
        if (TranslationService.ASR_OMNILINGUAL.equals(mode)) return "Omnilingual";
        if (TranslationService.ASR_SYSTEM.equals(mode)) return "系统 SpeechRecognizer";
        if (TranslationService.ASR_YOUDAO.equals(mode)) return "有道云 ASR";
        return "自动推荐";
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = column();
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
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 13, Color.rgb(201, 190, 221));
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private CheckBox check(String value, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(value);
        box.setTextColor(Color.WHITE);
        box.setChecked(checked);
        return box;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
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

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("无法打开链接：" + safe(e)); }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_PERMISSIONS);
        } else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentAsr != null) currentAsr.setText(
            "当前：" + asrLabel(selectedAsrMode()) + "（固定，不自动回退）");
        if (inputModeSpinner != null) inputModeSpinner.setSelection(Math.min(3, preferences.getInt("input_mode", 0)));
        if (micProcessingSpinner != null) micProcessingSpinner.setSelection(micProcessingIndex(
            preferences.getString("mic_processing", MicAudioEffects.MODE_REMOTE)));
        refreshSummary();
        updateRecent();
    }

    @Override protected void onPause() {
        saveSettings(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (updateChecker != null) updateChecker.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
