package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FloatingTranslator real-time pipeline.
 *
 * Audio source is always fixed by the user and is never switched automatically.
 * Optional high-accuracy ASR models are local sherpa-onnx model packs. AUTO selects
 * a downloaded model appropriate to single-language vs mixed-language speech, then
 * falls back to Vosk and finally system/Youdao where possible.
 */
public class TranslationService extends Service implements RecognitionListener {
    public static final String ACTION_START = "com.zhou.floatingtranslator.START";
    public static final String ACTION_STOP = "com.zhou.floatingtranslator.STOP";
    public static final String ACTION_UI_VISIBLE = "com.zhou.floatingtranslator.UI_VISIBLE";
    public static final String ACTION_UI_HIDDEN = "com.zhou.floatingtranslator.UI_HIDDEN";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_INPUT_MODE = "input_mode";
    public static final String INPUT_PLAYBACK = "playback";
    public static final String INPUT_MICROPHONE = "microphone";

    public static final String EXTRA_ASR_MODE = "asr_mode";
    public static final String ASR_AUTO = "auto";
    public static final String ASR_VOSK = "vosk";
    public static final String ASR_SENSEVOICE = OfflineAsrModelCatalog.SENSEVOICE;
    public static final String ASR_REAZON = OfflineAsrModelCatalog.REAZON_JA;
    public static final String ASR_PARAKEET = OfflineAsrModelCatalog.PARAKEET_JA;
    public static final String ASR_WHISPER_SMALL = OfflineAsrModelCatalog.WHISPER_SMALL;
    public static final String ASR_WHISPER_MEDIUM = OfflineAsrModelCatalog.WHISPER_MEDIUM;
    public static final String ASR_QWEN3 = OfflineAsrModelCatalog.QWEN3_ASR;
    public static final String ASR_OMNILINGUAL = OfflineAsrModelCatalog.OMNILINGUAL;
    public static final String ASR_SYSTEM = "system";
    public static final String ASR_YOUDAO = "youdao";
    public static final String ASR_FREE_ONLINE = FreeOnlineSpeechEngine.AUTO;
    public static final String ASR_GROQ_LARGE = FreeOnlineSpeechEngine.GROQ_LARGE;
    public static final String ASR_GROQ_TURBO = FreeOnlineSpeechEngine.GROQ_TURBO;
    public static final String ASR_CLOUDFLARE = FreeOnlineSpeechEngine.CLOUDFLARE;

    public static final String EXTRA_LANGUAGE_MODE = "language_mode";
    public static final String EXTRA_SOURCE_SPEECH = "source_speech";
    public static final String EXTRA_SOURCE_MLKIT = "source_mlkit";
    public static final String EXTRA_TARGET_MLKIT = "target_mlkit";
    public static final String EXTRA_ENGINE_ID = "engine_id";
    public static final String EXTRA_YOUDAO_SPEECH_FALLBACK = "youdao_speech_fallback";
    public static final String EXTRA_SHOW_ORIGINAL = "show_original";
    public static final String EXTRA_SHOW_DIAGNOSTICS = "show_diagnostics";
    public static final String EXTRA_PREFER_OFFLINE = "prefer_offline";
    public static final String EXTRA_FONT_SIZE = "font_size";
    public static final String EXTRA_ENABLE_OCR = "enable_ocr";
    public static final String EXTRA_AUTO_MIC_FALLBACK = "auto_mic_fallback"; // compatibility; ignored
    public static final String EXTRA_MIC_PROCESSING = "mic_processing";

    private static final String PREFS = "floating_translator";
    private static final int NOTIFICATION_ID = 3401;
    private static final String CHANNEL_ID = "floating_translation";
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_PRESENT_PEAK = 80;
    private static final long LEVEL_UPDATE_MS = 700L;
    private static final long STREAM_TRANSLATE_INTERVAL_MS = 1200L;
    private static final long CLOUD_CHUNK_MS = 3600L;
    private static final int TRANSLATE_CHUNK_CHARS = 180;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private final Set<String> failedAutoModels = new HashSet<>();

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private MicAudioEffects micEffects;
    private OfflineSpeechEngine offlineSpeech;
    private SherpaSpeechEngine sherpaSpeech;
    private FreeOnlineSpeechEngine onlineSpeech;
    private SpeechRecognizer systemRecognizer;
    private OfflineFirstTranslationRouter translator;
    private OcrCapture ocrCapture;

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean uiVisible;
    private volatile boolean audioCaptureStarted;
    private volatile boolean cloudSpeechMode;
    private volatile boolean cloudRequestBusy;
    private volatile int captureGeneration;

    private String inputMode = INPUT_PLAYBACK;
    private String asrMode = ASR_AUTO;
    private String activeAsrMode = ASR_AUTO;
    private String languageMode = SherpaSpeechEngine.LANG_SINGLE;
    private String speechLanguage = "en-US";
    private String sourceMlTag = "en";
    private String targetMlTag = "zh";
    private String engineId = TranslationRouter.AUTO;
    private boolean showOriginal;
    private boolean showDiagnostics;
    private boolean enableOcr;
    private boolean allowYoudaoSpeech;
    private boolean preferOffline;
    private String micProcessing = MicAudioEffects.MODE_REMOTE;
    private boolean failedAutoVosk;

    private long lastLevelUiAt;
    private long cloudChunkStartedAt;
    private long lastStreamTranslateAt;
    private final ByteArrayOutputStream cloudPcm = new ByteArrayOutputStream();

    private boolean ocrBusy;
    private String lastOcrText = "";
    private String pendingOcrText = "";
    private String lastStreamTranslated = "";

    private boolean speechTranslationBusy;
    private final SpeechQueue speechQueue = new SpeechQueue();
    private String segmentLanguage = "";

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView pauseControl;
    private TextView diagnosticsText;
    private TextView originalText;
    private TextView translatedText;
    private TextView ocrOriginalText;
    private TextView ocrTranslatedText;

