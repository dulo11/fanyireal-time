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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FloatingTranslator 0.4.3 pipeline.
 *
 * Audio source is always fixed by the user. The service never changes system-playback
 * capture to microphone on its own.
 *
 * ASR modes:
 *   AUTO   : Vosk offline -> Android SpeechRecognizer (microphone only) -> Youdao cloud
 *   VOSK   : Vosk only
 *   SYSTEM : Android SpeechRecognizer only
 *   YOUDAO : Youdao cloud speech only
 *
 * Vosk partial hypotheses are translated periodically, so fast/continuous speech no
 * longer has to wait for a long silence before any translation appears.
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
    public static final String ASR_SYSTEM = "system";
    public static final String ASR_YOUDAO = "youdao";
    public static final String EXTRA_SOURCE_SPEECH = "source_speech";
    public static final String EXTRA_SOURCE_MLKIT = "source_mlkit";
    public static final String EXTRA_TARGET_MLKIT = "target_mlkit";
    public static final String EXTRA_ENGINE_ID = "engine_id";
    public static final String EXTRA_YOUDAO_SPEECH_FALLBACK = "youdao_speech_fallback";
    public static final String EXTRA_SHOW_ORIGINAL = "show_original";
    public static final String EXTRA_PREFER_OFFLINE = "prefer_offline";
    public static final String EXTRA_FONT_SIZE = "font_size";
    public static final String EXTRA_ENABLE_OCR = "enable_ocr";
    public static final String EXTRA_AUTO_MIC_FALLBACK = "auto_mic_fallback";

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

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private OfflineSpeechEngine offlineSpeech;
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
    private String speechLanguage = "en-US";
    private String sourceMlTag = "en";
    private String targetMlTag = "zh";
    private String engineId = TranslationRouter.AUTO;
    private boolean showOriginal;
    private boolean enableOcr;
    private boolean allowYoudaoSpeech;
    private boolean preferOffline;

    private long lastSpeechTextAt;
    private long lastLevelUiAt;
    private long cloudChunkStartedAt;
    private long lastStreamTranslateAt;
    private final ByteArrayOutputStream cloudPcm = new ByteArrayOutputStream();

    private boolean ocrBusy;
    private String lastOcrText = "";
    private String pendingOcrText = "";
    private String lastSpeechText = "";
    private String lastStreamTranslated = "";

    private boolean speechTranslationBusy;
    private String pendingSpeechText = "";
    private boolean pendingSpeechFinal;

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
            setUiVisible(true);
            return START_NOT_STICKY;
        }
        if (ACTION_UI_HIDDEN.equals(action)) {
            setUiVisible(false);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        stopPipelineOnly();

        inputMode = intent.getStringExtra(EXTRA_INPUT_MODE);
        if (!INPUT_MICROPHONE.equals(inputMode)) inputMode = INPUT_PLAYBACK;
        asrMode = normalizeAsr(intent.getStringExtra(EXTRA_ASR_MODE));
        speechLanguage = value(intent.getStringExtra(EXTRA_SOURCE_SPEECH), "en-US");
        sourceMlTag = value(intent.getStringExtra(EXTRA_SOURCE_MLKIT), "en");
        targetMlTag = value(intent.getStringExtra(EXTRA_TARGET_MLKIT), "zh");
        engineId = value(intent.getStringExtra(EXTRA_ENGINE_ID), TranslationRouter.AUTO);
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, false);
        allowYoudaoSpeech = intent.getBooleanExtra(EXTRA_YOUDAO_SPEECH_FALLBACK, true);
        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, true);
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
        lastSpeechText = "";
        lastStreamTranslated = "";
        lastOcrText = "";
        pendingOcrText = "";
        speechTranslationBusy = false;
        pendingSpeechText = "";
        pendingSpeechFinal = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", true).apply();

        createOverlay(fontSize);
        translator = new OfflineFirstTranslationRouter(this, sourceMlTag, targetMlTag, engineId);
        setDiagTranslation(translator.selectedEngineName());
        setDiagAudio(fixedSourceLabel() + " · 等待声音");
        setDiagSpeech("ASR：" + asrLabel(asrMode) + " · 准备中");
        setDiagScreen(enableOcr ? "等待屏幕捕获" : "OCR 已关闭");
        showStatus("固定声音来源：" + fixedSourceLabel() + "\n正在准备 " + asrLabel(asrMode));

        if (projectionNeeded) startProjection(resultCode, resultData);
        else prepareSpeechEngine();
        return START_NOT_STICKY;
    }

    private String normalizeAsr(String mode) {
        if (ASR_VOSK.equals(mode) || ASR_SYSTEM.equals(mode) || ASR_YOUDAO.equals(mode)) return mode;
        return ASR_AUTO;
    }

    private String asrLabel(String mode) {
        if (ASR_VOSK.equals(mode)) return "Vosk 内置离线 ASR";
        if (ASR_SYSTEM.equals(mode)) return "Android 系统 SpeechRecognizer";
        if (ASR_YOUDAO.equals(mode)) return "有道云语音 ASR";
        return "自动 ASR（Vosk→系统→有道）";
    }

    private String fixedSourceLabel() {
        return INPUT_PLAYBACK.equals(inputMode) ? "固定·系统内部声音" : "固定·麦克风外放";
    }

    private void startProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("MediaProjection 返回空对象");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(() -> {
                        if (running) {
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
            case ASR_VOSK:
                startVosk(false);
                break;
            case ASR_SYSTEM:
                startSystemFixed();
                break;
            case ASR_YOUDAO:
                startYoudaoFixed();
                break;
            case ASR_AUTO:
            default:
                if (OfflineSpeechEngine.supports(sourceMlTag)) startVosk(true);
                else fallbackFromVosk("该语言暂无 Vosk 离线模型");
                break;
        }
    }

    private void startVosk(boolean allowFallback) {
        if (!OfflineSpeechEngine.supports(sourceMlTag)) {
            if (allowFallback) fallbackFromVosk("该语言暂无 Vosk 离线模型");
            else {
                setDiagSpeech("Vosk 不支持当前语言");
                showStatus("Vosk 暂不支持当前语言，请在 ASR 设置选择其他方式");
            }
            return;
        }
        setDiagSpeech("ASR：Vosk 离线模型准备中");
        offlineSpeech = new OfflineSpeechEngine(this, sourceMlTag, new OfflineSpeechEngine.Callback() {
            @Override public void onStatus(String message) {
                setDiagSpeech("ASR：" + message);
                showStatus(message);
                updateNotification(message);
            }

            @Override public void onReady(String engineName) {
                if (!running) return;
                setDiagSpeech("ASR：" + engineName + " · " + sourceMlTag + " · 流式");
                showStatus("Vosk 离线 ASR 已就绪，开始流式识别");
                startRawAudioCapture();
            }

            @Override public void onPartial(String text) {
                onOfflineSpeechText(text, false);
            }

            @Override public void onFinal(String text) {
                onOfflineSpeechText(text, true);
            }

            @Override public void onError(String message) {
                if (!running) return;
                setDiagSpeech("ASR：Vosk 失败 · " + message);
                if (allowFallback) fallbackFromVosk(message);
                else showStatus("Vosk ASR 失败：" + message);
            }
        });
        offlineSpeech.prepare();
    }

    private void fallbackFromVosk(String reason) {
        if (!running || !ASR_AUTO.equals(asrMode)) return;
        if (INPUT_MICROPHONE.equals(inputMode) && SpeechRecognizer.isRecognitionAvailable(this)) {
            setDiagSpeech("ASR：Vosk 不可用 → 系统 SpeechRecognizer");
            showStatus(reason + "\n声音来源保持麦克风，改用系统 SpeechRecognizer");
            startSystemRecognizer();
            return;
        }
        if (canUseYoudaoSpeech()) {
            cloudSpeechMode = true;
            setDiagSpeech("ASR：Vosk 不可用 → 有道云语音");
            showStatus(reason + "\n声音来源保持不变，改用有道云 ASR");
            startRawAudioCapture();
            return;
        }
        if (INPUT_PLAYBACK.equals(inputMode) && SpeechRecognizer.isRecognitionAvailable(this)) {
            setDiagSpeech("ASR：系统识别器仅支持麦克风");
            showStatus(reason + "\n当前声音固定为系统内部声音，系统 SpeechRecognizer 无法直接读取它。请手动改为麦克风，或配置有道云 ASR。");
            return;
        }
        setDiagSpeech("ASR：无可用备用识别器");
        showStatus(reason + "\n没有可用的备用 ASR");
    }

    private void startSystemFixed() {
        if (!INPUT_MICROPHONE.equals(inputMode)) {
            setDiagSpeech("ASR：系统 SpeechRecognizer 需要麦克风");
            showStatus("你固定选择了系统内部声音，但 Android SpeechRecognizer 只能稳定读取麦克风。\n请把声音来源改成“麦克风识别手机外放”，或者 ASR 改为 Vosk/有道。");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setDiagSpeech("ASR：系统 SpeechRecognizer 不可用");
            showStatus("系统没有可用的 SpeechRecognizer 服务，请选择 Vosk 离线 ASR");
            return;
        }
        startSystemRecognizer();
    }

    private void startYoudaoFixed() {
        if (!canUseYoudaoSpeech()) {
            setDiagSpeech("ASR：有道云未配置 Key");
            showStatus("有道云 ASR 需要 AppKey + AppSecret，请到 API 备用设置填写");
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
            int min = Math.max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                SAMPLE_RATE * 2
            );

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
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(min * 2)
                    .build();
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                try { record.release(); } catch (Exception ignored) {}
                throw new IllegalStateException("AudioRecord 初始化失败");
            }

            audioRecord = record;
            audioCaptureStarted = true;
            int generation = ++captureGeneration;
            record.startRecording();
            setDiagAudio(fixedSourceLabel() + " · 采集已启动");
            updateNotification(fixedSourceLabel() + " · " + asrLabel(asrMode));
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

            OfflineSpeechEngine speech = offlineSpeech;
            if (speech != null && speech.isReady()) {
                speech.acceptPcm(bytes, count * 2);
            } else if (cloudSpeechMode) {
                appendCloudAudio(bytes, count * 2, peak, now);
            }
        }
    }

    private void onOfflineSpeechText(String text, boolean committed) {
        if (!running || paused || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        lastSpeechTextAt = now;
        setDiagSpeech("ASR：Vosk " + (committed ? "最终" : "流式") + " · " + preview(cleaned));
        if (showOriginal && originalText != null) originalText.setText("原文：" + cleaned);

        if (committed) {
            lastSpeechText = cleaned;
            lastStreamTranslated = "";
            queueSpeechTranslation(cleaned, true);
            return;
        }

        // Continuous/fast speech may not produce a Vosk endpoint for a long time.
        // Translate the latest partial every ~1.2 s instead of waiting for silence.
        if (cleaned.length() < 3) return;
        if (cleaned.equals(lastStreamTranslated)) return;
        if (now - lastStreamTranslateAt < STREAM_TRANSLATE_INTERVAL_MS) return;
        lastStreamTranslateAt = now;
        lastStreamTranslated = cleaned;
        queueSpeechTranslation(cleaned, false);
    }

    private void queueSpeechTranslation(String text, boolean finalResult) {
        if (!running || text == null || text.trim().isEmpty()) return;
        String cleaned = text.trim();
        if (speechTranslationBusy) {
            if (finalResult) {
                pendingSpeechText = cleaned;
                pendingSpeechFinal = true;
            } else if (!pendingSpeechFinal) {
                pendingSpeechText = cleaned;
            }
            return;
        }
        speechTranslationBusy = true;
        translateSpeechSegmented(cleaned, finalResult);
    }

    private void translateSpeechSegmented(String text, boolean finalResult) {
        List<String> parts = splitForTranslation(text, TRANSLATE_CHUNK_CHARS);
        translatePart(parts, 0, new StringBuilder(), text, finalResult);
    }

    private void translatePart(List<String> parts, int index, StringBuilder out,
                               String original, boolean finalResult) {
        if (!running) {
            finishSpeechTranslation();
            return;
        }
        if (index >= parts.size()) {
            String translated = out.toString().trim();
            setDiagTranslation((finalResult ? "最终" : "流式") + " · ML Kit 离线优先");
            if (translatedText != null) translatedText.setText("译文：" + translated);
            saveHistory(original, translated);
            finishSpeechTranslation();
            return;
        }

        OfflineFirstTranslationRouter current = translator;
        if (current == null) {
            finishSpeechTranslation();
            return;
        }
        if (index == 0) setDiagTranslation("翻译中 · " + (finalResult ? "完整句" : "流式片段"));
        String part = parts.get(index);
        current.translate(part, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (out.length() > 0) out.append('\n');
                out.append(translated);
                setDiagTranslation((finalResult ? "最终" : "流式") + " · " + engineName
                    + (parts.size() > 1 ? " · 分段 " + (index + 1) + "/" + parts.size() : ""));
                translatePart(parts, index + 1, out, original, finalResult);
            }

            @Override public void onError(String message) {
                setDiagTranslation("翻译失败：" + message);
                if (translatedText != null) translatedText.setText("翻译失败：" + message);
                finishSpeechTranslation();
            }
        });
    }

    private void finishSpeechTranslation() {
        speechTranslationBusy = false;
        if (!pendingSpeechText.isEmpty()) {
            String next = pendingSpeechText;
            boolean nextFinal = pendingSpeechFinal;
            pendingSpeechText = "";
            pendingSpeechFinal = false;
            main.post(() -> queueSpeechTranslation(next, nextFinal));
        }
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
                || c == '、' || c == '，' || c == ',' || Character.isWhitespace(c)) {
                return i;
            }
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
                lastSpeechTextAt = SystemClock.elapsedRealtime();
                setDiagSpeech("ASR：有道云 · " + preview(text));
                if (showOriginal && originalText != null) originalText.setText("原文：" + text);
                // ASR may be cloud, but text translation still follows the app's ML Kit-first router.
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
                cloudSpeechMode = true;
                setDiagSpeech("ASR：系统不可用 → 有道云");
                startRawAudioCapture();
            } else {
                setDiagSpeech("ASR：系统 SpeechRecognizer 不可用");
            }
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
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
                setDiagSpeech("ASR：系统 SpeechRecognizer · 麦克风");
                systemRecognizer.startListening(listen);
            } catch (Exception e) {
                setDiagSpeech("ASR：系统启动失败 · " + safe(e));
                if (ASR_AUTO.equals(asrMode) && canUseYoudaoSpeech()) {
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
            @Override public void onError(Exception error) {
                setDiagScreen("OCR 错误：" + safe(error));
            }
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
        if (ocrBusy) {
            pendingOcrText = cleaned;
            return;
        }
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
            if (line.startsWith("诊断") || line.startsWith("原文：") || line.startsWith("译文：")
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
            case "ja": return kana >= 2;
            case "zh": return han >= 2 && kana == 0;
            case "ko": return hangul >= 2;
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
        String value;
        if (peak < AUDIO_PRESENT_PEAK) value = fixedSourceLabel() + "：▁ 近乎静音";
        else if (peak < 500) value = fixedSourceLabel() + "：▂ 有信号";
        else if (peak < 2500) value = fixedSourceLabel() + "：▃▅ 有声音";
        else if (peak < 9000) value = fixedSourceLabel() + "：▃▅▇ 正常";
        else value = fixedSourceLabel() + "：▃▅▇█ 较强";
        setDiagAudio(value);
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
        box.addView(diagnosticsText, new LinearLayout.LayoutParams(-1, -2));

        originalText = overlayText(Math.max(13, fontSize - 5), Color.rgb(220, 215, 235));
        originalText.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        translatedText = overlayText(fontSize, Color.WHITE);
        translatedText.setTypeface(null, 1);
        box.addView(originalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translatedText, new LinearLayout.LayoutParams(-1, -2));

        ocrOriginalText = overlayText(Math.max(12, fontSize - 6), Color.rgb(205, 215, 235));
        ocrTranslatedText = overlayText(Math.max(14, fontSize - 2), Color.WHITE);
        ocrTranslatedText.setTypeface(null, 1);
        ocrOriginalText.setVisibility(enableOcr && showOriginal ? View.VISIBLE : View.GONE);
        ocrTranslatedText.setVisibility(enableOcr ? View.VISIBLE : View.GONE);
        box.addView(ocrOriginalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(ocrTranslatedText, new LinearLayout.LayoutParams(-1, -2));

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        overlayParams = new WindowManager.LayoutParams(
            getResources().getDisplayMetrics().widthPixels - dp(24),
            -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        );
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
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    originalY[0] = overlayParams.y;
                    return true;
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
            setDiagSpeech("ASR：已暂停");
            showStatus("翻译已暂停");
        } else {
            setDiagSpeech("ASR：" + asrLabel(asrMode) + " · 已恢复");
            showStatus("实时翻译已继续");
            if (ASR_SYSTEM.equals(asrMode) || (ASR_AUTO.equals(asrMode) && systemRecognizer != null)) {
                startSystemRecognizer();
            }
        }
    }

    private void showStatus(String value) {
        main.post(() -> {
            if (translatedText != null) translatedText.setText(value);
        });
    }

    private void setDiagTranslation(String value) {
        diagTranslation = value;
        main.post(this::renderDiagnostics);
    }
    private void setDiagAudio(String value) {
        diagAudio = value;
        main.post(this::renderDiagnostics);
    }
    private void setDiagSpeech(String value) {
        diagSpeech = value;
        main.post(this::renderDiagnostics);
    }
    private void setDiagScreen(String value) {
        diagScreen = value;
        main.post(this::renderDiagnostics);
    }

    private void renderDiagnostics() {
        if (diagnosticsText == null) return;
        diagnosticsText.setText(
            "翻译：" + diagTranslation
                + "\n声音：" + diagAudio
                + "\n" + diagSpeech
                + "\n屏幕：" + diagScreen
        );
    }

    private void saveHistory(String original, String translated) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("last_original", original)
            .putString("last_translation", translated)
            .apply();
    }

    private void stopAudioCapture() {
        captureGeneration++;
        audioCaptureStarted = false;
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
        if (offlineSpeech != null) {
            try { offlineSpeech.close(); } catch (Exception ignored) {}
            offlineSpeech = null;
        }
        if (ocrCapture != null) {
            try { ocrCapture.stop(); } catch (Exception ignored) {}
            ocrCapture = null;
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
        }
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
            translator = null;
        }
        synchronized (cloudPcm) { cloudPcm.reset(); }
        cloudRequestBusy = false;
        cloudSpeechMode = false;
        speechTranslationBusy = false;
        pendingSpeechText = "";
        pendingSpeechFinal = false;
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
        PendingIntent pending = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("浮译 0.4.3")
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

    @Override public void onReadyForSpeech(Bundle params) {
        setDiagSpeech("ASR：系统 SpeechRecognizer 已就绪");
    }

    @Override public void onBeginningOfSpeech() {
        setDiagAudio("固定·麦克风外放：检测到语音");
    }

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
            cloudSpeechMode = true;
            setDiagSpeech("ASR：系统失败 → 有道云");
            startRawAudioCapture();
        } else {
            main.postDelayed(this::startSystemRecognizer, transientError ? 450 : 900);
        }
    }

    @Override public void onResults(Bundle results) {
        String text = bestText(results);
        if (!text.isEmpty()) {
            if (showOriginal && originalText != null) originalText.setText("原文：" + text);
            lastSpeechTextAt = SystemClock.elapsedRealtime();
            setDiagSpeech("ASR：系统最终 · " + preview(text));
            queueSpeechTranslation(text, true);
        }
        if (running && !paused) main.postDelayed(this::startSystemRecognizer, 250);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        String text = bestText(partialResults);
        if (text.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        lastSpeechTextAt = now;
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
