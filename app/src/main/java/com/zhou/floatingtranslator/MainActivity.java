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

/** Detailed translation console. HomeActivity is the compact launcher. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_CAPTURE = 11;
    private static final String PREFS = "floating_translator";

    private SharedPreferences preferences;
    private Spinner inputModeSpinner;
    private Spinner micProcessingSpinner;
    private Spinner asrModeSpinner;
    private Spinner languageModeSpinner;
    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private CheckBox showOriginal;
    private CheckBox showDiagnostics;
    private CheckBox preferOffline;
    private CheckBox enableOcr;
    private SeekBar fontSize;
    private TextView summary;
    private TextView status;
    private TextView recent;
    private UpdateChecker updateChecker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
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
        Button models = secondaryButton("📦 离线模型中心");
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        quick.addView(models, params());

        LinearLayout audioCard = card(root);
        audioCard.addView(section("声音与识别"));
        audioCard.addView(label("固定声音来源"));
        inputModeSpinner = new Spinner(this);
        inputModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "系统内部声音｜直播/视频",
                "麦克风｜通话外放兼容",
                "ROOT / Shizuku 内部通话/VoIP｜按 App 保存 PCM"
            }));
        inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));
        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(inputModeSpinner, params());

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
            "自动模式下，麦克风来源优先 Android 系统 SpeechRecognizer，并允许系统联网获得更好识别；系统内部声音/ROOT PCM 无法直接交给 Android SpeechRecognizer，会自动跳到免费在线，再到本地。付费 ASR 只在你显式开启后最后兜底。",
            12, Color.rgb(184, 174, 207));
        audioCard.addView(micTip);

        audioCard.addView(label("ASR 语音识别"));
        asrModeSpinner = new Spinner(this);
        asrModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "自动推荐｜安卓内置优先 → 免费在线 → 本地 → 付费最后",
                "免费在线自动｜Groq Free → Cloudflare Free → 本地",
                "Groq Free｜Whisper Large V3｜在线高精度",
                "Groq Free｜Whisper Large V3 Turbo｜在线低延迟",
                "Cloudflare Workers AI Free｜Whisper Large V3 Turbo",
                "Vosk｜省电/小模型",
                "SenseVoice INT8｜中英日韩粤",
                "ReazonSpeech｜日语直播优先",
                "Parakeet 日语 0.6B INT8｜快语速/长句",
                "Whisper Small INT8｜多语言混合",
                "Whisper Medium INT8｜高精度/高占用",
                "Qwen3-ASR 0.6B INT8｜日英混合优先",
                "Omnilingual ASR 300M INT8｜小语种",
                "Android 系统 SpeechRecognizer｜首选｜仅麦克风",
                "有道云 ASR｜付费/旧兼容｜只做最后兜底"
            }));
        asrModeSpinner.setSelection(asrIndex(preferences.getString("asr_mode", TranslationService.ASR_AUTO)));
        asrModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(asrModeSpinner, params());

        audioCard.addView(label("识别语言模式"));
        languageModeSpinner = new Spinner(this);
        languageModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{
                "单语言",
                "日语 + 英语（混合）",
                "中文 + 英语（混合）",
                "韩语 + 英语（混合）",
                "自动多语言"
            }));
        languageModeSpinner.setSelection(languageModeIndex(preferences.getString(
            "language_mode", SherpaSpeechEngine.LANG_SINGLE)));
        languageModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        audioCard.addView(languageModeSpinner, params());

        TextView rootTip = text(
            "内部通话模式：在兼容中心明确选择固定 ROOT 或固定 Shizuku，再扫描/测试 PCM 并按 App 保存。" +
            "Shizuku 是免 ROOT 实验方案，受 shell/SELinux 限制；失败不会自动切到 ROOT 或麦克风。",
            12, Color.rgb(184, 174, 207));
        rootTip.setPadding(0, dp(6), 0, 0);
        audioCard.addView(rootTip);

        LinearLayout languageCard = card(root);
        languageCard.addView(section("翻译语言"));
        languageCard.addView(label("原语言"));
        sourceSpinner = languageSpinner();
        sourceSpinner.setSelection(preferences.getInt("source_index", 2));
        languageCard.addView(sourceSpinner, params());
        languageCard.addView(label("翻译为"));
        targetSpinner = languageSpinner();
        targetSpinner.setSelection(preferences.getInt("target_index", 0));
        languageCard.addView(targetSpinner, params());
        Button swap = secondaryButton("⇄ 互换语言");
        swap.setOnClickListener(v -> {
            int s = sourceSpinner.getSelectedItemPosition();
            sourceSpinner.setSelection(targetSpinner.getSelectedItemPosition());
            targetSpinner.setSelection(s);
            saveSettings(false);
        });
        languageCard.addView(swap, params());

        LinearLayout displayCard = card(root);
        displayCard.addView(section("字幕与显示"));
        showOriginal = check("显示原文", preferences.getBoolean("show_original", true));
        showDiagnostics = check("显示诊断（声音 / ASR / 翻译）",
            preferences.getBoolean("show_diagnostics", true));
        enableOcr = check("屏幕 OCR（ROOT 通话模式会忽略）",
            preferences.getBoolean("enable_ocr", false));
        preferOffline = check("手动选择系统 SpeechRecognizer 时强制请求离线（自动模式忽略此项，优先在线质量）",
            preferences.getBoolean("prefer_offline", false));
        displayCard.addView(showOriginal);
        displayCard.addView(showDiagnostics);
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
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("原语言和目标语言不能相同");
            return;
        }

        int mode = inputModeSpinner.getSelectedItemPosition();
        boolean mic = mode == 1;
        boolean root = mode == 2;
        String asr = selectedAsrMode();

        if (root && !RootCallProfileStore.hasSelected(this)) {
            toast("内部通话模式还没有保存 PCM 配置，先去 ROOT / Shizuku 兼容中心");
            startActivity(new Intent(this, RootCallActivity.class));
            return;
        }
        if (TranslationService.ASR_SYSTEM.equals(asr) && !mic) {
            toast("系统 SpeechRecognizer 只能使用麦克风；系统内部声音/ROOT/Shizuku 请选本地 ASR 或有道 ASR");
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
            if (enableOcr.isChecked()) toast("ROOT / Shizuku 通话模式暂不使用 OCR，已只启动通话声音翻译");
            startRootTranslationService();
            RootCallProfileStore.Profile p = RootCallProfileStore.loadSelected(this);
            status.setText("正在启动 " + (p == null ? "内部" : p.sourceLabel()) + " 通话翻译；保持目标 App 通话即可。");
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
        status.setText("请允许共享整个屏幕；系统内部声音/OCR 需要此授权。");
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
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_ENGINE_ID,
                preferences.getString("engine_id", TranslationRouter.AUTO))
            .putExtra(TranslationService.EXTRA_YOUDAO_SPEECH_FALLBACK,
                preferences.getBoolean("youdao_speech_fallback", false))
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_SHOW_DIAGNOSTICS, showDiagnostics.isChecked())
            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())
            .putExtra(TranslationService.EXTRA_MIC_PROCESSING, selectedMicProcessing())
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());
    }

    private void startRootTranslationService() {
        stopService(new Intent(this, TranslationService.class));
        Intent service = baseServiceIntent(RootCallTranslationService.class)
            .putExtra(TranslationService.EXTRA_ENABLE_OCR, false)
            .putExtra(TranslationService.EXTRA_INPUT_MODE, "root");
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
            .putString("asr_mode", selectedAsrMode())
            .putString("language_mode", selectedLanguageMode())
            .putInt("source_index", sourceSpinner.getSelectedItemPosition())
            .putInt("target_index", targetSpinner.getSelectedItemPosition())
            .putBoolean("show_original", showOriginal.isChecked())
            .putBoolean("show_diagnostics", showDiagnostics.isChecked())
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
        String source = mode == 2 ? ((profile == null ? "ROOT/Shizuku" : profile.sourceLabel()) + " 通话/VoIP")
            : mode == 1 ? "麦克风" : "系统内部声音";
        String internal = profile == null ? "" : "\n内部 App：" + profile.packageName + " · " + profile.sourceLabel();
        String mic = mode == 1 ? "\n麦克风处理：" + micProcessingLabel(preferences.getString(
            "mic_processing", MicAudioEffects.MODE_REMOTE)) : "";
        summary.setText("声音：" + source + "\nASR：" + asrLabel(preferences.getString("asr_mode", TranslationService.ASR_AUTO))
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

    private int asrIndex(String mode) {
        if (TranslationService.ASR_FREE_ONLINE.equals(mode)) return 1;
        if (TranslationService.ASR_GROQ_LARGE.equals(mode)) return 2;
        if (TranslationService.ASR_GROQ_TURBO.equals(mode)) return 3;
        if (TranslationService.ASR_CLOUDFLARE.equals(mode)) return 4;
        if (TranslationService.ASR_VOSK.equals(mode)) return 5;
        if (TranslationService.ASR_SENSEVOICE.equals(mode)) return 6;
        if (TranslationService.ASR_REAZON.equals(mode)) return 7;
        if (TranslationService.ASR_PARAKEET.equals(mode)) return 8;
        if (TranslationService.ASR_WHISPER_SMALL.equals(mode)) return 9;
        if (TranslationService.ASR_WHISPER_MEDIUM.equals(mode)) return 10;
        if (TranslationService.ASR_QWEN3.equals(mode)) return 11;
        if (TranslationService.ASR_OMNILINGUAL.equals(mode)) return 12;
        if (TranslationService.ASR_SYSTEM.equals(mode)) return 13;
        if (TranslationService.ASR_YOUDAO.equals(mode)) return 14;
        return 0;
    }

    private String selectedAsrMode() {
        switch (asrModeSpinner.getSelectedItemPosition()) {
            case 1: return TranslationService.ASR_FREE_ONLINE;
            case 2: return TranslationService.ASR_GROQ_LARGE;
            case 3: return TranslationService.ASR_GROQ_TURBO;
            case 4: return TranslationService.ASR_CLOUDFLARE;
            case 5: return TranslationService.ASR_VOSK;
            case 6: return TranslationService.ASR_SENSEVOICE;
            case 7: return TranslationService.ASR_REAZON;
            case 8: return TranslationService.ASR_PARAKEET;
            case 9: return TranslationService.ASR_WHISPER_SMALL;
            case 10: return TranslationService.ASR_WHISPER_MEDIUM;
            case 11: return TranslationService.ASR_QWEN3;
            case 12: return TranslationService.ASR_OMNILINGUAL;
            case 13: return TranslationService.ASR_SYSTEM;
            case 14: return TranslationService.ASR_YOUDAO;
            default: return TranslationService.ASR_AUTO;
        }
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
        if (SherpaSpeechEngine.LANG_JA_EN.equals(mode)) return 1;
        if (SherpaSpeechEngine.LANG_ZH_EN.equals(mode)) return 2;
        if (SherpaSpeechEngine.LANG_KO_EN.equals(mode)) return 3;
        if (SherpaSpeechEngine.LANG_AUTO.equals(mode)) return 4;
        return 0;
    }

    private String selectedLanguageMode() {
        switch (languageModeSpinner.getSelectedItemPosition()) {
            case 1: return SherpaSpeechEngine.LANG_JA_EN;
            case 2: return SherpaSpeechEngine.LANG_ZH_EN;
            case 3: return SherpaSpeechEngine.LANG_KO_EN;
            case 4: return SherpaSpeechEngine.LANG_AUTO;
            default: return SherpaSpeechEngine.LANG_SINGLE;
        }
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
        if (inputModeSpinner != null) inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));
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