    private volatile String diagTranslation = "未启动";
    private volatile String diagAudio = "未启动";
    private volatile String diagSpeech = "未启动";
    private volatile String diagScreen = "未启动";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_UI_VISIBLE.equals(action)) {
            if (running) setUiVisible(true); else stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UI_HIDDEN.equals(action)) {
            if (running) setUiVisible(false); else stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        stopPipelineOnly();
        removeOverlay();

        inputMode = intent.getStringExtra(EXTRA_INPUT_MODE);
        if (!INPUT_MICROPHONE.equals(inputMode)) inputMode = INPUT_PLAYBACK;
        asrMode = normalizeAsr(intent.getStringExtra(EXTRA_ASR_MODE));
        activeAsrMode = asrMode;
        languageMode = normalizeLanguageMode(intent.getStringExtra(EXTRA_LANGUAGE_MODE));
        speechLanguage = value(intent.getStringExtra(EXTRA_SOURCE_SPEECH), "en-US");
        sourceMlTag = value(intent.getStringExtra(EXTRA_SOURCE_MLKIT), "en");
        targetMlTag = value(intent.getStringExtra(EXTRA_TARGET_MLKIT), "zh");
        engineId = value(intent.getStringExtra(EXTRA_ENGINE_ID), TranslationRouter.AUTO);
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        showDiagnostics = intent.getBooleanExtra(EXTRA_SHOW_DIAGNOSTICS, true);
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, false);
        allowYoudaoSpeech = intent.getBooleanExtra(EXTRA_YOUDAO_SPEECH_FALLBACK, true);
        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, true);
        micProcessing = MicAudioEffects.normalize(intent.getStringExtra(EXTRA_MIC_PROCESSING));
        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);

        boolean projectionNeeded = INPUT_PLAYBACK.equals(inputMode) || enableOcr;
        int foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (projectionNeeded) foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(NOTIFICATION_ID, buildNotification("正在准备实时翻译"), foregroundType);

        if (!Settings.canDrawOverlays(this)
            || (projectionNeeded && resultData == null)
            || sourceMlTag.equals(targetMlTag)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        running = true;
        paused = false;
        uiVisible = false;
        audioCaptureStarted = false;
        cloudSpeechMode = false;
        cloudRequestBusy = false;
        failedAutoVosk = false;
        failedAutoModels.clear();
        lastStreamTranslated = "";
        lastOcrText = "";
        pendingOcrText = "";
        speechTranslationBusy = false;
        speechQueue.clear();
        synchronized (cloudPcm) { cloudPcm.reset(); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", true).apply();

        createOverlay(fontSize);
        translator = new OfflineFirstTranslationRouter(this, sourceMlTag, targetMlTag, engineId);
        setDiagTranslation(translator.selectedEngineName());
        setDiagAudio(fixedSourceLabel() + " · 等待声音");
        setDiagSpeech("ASR：" + asrLabel(asrMode) + " · " + languageModeLabel() + " · 准备中");
        setDiagScreen(enableOcr ? "等待屏幕捕获" : "OCR 已关闭");
        showStatus("固定声音来源：" + fixedSourceLabel() + "\n正在准备 " + asrLabel(asrMode));

        if (projectionNeeded) startProjection(resultCode, resultData);
        else prepareSpeechEngine();
        return START_NOT_STICKY;
    }

    public static String modelIdForAsr(String mode) {
        return OfflineAsrModelCatalog.find(mode) == null ? null : mode;
    }

    private String normalizeAsr(String mode) {
        if (ASR_VOSK.equals(mode) || ASR_SYSTEM.equals(mode) || ASR_YOUDAO.equals(mode)
            || ASR_FREE_ONLINE.equals(mode) || ASR_GROQ_LARGE.equals(mode)
            || ASR_GROQ_TURBO.equals(mode) || ASR_CLOUDFLARE.equals(mode)
            || OfflineAsrModelCatalog.find(mode) != null) return mode;
        return ASR_AUTO;
    }

    private String normalizeLanguageMode(String mode) {
        if (SherpaSpeechEngine.LANG_JA_EN.equals(mode)
            || SherpaSpeechEngine.LANG_ZH_EN.equals(mode)
            || SherpaSpeechEngine.LANG_KO_EN.equals(mode)
            || SherpaSpeechEngine.LANG_AUTO.equals(mode)) return mode;
        return SherpaSpeechEngine.LANG_SINGLE;
    }

    private String asrLabel(String mode) {
        if (ASR_VOSK.equals(mode)) return "Vosk 离线 ASR";
        if (ASR_SYSTEM.equals(mode)) return "Android 系统 SpeechRecognizer";
        if (ASR_YOUDAO.equals(mode)) return "有道云语音 ASR";
        if (ASR_FREE_ONLINE.equals(mode)) return "免费在线自动 ASR";
        if (ASR_GROQ_LARGE.equals(mode)) return "Groq Free · Whisper Large V3";
        if (ASR_GROQ_TURBO.equals(mode)) return "Groq Free · Whisper Large V3 Turbo";
        if (ASR_CLOUDFLARE.equals(mode)) return "Cloudflare Workers AI Free";
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(mode);
        if (model != null) return model.name;
        return "自动推荐 ASR";
    }

    private String languageModeLabel() {
        switch (languageMode) {
            case SherpaSpeechEngine.LANG_JA_EN: return "日语+英语混合";
            case SherpaSpeechEngine.LANG_ZH_EN: return "中文+英语混合";
            case SherpaSpeechEngine.LANG_KO_EN: return "韩语+英语混合";
            case SherpaSpeechEngine.LANG_AUTO: return "自动多语言";
            default: return "单语言 " + sourceMlTag;
        }
    }

    private String fixedSourceLabel() {
        return INPUT_PLAYBACK.equals(inputMode) ? "固定·系统内部声音" : "固定·麦克风外放";
    }

    private void startProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("MediaProjection 返回空对象");
            final MediaProjection activeProjection = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(() -> {
                        if (running && projection == activeProjection) {
                            setDiagScreen("屏幕捕获被系统停止");
                            stopEverything();
                        }
                    });
                }
            }, main);
            if (enableOcr) startOcr();
            prepareSpeechEngine();
        } catch (Exception e) {
            setDiagScreen("屏幕捕获失败：" + safe(e));
            showStatus("屏幕/系统声音捕获启动失败：" + safe(e));
        }
    }

    private void prepareSpeechEngine() {
        if (!running) return;
        switch (asrMode) {
            case ASR_VOSK: startVosk(false); break;
            case ASR_SYSTEM: startSystemFixed(); break;
            case ASR_YOUDAO: startYoudaoFixed(); break;
            case ASR_FREE_ONLINE:
            case ASR_GROQ_LARGE:
            case ASR_GROQ_TURBO:
            case ASR_CLOUDFLARE:
                startFreeOnline(asrMode, false); break;
            case ASR_AUTO: prepareAutoAsr(); break;
            default:
                if (OfflineAsrModelCatalog.find(asrMode) != null) startSherpa(asrMode, false);
                else prepareAutoAsr();
                break;
        }
    }

    private void prepareAutoAsr() {
        if (!running || !ASR_AUTO.equals(asrMode)) return;
        if (FreeOnlineSpeechEngine.hasAnyConfigured(this)) {
            startFreeOnline(ASR_FREE_ONLINE, true);
            return;
        }
        prepareAutoLocalAsr();
    }

    private void prepareAutoLocalAsr() {
        if (!running || !ASR_AUTO.equals(asrMode)) return;
        String selected = bestInstalledSherpaForCurrentMode();
        if (selected != null) {
            startSherpa(selected, true);
            return;
        }
        if (!failedAutoVosk && OfflineSpeechEngine.supports(sourceMlTag)) {
            startVosk(true);
            return;
        }
        fallbackAutoSystemOrCloud("本机高精度模型/Vosk 暂不可用");
    }

    private String bestInstalledSherpaForCurrentMode() {
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            String[] order;
            boolean mixed = !SherpaSpeechEngine.LANG_SINGLE.equals(languageMode);
            if (mixed) {
                order = new String[]{ASR_QWEN3, ASR_WHISPER_SMALL, ASR_WHISPER_MEDIUM,
                    ASR_SENSEVOICE, ASR_OMNILINGUAL};
            } else if ("ja".equals(sourceMlTag)) {
                order = new String[]{ASR_REAZON, ASR_PARAKEET, ASR_SENSEVOICE,
                    ASR_WHISPER_SMALL, ASR_QWEN3, ASR_WHISPER_MEDIUM, ASR_OMNILINGUAL};
            } else if ("zh".equals(sourceMlTag) || "ko".equals(sourceMlTag) || "en".equals(sourceMlTag)) {
                order = new String[]{ASR_SENSEVOICE, ASR_WHISPER_SMALL, ASR_QWEN3,
                    ASR_WHISPER_MEDIUM, ASR_OMNILINGUAL};
            } else {
                order = new String[]{ASR_QWEN3, ASR_WHISPER_SMALL, ASR_WHISPER_MEDIUM, ASR_OMNILINGUAL};
            }
            for (String id : order) {
                if (!failedAutoModels.contains(id) && store.isInstalled(id)) return id;
            }
            return null;
        } finally {
            store.close();
        }
    }

    private void startSherpa(String modelId, boolean allowAutoFallback) {
        OfflineAsrModelCatalog.Model meta = OfflineAsrModelCatalog.find(modelId);
        if (meta == null) {
            if (allowAutoFallback) { failedAutoModels.add(modelId); prepareAutoAsr(); }
            return;
        }
        stopRawRecognitionEngines(false);
        setDiagSpeech("ASR：正在加载 " + meta.name + " · " + languageModeLabel());
        sherpaSpeech = new SherpaSpeechEngine(this, modelId, languageMode, sourceMlTag,
            new SherpaSpeechEngine.Callback() {
                @Override public void onStatus(String message) {
                    setDiagSpeech("ASR：" + message);
                    updateNotification(message);
                }

                @Override public void onReady(String engineName) {
                    if (!running) return;
                    activeAsrMode = modelId;
                    setDiagSpeech("ASR：" + engineName + " · " + languageModeLabel() + " · 离线");
                    showStatus(engineName + " 已就绪，声音来源保持 " + fixedSourceLabel());
                    startRawAudioCapture();
                }

                @Override public void onText(String text, String detectedLanguage) {
                    if (!running || paused || text == null || text.trim().isEmpty()) return;
                    String cleaned = text.trim();
                    String lang = detectedLanguage == null || detectedLanguage.isEmpty() ? "" : " · " + detectedLanguage;
                    setDiagSpeech("ASR：" + meta.name + lang + " · " + preview(cleaned));
                    if (showOriginal && originalText != null) originalText.setText("原文：" + cleaned);
                    queueSpeechTranslation(cleaned, true, detectedLanguage);
                }

                @Override public void onError(String message) {
                    if (!running) return;
                    setDiagSpeech("ASR：" + meta.name + " 失败 · " + message);
                    if (allowAutoFallback && ASR_AUTO.equals(asrMode)) {
                        failedAutoModels.add(modelId);
                        closeSherpa();
                        prepareAutoAsr();
                    } else {
                        showStatus(meta.name + " 失败：" + message + "\n声音来源不会自动改变；可回 App 换 ASR。 ");
                    }
                }
            });
        sherpaSpeech.prepare();
    }

    private void startFreeOnline(String mode, boolean allowAutoFallback) {
        stopRawRecognitionEngines(false);
        setDiagSpeech("ASR：正在连接 " + asrLabel(mode));
        onlineSpeech = new FreeOnlineSpeechEngine(this, mode, languageMode, sourceMlTag,
            new FreeOnlineSpeechEngine.Callback() {
                @Override public void onStatus(String message) {
                    setDiagSpeech("ASR：" + message);
                    updateNotification(message);
                }
                @Override public void onReady(String engineName) {
                    if (!running) return;
                    activeAsrMode = mode;
                    setDiagSpeech("ASR：" + engineName + " · 在线免费");
                    showStatus(engineName + " 已就绪；免费额度到限后自动回退本地");
                    startRawAudioCapture();
                }
                @Override public void onText(String text, String detectedLanguage, String engineName) {
                    if (!running || paused || text == null || text.trim().isEmpty()) return;
                    String cleaned = text.trim();
                    setDiagSpeech("ASR：" + engineName + " · " + preview(cleaned));
                    if (showOriginal && originalText != null) originalText.setText("原文：" + cleaned);
                    queueSpeechTranslation(cleaned, true, detectedLanguage);
                }
                @Override public void onError(String message, boolean fatal) {
                    if (!running) return;
                    setDiagSpeech("ASR：免费在线失败 · " + message);
                    if (fatal && allowAutoFallback && ASR_AUTO.equals(asrMode)) {
                        closeOnlineSpeech();
                        showStatus("免费在线 ASR 暂不可用，自动切回本地识别");
                        prepareAutoLocalAsr();
                    } else if (fatal) {
                        showStatus("免费在线 ASR 失败：" + message);
                    }
                }
            });
        onlineSpeech.prepare();
    }

    private void startVosk(boolean allowAutoFallback) {
        stopRawRecognitionEngines(false);
        if (!OfflineSpeechEngine.supports(sourceMlTag)) {
            if (allowAutoFallback) {
                failedAutoVosk = true;
                prepareAutoAsr();
            } else {
                setDiagSpeech("Vosk 不支持当前语言");
                showStatus("Vosk 暂不支持当前语言，请选择其他 ASR");
            }
            return;
        }
        if (!SherpaSpeechEngine.LANG_SINGLE.equals(languageMode)) {
            setDiagSpeech("ASR：Vosk · 注意：固定单语言模型，不擅长混合语");
        } else {
            setDiagSpeech("ASR：Vosk 离线模型准备中");
        }
        offlineSpeech = new OfflineSpeechEngine(this, sourceMlTag, new OfflineSpeechEngine.Callback() {
            @Override public void onStatus(String message) {
                setDiagSpeech("ASR：" + message);
                updateNotification(message);
            }

            @Override public void onReady(String engineName) {
                if (!running) return;
                activeAsrMode = ASR_VOSK;
                setDiagSpeech("ASR：" + engineName + " · " + sourceMlTag + " · 流式");
                showStatus("Vosk 离线 ASR 已就绪，开始流式识别");
                startRawAudioCapture();
            }

            @Override public void onPartial(String text) { onVoskText(text, false); }
            @Override public void onFinal(String text) { onVoskText(text, true); }

            @Override public void onError(String message) {
                if (!running) return;
                setDiagSpeech("ASR：Vosk 失败 · " + message);
                if (allowAutoFallback && ASR_AUTO.equals(asrMode)) {
                    failedAutoVosk = true;
                    closeVosk();
                    prepareAutoAsr();
                } else showStatus("Vosk ASR 失败：" + message);
            }
        });
        offlineSpeech.prepare();
    }

    private void onVoskText(String text, boolean committed) {
        if (!running || paused || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        setDiagSpeech("ASR：Vosk " + (committed ? "最终" : "流式") + " · " + preview(cleaned));
        if (showOriginal && originalText != null) originalText.setText("原文：" + cleaned);
        if (committed) {
            lastStreamTranslated = "";
            queueSpeechTranslation(cleaned, true);
            return;
        }
        if (cleaned.length() < 3 || cleaned.equals(lastStreamTranslated)
            || now - lastStreamTranslateAt < STREAM_TRANSLATE_INTERVAL_MS) return;
        lastStreamTranslateAt = now;
        lastStreamTranslated = cleaned;
        queueSpeechTranslation(cleaned, false);
    }

    private void fallbackAutoSystemOrCloud(String reason) {
        if (!running || !ASR_AUTO.equals(asrMode)) return;
        if (INPUT_MICROPHONE.equals(inputMode) && SpeechRecognizer.isRecognitionAvailable(this)) {
            activeAsrMode = ASR_SYSTEM;
            setDiagSpeech("ASR：高精度离线不可用 → 系统 SpeechRecognizer");
            showStatus(reason + "\n声音来源保持麦克风，使用系统 SpeechRecognizer");
            startSystemRecognizer();
            return;
        }
        if (canUseYoudaoSpeech()) {
            activeAsrMode = ASR_YOUDAO;
            cloudSpeechMode = true;
            setDiagSpeech("ASR：离线不可用 → 有道云语音");
            showStatus(reason + "\n声音来源保持不变，使用有道云 ASR");
            startRawAudioCapture();
            return;
        }
        setDiagSpeech("ASR：没有可用识别器");
        showStatus(reason + "\n没有可用备用 ASR。可以到模型中心下载 SenseVoice / ReazonSpeech / Whisper / Qwen3。");
    }

    private void startSystemFixed() {
        activeAsrMode = ASR_SYSTEM;
        if (!INPUT_MICROPHONE.equals(inputMode)) {
            setDiagSpeech("ASR：系统 SpeechRecognizer 需要麦克风");
            showStatus("固定声音是系统内部声音，但 Android SpeechRecognizer 不能直接读取它。请手动换麦克风，或改用本地 ASR。");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setDiagSpeech("ASR：系统 SpeechRecognizer 不可用");
            showStatus("系统没有可用 SpeechRecognizer，请选择本地 ASR");
            return;
        }
        startSystemRecognizer();
    }

    private void startYoudaoFixed() {
        activeAsrMode = ASR_YOUDAO;
        if (!canUseYoudaoSpeech()) {
            setDiagSpeech("ASR：有道云未配置 Key");
            showStatus("有道云 ASR 需要 AppKey + AppSecret，请到 API 安全中心填写");
            return;
        }
        cloudSpeechMode = true;
        setDiagSpeech("ASR：有道云语音 · 固定声音来源");
        startRawAudioCapture();
    }

    private boolean canUseYoudaoSpeech() {
        return allowYoudaoSpeech && translator != null && translator.hasYoudaoCredentials();
    }

    private void startRawAudioCapture() {
        if (!running || audioCaptureStarted) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setDiagAudio(fixedSourceLabel() + " · 缺少录音权限");
            return;
        }
        try {
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
            int min = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE * 2);

            AudioRecord record;
            if (INPUT_PLAYBACK.equals(inputMode)) {
                if (projection == null) throw new IllegalStateException("没有屏幕捕获授权");
                AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
                record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(min * 2)
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            } else {
                record = new AudioRecord.Builder()
                    .setAudioSource(MicAudioEffects.recommendedAudioSource(micProcessing))
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(min * 2)
                    .build();
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                try { record.release(); } catch (Exception ignored) {}
                throw new IllegalStateException("AudioRecord 初始化失败");
            }
            audioRecord = record;
            closeMicEffects();
            if (INPUT_MICROPHONE.equals(inputMode)) micEffects = MicAudioEffects.attach(record, micProcessing);
            audioCaptureStarted = true;
            int generation = ++captureGeneration;
            record.startRecording();
            String processing = micEffects == null ? "" : " · " + micEffects.status();
            setDiagAudio(fixedSourceLabel() + " · 采集已启动" + processing);
            updateNotification(fixedSourceLabel() + " · " + asrLabel(activeAsrMode));
            audioWorker.execute(() -> audioLoop(record, generation));
        } catch (Exception e) {
            audioCaptureStarted = false;
            setDiagAudio(fixedSourceLabel() + " · 启动失败：" + safe(e));
            showStatus("固定声音来源启动失败：" + safe(e) + "\n不会自动切换到其他声音来源。");
        }
    }

    private void audioLoop(AudioRecord record, int generation) {
        short[] shorts = new short[1600];
        byte[] bytes = new byte[shorts.length * 2];
        while (running && generation == captureGeneration && record == audioRecord) {
            int count;
            try {
                count = record.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                main.post(() -> setDiagAudio(fixedSourceLabel() + " · 读取失败：" + safe(e)));
                break;
            }
            if (count <= 0 || paused) continue;
            int peak = shortsToBytes(shorts, count, bytes);
            long now = SystemClock.elapsedRealtime();
            updateAudioLevel(peak, now);

            FreeOnlineSpeechEngine online = onlineSpeech;
            OfflineSpeechEngine vosk = offlineSpeech;
            SherpaSpeechEngine sherpa = sherpaSpeech;
            if (online != null && online.isReady()) {
                online.acceptPcm(bytes, count * 2, peak);
            } else if (vosk != null && vosk.isReady()) {
                vosk.acceptPcm(bytes, count * 2);
            } else if (sherpa != null && sherpa.isReady()) {
                sherpa.acceptPcm(bytes, count * 2, peak);
            } else if (cloudSpeechMode) {
                appendCloudAudio(bytes, count * 2, peak, now);
            }
        }
    }

    private void queueSpeechTranslation(String text, boolean finalResult) {
        queueSpeechTranslation(text, finalResult, "");
    }

    private void queueSpeechTranslation(String text, boolean finalResult, String language) {
        if (!running || text == null || text.trim().isEmpty()) return;
        speechQueue.offer(text.trim(), finalResult, language);
        drainSpeechQueue();
    }

    private void drainSpeechQueue() {
        if (!running || speechTranslationBusy) return;
        SpeechQueue.Item next = speechQueue.poll();
        if (next == null) return;
        speechTranslationBusy = true;
        segmentLanguage = next.language;
        translateSpeechSegmented(next.text, next.complete);
    }

    private void translateSpeechSegmented(String text, boolean finalResult) {
        List<String> parts = splitForTranslation(text, TRANSLATE_CHUNK_CHARS);
        translatePart(parts, 0, new StringBuilder(), text, finalResult);
    }

    private void translatePart(List<String> parts, int index, StringBuilder out,
                               String original, boolean finalResult) {
        if (!running) { finishSpeechTranslation(); return; }
        if (index >= parts.size()) {
            String translated = out.toString().trim();
            if (translatedText != null) translatedText.setText("译文：" + translated);
            saveHistory(original, translated);
            finishSpeechTranslation();
            return;
        }
        OfflineFirstTranslationRouter current = translator;
        if (current == null) { finishSpeechTranslation(); return; }
        if (index == 0) setDiagTranslation("翻译中 · " + (finalResult ? "完整句" : "流式片段"));
        String part = parts.get(index);
        current.translate(part, segmentLanguage, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (!running || translator != current) return;
                if (out.length() > 0) out.append('\n');
                out.append(translated);
                setDiagTranslation((finalResult ? "最终" : "流式") + " · " + engineName
                    + (parts.size() > 1 ? " · 分段 " + (index + 1) + "/" + parts.size() : ""));
                translatePart(parts, index + 1, out, original, finalResult);
            }
            @Override public void onError(String message) {
                if (!running || translator != current) return;
                setDiagTranslation("翻译失败：" + message);
                if (translatedText != null) translatedText.setText("翻译失败：" + message);
                finishSpeechTranslation();
            }
        });
    }

    private void finishSpeechTranslation() {
        speechTranslationBusy = false;
        drainSpeechQueue();
    }

    private List<String> splitForTranslation(String text, int maxChars) {
        ArrayList<String> parts = new ArrayList<>();
        String remaining = text.trim();
        while (remaining.length() > maxChars) {
            int cut = bestCut(remaining, maxChars);
            String part = remaining.substring(0, cut).trim();
            if (!part.isEmpty()) parts.add(part);
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) parts.add(remaining);
        if (parts.isEmpty()) parts.add(text);
        return parts;
    }

    private int bestCut(String text, int maxChars) {
        int min = Math.max(1, maxChars / 2);
        for (int i = Math.min(maxChars, text.length() - 1); i >= min; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；'
                || c == '、' || c == '，' || c == ',' || Character.isWhitespace(c)) return i;
        }
        return Math.min(maxChars, text.length());
    }

    private void appendCloudAudio(byte[] bytes, int length, int peak, long now) {
        if (peak < AUDIO_PRESENT_PEAK && cloudPcm.size() == 0) return;
        synchronized (cloudPcm) {
            if (cloudPcm.size() == 0) cloudChunkStartedAt = now;
            cloudPcm.write(bytes, 0, length);
            if (now - cloudChunkStartedAt < CLOUD_CHUNK_MS || cloudRequestBusy) return;
            byte[] chunk = cloudPcm.toByteArray();
            cloudPcm.reset();
            cloudChunkStartedAt = now;
            if (chunk.length < SAMPLE_RATE * 2) return;
            sendCloudSpeech(chunk);
        }
    }

    private void sendCloudSpeech(byte[] pcm) {
        OfflineFirstTranslationRouter current = translator;
        if (current == null || !canUseYoudaoSpeech()) return;
        cloudRequestBusy = true;
        setDiagSpeech("ASR：有道云识别中……");
        current.translateYoudaoSpeech(pcm, new TranslationRouter.SpeechCallback() {
            @Override public void onSuccess(String original, String translatedByYoudao, String engineName) {
                cloudRequestBusy = false;
                String text = original == null ? "" : original.trim();
                if (text.isEmpty()) return;
                setDiagSpeech("ASR：有道云 · " + preview(text));
                if (showOriginal && originalText != null) originalText.setText("原文：" + text);
                queueSpeechTranslation(text, true);
            }
            @Override public void onError(String message) {
                cloudRequestBusy = false;
                setDiagSpeech("ASR：有道云失败 · " + message);
            }
        });
    }

    private void startSystemRecognizer() {
        if (!running || !INPUT_MICROPHONE.equals(inputMode)) {
            setDiagSpeech("ASR：系统识别器要求麦克风来源");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (ASR_AUTO.equals(asrMode) && canUseYoudaoSpeech()) {
                activeAsrMode = ASR_YOUDAO;
                cloudSpeechMode = true;
                setDiagSpeech("ASR：系统不可用 → 有道云");
                startRawAudioCapture();
            } else setDiagSpeech("ASR：系统 SpeechRecognizer 不可用");
            return;
        }
        main.post(() -> {
            if (!running || paused) return;
            destroySystemRecognizer();
            try {
                systemRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                systemRecognizer.setRecognitionListener(this);
                Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
                if (SherpaSpeechEngine.LANG_SINGLE.equals(languageMode)) {
                    listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage);
                }
                setDiagSpeech("ASR：系统 SpeechRecognizer · 麦克风 · " + languageModeLabel());
                systemRecognizer.startListening(listen);
            } catch (Exception e) {
                setDiagSpeech("ASR：系统启动失败 · " + safe(e));
                if (ASR_AUTO.equals(asrMode) && canUseYoudaoSpeech()) {
                    activeAsrMode = ASR_YOUDAO;
                    cloudSpeechMode = true;
                    startRawAudioCapture();
                }
            }
        });
    }

    private void startOcr() {
        if (!enableOcr || projection == null) return;
        ocrCapture = new OcrCapture(this, sourceMlTag, new OcrCapture.Callback() {
            @Override public void onFrame(int width, int height) {
                if (!uiVisible) setDiagScreen("OCR 正常 " + width + "×" + height);
            }
            @Override public void onText(String text) {
                if (!uiVisible) main.post(() -> handleOcrText(text));
            }
            @Override public void onError(Exception error) { setDiagScreen("OCR 错误：" + safe(error)); }
        });
        ocrCapture.start(projection);
        ocrCapture.setPaused(uiVisible);
    }

    private void handleOcrText(String text) {
        if (!running || paused || uiVisible || !enableOcr || text == null) return;
        String cleaned = sanitizeOcr(text);
        if (cleaned.isEmpty() || cleaned.equals(lastOcrText)) return;
        if (!looksLikeSourceLanguage(cleaned, sourceMlTag)) {
            setDiagScreen("OCR 忽略非目标语言界面文字");
            return;
        }
        if (ocrBusy) { pendingOcrText = cleaned; return; }
        lastOcrText = cleaned;
        ocrBusy = true;
        setDiagScreen("OCR 识别到目标语言文字");
        if (showOriginal && ocrOriginalText != null) ocrOriginalText.setText("屏幕原文：" + cleaned);
        translator.translate(cleaned, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                ocrBusy = false;
                if (ocrTranslatedText != null) ocrTranslatedText.setText("屏幕译文：" + translated);
                setDiagTranslation(engineName);
                saveHistory(cleaned, translated);
                consumePendingOcr();
            }
            @Override public void onError(String message) {
                ocrBusy = false;
                setDiagScreen("OCR 翻译失败：" + message);
                consumePendingOcr();
            }
        });
    }

    private void consumePendingOcr() {
        if (pendingOcrText.isEmpty()) return;
        String next = pendingOcrText;
        pendingOcrText = "";
        main.postDelayed(() -> handleOcrText(next), 300);
    }

    private String sanitizeOcr(String text) {
        String[] lines = text.replace('\u0000', ' ').split("\\n");
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("翻译：") || line.startsWith("声音：") || line.startsWith("ASR：")
                || line.startsWith("屏幕：") || line.startsWith("原文：") || line.startsWith("译文：")
                || line.startsWith("屏幕原文：") || line.startsWith("屏幕译文：")
                || line.equals("暂停") || line.equals("继续") || line.equals("关闭")) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
            if (out.length() >= 320) break;
        }
        String value = out.toString().trim();
        return value.length() > 320 ? value.substring(0, 320) : value;
    }

    private boolean looksLikeSourceLanguage(String text, String language) {
        int han = 0, kana = 0, hangul = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u31f0' && c <= '\u31ff')) kana++;
            else if (c >= '\u4e00' && c <= '\u9fff') han++;
            else if (c >= '\uac00' && c <= '\ud7af') hangul++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '\u00c0' && c <= '\u024f')) latin++;
        }
        switch (language) {
            case "ja": return kana >= 2 || (!SherpaSpeechEngine.LANG_SINGLE.equals(languageMode) && latin >= 4);
            case "zh": return (han >= 2 && kana == 0) || (!SherpaSpeechEngine.LANG_SINGLE.equals(languageMode) && latin >= 4);
            case "ko": return hangul >= 2 || (!SherpaSpeechEngine.LANG_SINGLE.equals(languageMode) && latin >= 4);
            case "en": return latin >= 4 && kana == 0 && hangul == 0;
            case "vi": case "fr": case "de": case "es": case "pt": case "it": case "nl":
                return latin >= 4 && kana == 0 && hangul == 0;
            default: return text.length() >= 3;
        }
    }

    private int shortsToBytes(short[] shorts, int count, byte[] bytes) {
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int v = shorts[i];
            int a = Math.abs(v);
            if (a > peak) peak = a;
            bytes[i * 2] = (byte) (v & 0xff);
            bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return peak;
    }

    private void updateAudioLevel(int peak, long now) {
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        String v;
        if (peak < AUDIO_PRESENT_PEAK) v = fixedSourceLabel() + "：▁ 近乎静音";
        else if (peak < 500) v = fixedSourceLabel() + "：▂ 有信号";
        else if (peak < 2500) v = fixedSourceLabel() + "：▃▅ 有声音";
        else if (peak < 9000) v = fixedSourceLabel() + "：▃▅▇ 正常";
        else v = fixedSourceLabel() + "：▃▅▇█ 较强";
        setDiagAudio(v);
    }

    private void createOverlay(int fontSize) {
        removeOverlay();
        windowManager = getSystemService(WindowManager.class);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(10));
        box.setBackgroundResource(R.drawable.panel);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        pauseControl = overlayControl("暂停");
        TextView close = overlayControl("关闭");
        pauseControl.setOnClickListener(v -> togglePause());
        close.setOnClickListener(v -> stopEverything());
        controls.addView(pauseControl, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        diagnosticsText = overlayText(11, Color.rgb(200, 180, 255));
        diagnosticsText.setMaxLines(5);
        diagnosticsText.setGravity(Gravity.START);
        diagnosticsText.setVisibility(showDiagnostics ? View.VISIBLE : View.GONE);
        box.addView(diagnosticsText, new LinearLayout.LayoutParams(-1, -2));

        originalText = overlayText(Math.max(13, fontSize - 5), Color.rgb(220, 215, 235));
        originalText.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        translatedText = overlayText(fontSize, Color.WHITE);
        translatedText.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(originalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translatedText, new LinearLayout.LayoutParams(-1, -2));

        ocrOriginalText = overlayText(Math.max(12, fontSize - 6), Color.rgb(205, 215, 235));
        ocrTranslatedText = overlayText(Math.max(14, fontSize - 2), Color.WHITE);
        ocrTranslatedText.setTypeface(null, android.graphics.Typeface.BOLD);
        ocrOriginalText.setVisibility(enableOcr && showOriginal ? View.VISIBLE : View.GONE);
        ocrTranslatedText.setVisibility(enableOcr ? View.VISIBLE : View.GONE);
        box.addView(ocrOriginalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(ocrTranslatedText, new LinearLayout.LayoutParams(-1, -2));

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        overlayParams = new WindowManager.LayoutParams(
            getResources().getDisplayMetrics().widthPixels - dp(24), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.y = dp(110);
        overlay = box;
        makeDraggable(box);
        windowManager.addView(overlay, overlayParams);
        renderDiagnostics();
    }

    private TextView overlayText(int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(5);
        t.setText(" ");
        return t;
    }

    private TextView overlayControl(String value) {
        TextView t = overlayText(13, Color.rgb(205, 185, 255));
        t.setText(value);
        t.setPadding(dp(8), dp(3), dp(8), dp(5));
        return t;
    }

    private void makeDraggable(View view) {
        final float[] start = new float[2];
        final int[] originalY = new int[1];
        view.setOnTouchListener((v, event) -> {
            if (overlayParams == null || windowManager == null || overlay == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX(); start[1] = event.getRawY(); originalY[0] = overlayParams.y; return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.y = Math.max(0, originalY[0] + Math.round(start[1] - event.getRawY()));
                    try { windowManager.updateViewLayout(overlay, overlayParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP: return true;
                default: return false;
            }
        });
    }

    private void setUiVisible(boolean visible) {
        uiVisible = visible;
        main.post(() -> {
            if (overlay != null) overlay.setVisibility(visible ? View.GONE : View.VISIBLE);
            if (ocrCapture != null) ocrCapture.setPaused(visible || paused);
        });
    }

    private void togglePause() {
        paused = !paused;
        if (pauseControl != null) pauseControl.setText(paused ? "继续" : "暂停");
        if (ocrCapture != null) ocrCapture.setPaused(paused || uiVisible);
        if (paused) {
            FreeOnlineSpeechEngine online = onlineSpeech;
            if (online != null) online.flush();
            SherpaSpeechEngine s = sherpaSpeech;
            if (s != null) s.flush();
            setDiagSpeech("ASR：已暂停");
            showStatus("翻译已暂停");
        } else {
            setDiagSpeech("ASR：" + asrLabel(activeAsrMode) + " · 已恢复");
            showStatus("实时翻译已继续");
            if (ASR_SYSTEM.equals(activeAsrMode)) startSystemRecognizer();
        }
    }

    private void showStatus(String value) {
        main.post(() -> { if (translatedText != null) translatedText.setText(value); });
    }

    private void setDiagTranslation(String value) { diagTranslation = value; main.post(this::renderDiagnostics); }
    private void setDiagAudio(String value) { diagAudio = value; main.post(this::renderDiagnostics); }
    private void setDiagSpeech(String value) { diagSpeech = value; main.post(this::renderDiagnostics); }
    private void setDiagScreen(String value) { diagScreen = value; main.post(this::renderDiagnostics); }

    private void renderDiagnostics() {
        if (diagnosticsText == null) return;
        diagnosticsText.setVisibility(showDiagnostics ? View.VISIBLE : View.GONE);
        if (!showDiagnostics) return;
        diagnosticsText.setText("翻译：" + diagTranslation + "\n声音：" + diagAudio + "\n" + diagSpeech
            + "\n屏幕：" + diagScreen);
    }

    private void saveHistory(String original, String translated) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("last_original", original)
            .putString("last_translation", translated).apply();
    }

    private void stopRawRecognitionEngines(boolean stopAudio) {
        if (stopAudio) stopAudioCapture();
        closeOnlineSpeech();
        closeVosk();
        closeSherpa();
        cloudSpeechMode = false;
    }

    private void closeOnlineSpeech() {
        FreeOnlineSpeechEngine s = onlineSpeech;
        onlineSpeech = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void closeVosk() {
        OfflineSpeechEngine s = offlineSpeech;
        offlineSpeech = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void closeSherpa() {
        SherpaSpeechEngine s = sherpaSpeech;
        sherpaSpeech = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void closeMicEffects() {
        MicAudioEffects effects = micEffects;
        micEffects = null;
        if (effects != null) try { effects.close(); } catch (Exception ignored) {}
    }

    private void stopAudioCapture() {
        captureGeneration++;
        audioCaptureStarted = false;
        closeMicEffects();
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            try { record.release(); } catch (Exception ignored) {}
        }
    }

    private void destroySystemRecognizer() {
        SpeechRecognizer r = systemRecognizer;
        systemRecognizer = null;
        if (r != null) {
            try { r.cancel(); } catch (Exception ignored) {}
            try { r.destroy(); } catch (Exception ignored) {}
        }
    }

    private void stopPipelineOnly() {
        running = false;
        stopAudioCapture();
        destroySystemRecognizer();
        closeVosk();
        closeSherpa();
        if (ocrCapture != null) {
            try { ocrCapture.stop(); } catch (Exception ignored) {}
            ocrCapture = null;
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) try { p.stop(); } catch (Exception ignored) {}
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
            translator = null;
        }
        synchronized (cloudPcm) { cloudPcm.reset(); }
        cloudSpeechMode = false;
        cloudRequestBusy = false;
        audioCaptureStarted = false;
        ocrBusy = false;
        pendingOcrText = "";
        speechTranslationBusy = false;
        speechQueue.clear();
    }

    private void stopEverything() {
        stopPipelineOnly();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        removeOverlay();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        overlayParams = null;
        diagnosticsText = null;
        originalText = null;
        translatedText = null;
        ocrOriginalText = null;
        ocrTranslatedText = null;
        pauseControl = null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "实时翻译", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("浮译实时翻译状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        PendingIntent pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("浮译 " + BuildConfig.VERSION_NAME)
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(message));
    }

    @Override public void onDestroy() {
        stopPipelineOnly();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        removeOverlay();
        audioWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onReadyForSpeech(Bundle params) { setDiagSpeech("ASR：系统 SpeechRecognizer 已就绪"); }
    @Override public void onBeginningOfSpeech() { setDiagAudio("固定·麦克风外放：检测到语音"); }

    @Override public void onRmsChanged(float rmsdB) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        if (rmsdB < 1f) setDiagAudio("固定·麦克风外放：▁ 等待语音");
        else if (rmsdB < 5f) setDiagAudio("固定·麦克风外放：▂▃ 有声音");
        else if (rmsdB < 9f) setDiagAudio("固定·麦克风外放：▃▅▇ 正常");
        else setDiagAudio("固定·麦克风外放：▃▅▇█ 较强");
    }

    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { setDiagSpeech("ASR：系统识别 · 等待结果"); }

    @Override public void onError(int error) {
        if (!running || paused) return;
        String message = speechErrorName(error);
        setDiagSpeech("ASR：系统识别失败 · " + message);
        boolean transientError = error == SpeechRecognizer.ERROR_NO_MATCH
            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        if (!transientError && ASR_AUTO.equals(asrMode) && canUseYoudaoSpeech()) {
            destroySystemRecognizer();
            activeAsrMode = ASR_YOUDAO;
            cloudSpeechMode = true;
            setDiagSpeech("ASR：系统失败 → 有道云");
            startRawAudioCapture();
        } else main.postDelayed(this::startSystemRecognizer, transientError ? 450 : 900);
    }

    @Override public void onResults(Bundle results) {
        String text = bestText(results);
        if (!text.isEmpty()) {
            if (showOriginal && originalText != null) originalText.setText("原文：" + text);
            setDiagSpeech("ASR：系统最终 · " + preview(text));
            queueSpeechTranslation(text, true);
        }
        if (running && !paused) main.postDelayed(this::startSystemRecognizer, 250);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        String text = bestText(partialResults);
        if (text.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        setDiagSpeech("ASR：系统流式 · " + preview(text));
        if (showOriginal && originalText != null) originalText.setText("原文：" + text);
        if (text.length() >= 3 && now - lastStreamTranslateAt >= STREAM_TRANSLATE_INTERVAL_MS
            && !text.equals(lastStreamTranslated)) {
            lastStreamTranslateAt = now;
            lastStreamTranslated = text;
            queueSpeechTranslation(text, false);
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    private String bestText(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() ? "" : values.get(0).trim();
    }

    private String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "缺少权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "未识别到文字";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "等待语音超时";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "语言不支持";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "语言不可用";
            default: return "错误码 " + error;
        }
    }

    private String preview(String text) {
        String line = text.replace('\n', ' ').trim();
        return line.length() > 38 ? line.substring(0, 38) + "…" : line;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
