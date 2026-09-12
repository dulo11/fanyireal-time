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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FloatingTranslator 0.4.1 pipeline.
 *
 * Speech priority:
 *   1) Vosk on-device ASR (download once, then fully offline)
 *   2) Android SpeechRecognizer when available
 *   3) optional Youdao cloud speech fallback
 *
 * Translation priority in AUTO:
 *   1) ML Kit on-device translation
 *   2) configured cloud engines only if ML Kit fails
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
    private static final long LEVEL_UPDATE_MS = 900L;
    private static final long PLAYBACK_SILENCE_FALLBACK_MS = 8000L;
    private static final long PLAYBACK_NO_TEXT_FALLBACK_MS = 12000L;
    private static final long CLOUD_CHUNK_MS = 4200L;

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
    private String speechLanguage = "en-US";
    private String sourceMlTag = "en";
    private String targetMlTag = "zh";
    private String engineId = TranslationRouter.AUTO;
    private boolean showOriginal;
    private boolean enableOcr;
    private boolean autoMicFallback;
    private boolean allowYoudaoSpeech;
    private boolean preferOffline;
    private boolean micFallbackActivated;

    private long playbackStartedAt;
    private long lastAudibleAt;
    private long lastSpeechTextAt;
    private long lastLevelUiAt;
    private long cloudChunkStartedAt;
    private final ByteArrayOutputStream cloudPcm = new ByteArrayOutputStream();

    private boolean ocrBusy;
    private String lastOcrText = "";
    private String pendingOcrText = "";
    private String lastSpeechText = "";

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
        speechLanguage = value(intent.getStringExtra(EXTRA_SOURCE_SPEECH), "en-US");
        sourceMlTag = value(intent.getStringExtra(EXTRA_SOURCE_MLKIT), "en");
        targetMlTag = value(intent.getStringExtra(EXTRA_TARGET_MLKIT), "zh");
        engineId = value(intent.getStringExtra(EXTRA_ENGINE_ID), TranslationRouter.AUTO);
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, false);
        autoMicFallback = intent.getBooleanExtra(EXTRA_AUTO_MIC_FALLBACK, true);
        allowYoudaoSpeech = intent.getBooleanExtra(EXTRA_YOUDAO_SPEECH_FALLBACK, true);
        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, true);
        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);

        boolean projectionNeeded = INPUT_PLAYBACK.equals(inputMode) || enableOcr;
        int foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (projectionNeeded) foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(NOTIFICATION_ID, buildNotification("正在准备离线实时翻译"), foregroundType);

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
        micFallbackActivated = false;
        lastSpeechText = "";
        lastOcrText = "";
        pendingOcrText = "";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", true).apply();

        createOverlay(fontSize);
        translator = new OfflineFirstTranslationRouter(this, sourceMlTag, targetMlTag, engineId);
        setDiagTranslation(translator.selectedEngineName());
        setDiagAudio(INPUT_PLAYBACK.equals(inputMode) ? "等待系统声音" : "等待麦克风");
        setDiagSpeech("准备离线语音识别");
        setDiagScreen(enableOcr ? "等待屏幕捕获" : "OCR 已关闭");
        showStatus("正在准备离线语音识别……");

        if (projectionNeeded) {
            startProjection(resultCode, resultData);
        } else {
            prepareSpeechEngine();
        }
        return START_NOT_STICKY;
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
        if (OfflineSpeechEngine.supports(sourceMlTag)) {
            setDiagSpeech("Vosk 离线模型准备中");
            offlineSpeech = new OfflineSpeechEngine(this, sourceMlTag, new OfflineSpeechEngine.Callback() {
                @Override public void onStatus(String message) {
                    setDiagSpeech(message);
                    showStatus(message);
                    updateNotification(message);
                }

                @Override public void onReady(String engineName) {
                    if (!running) return;
                    setDiagSpeech(engineName + " 已就绪 · " + sourceMlTag);
                    showStatus("离线语音识别已就绪，正在监听……");
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
                    setDiagSpeech(message);
                    fallbackAfterOfflineSpeechFailure(message);
                }
            });
            offlineSpeech.prepare();
            return;
        }

        setDiagSpeech("该语言暂无 Vosk 离线模型");
        fallbackAfterOfflineSpeechFailure("该语言暂无内置离线语音模型");
    }

    private void fallbackAfterOfflineSpeechFailure(String reason) {
        if (!running || audioCaptureStarted) return;
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            if (INPUT_PLAYBACK.equals(inputMode)) {
                if (autoMicFallback) {
                    inputMode = INPUT_MICROPHONE;
                    micFallbackActivated = true;
                    setDiagAudio("离线模型不可用，改用麦克风");
                    showStatus(reason + "\n已改用系统麦克风语音识别");
                    startSystemRecognizer();
                } else {
                    showStatus(reason + "\n系统识别器不能稳定接收内部音频，请切麦克风模式");
                }
            } else {
                startSystemRecognizer();
            }
            return;
        }

        if (canUseYoudaoSpeech()) {
            cloudSpeechMode = true;
            setDiagSpeech("有道云语音兜底");
            showStatus(reason + "\n离线模型不可用，已启用有道云语音兜底");
            startRawAudioCapture();
            return;
        }

        setDiagSpeech("无可用语音识别器");
        showStatus(reason + "\n当前语言没有可用离线模型，系统也没有语音识别服务");
    }

    private boolean canUseYoudaoSpeech() {
        return allowYoudaoSpeech && translator != null && translator.hasYoudaoCredentials();
    }

    private void startRawAudioCapture() {
        if (!running || audioCaptureStarted) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setDiagAudio("缺少录音权限");
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
                playbackStartedAt = SystemClock.elapsedRealtime();
                lastAudibleAt = playbackStartedAt;
                lastSpeechTextAt = playbackStartedAt;
                setDiagAudio("系统声音采集启动中");
            } else {
                record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(min * 2)
                    .build();
                setDiagAudio("麦克风采集启动中");
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                try { record.release(); } catch (Exception ignored) {}
                throw new IllegalStateException("AudioRecord 初始化失败");
            }

            audioRecord = record;
            audioCaptureStarted = true;
            int generation = ++captureGeneration;
            record.startRecording();
            updateNotification(INPUT_PLAYBACK.equals(inputMode)
                ? "离线识别系统声音中"
                : "离线识别麦克风中");
            audioWorker.execute(() -> audioLoop(record, generation));
        } catch (Exception e) {
            audioCaptureStarted = false;
            setDiagAudio("声音采集失败：" + safe(e));
            if (INPUT_PLAYBACK.equals(inputMode) && autoMicFallback) {
                main.postDelayed(() -> switchToMicrophone("系统声音捕获启动失败"), 250);
            }
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
                main.post(() -> setDiagAudio("读取声音失败：" + safe(e)));
                break;
            }
            if (count <= 0) continue;
            if (paused) continue;

            int peak = shortsToBytes(shorts, count, bytes);
            long now = SystemClock.elapsedRealtime();
            updateAudioLevel(peak, now);

            if (INPUT_PLAYBACK.equals(inputMode)) {
                if (peak >= AUDIO_PRESENT_PEAK) lastAudibleAt = now;
                if (autoMicFallback && !micFallbackActivated
                    && now - playbackStartedAt >= PLAYBACK_SILENCE_FALLBACK_MS
                    && now - lastAudibleAt >= PLAYBACK_SILENCE_FALLBACK_MS) {
                    main.post(() -> switchToMicrophone("连续 8 秒没有捕获到系统声音"));
                    break;
                }
                if (autoMicFallback && !micFallbackActivated
                    && peak >= AUDIO_PRESENT_PEAK
                    && now - playbackStartedAt >= PLAYBACK_NO_TEXT_FALLBACK_MS
                    && now - lastSpeechTextAt >= PLAYBACK_NO_TEXT_FALLBACK_MS) {
                    main.post(() -> switchToMicrophone("系统声音有信号，但离线识别长时间没有文字"));
                    break;
                }
            }

            OfflineSpeechEngine speech = offlineSpeech;
            if (speech != null && speech.isReady()) {
                speech.acceptPcm(bytes, count * 2);
            } else if (cloudSpeechMode) {
                appendCloudAudio(bytes, count * 2, peak, now);
            }
        }
    }

    private void switchToMicrophone(String reason) {
        if (!running || INPUT_MICROPHONE.equals(inputMode)) return;
        micFallbackActivated = true;
        inputMode = INPUT_MICROPHONE;
        stopAudioCapture();
        setDiagAudio("已切换麦克风兜底");
        showStatus(reason + "\n已改用麦克风继续离线识别，请保持直播外放");
        main.postDelayed(this::startRawAudioCapture, 300);
    }

    private void onOfflineSpeechText(String text, boolean committed) {
        if (!running || paused || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        lastSpeechTextAt = SystemClock.elapsedRealtime();
        setDiagSpeech((committed ? "离线结果：" : "离线识别：") + preview(cleaned));
        if (showOriginal && originalText != null) {
            originalText.setText("原文：" + cleaned);
        }

        if (!committed) {
            // Partial results update the original text and diagnostics only.
            // Translation is done on endpoints to avoid hammering the translator and UI.
            return;
        }
        if (cleaned.equals(lastSpeechText)) return;
        lastSpeechText = cleaned;
        translateSpeech(cleaned);
    }

    private void translateSpeech(String text) {
        OfflineFirstTranslationRouter current = translator;
        if (current == null) return;
        setDiagTranslation("翻译中 · ML Kit 离线优先");
        current.translate(text, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (!running) return;
                setDiagTranslation(engineName);
                if (translatedText != null) translatedText.setText("译文：" + translated);
                saveHistory(text, translated);
            }

            @Override public void onError(String message) {
                setDiagTranslation("翻译失败：" + message);
                if (translatedText != null) translatedText.setText("翻译失败：" + message);
            }
        });
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
        setDiagSpeech("有道云语音识别中……");
        current.translateYoudaoSpeech(pcm, new TranslationRouter.SpeechCallback() {
            @Override public void onSuccess(String original, String translated, String engineName) {
                cloudRequestBusy = false;
                lastSpeechTextAt = SystemClock.elapsedRealtime();
                setDiagSpeech("云语音：" + preview(original));
                setDiagTranslation(engineName);
                if (showOriginal && originalText != null) originalText.setText("原文：" + original);
                if (translatedText != null) translatedText.setText("译文：" + translated);
                saveHistory(original, translated);
            }

            @Override public void onError(String message) {
                cloudRequestBusy = false;
                setDiagSpeech("云语音失败：" + message);
            }
        });
    }

    private void startSystemRecognizer() {
        if (!running || !SpeechRecognizer.isRecognitionAvailable(this)) {
            fallbackAfterOfflineSpeechFailure("系统语音识别不可用");
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
                setDiagSpeech("系统识别器兜底 · 麦克风");
                systemRecognizer.startListening(listen);
            } catch (Exception e) {
                setDiagSpeech("系统识别器失败：" + safe(e));
                if (canUseYoudaoSpeech()) {
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
                if (!uiVisible) setDiagScreen("OCR 屏幕捕获正常 " + width + "×" + height);
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
                || line.equals("暂停") || line.equals("继续") || line.equals("关闭")) {
                continue;
            }
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
        String source = INPUT_PLAYBACK.equals(inputMode) ? "系统声音" : "麦克风";
        String value;
        if (peak < AUDIO_PRESENT_PEAK) value = source + "：▁ 近乎静音";
        else if (peak < 500) value = source + "：▂ 有信号";
        else if (peak < 2500) value = source + "：▃▅ 有声音";
        else if (peak < 9000) value = source + "：▃▅▇ 正常";
        else value = source + "：▃▅▇█ 较强";
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
        t.setMaxLines(4);
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
                case MotionEvent.ACTION_UP:
                    return true;
                default:
                    return false;
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
            setDiagSpeech("已暂停");
            showStatus("翻译已暂停");
        } else {
            setDiagSpeech(offlineSpeech != null && offlineSpeech.isReady()
                ? "Vosk 离线 ASR 已恢复"
                : "继续监听");
            showStatus("实时翻译已继续");
            if (systemRecognizer != null) startSystemRecognizer();
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
                + "\n语音：" + diagSpeech
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
        channel.setDescription("浮译离线实时翻译状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        PendingIntent pending = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("浮译 0.4.1")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(
            NOTIFICATION_ID, buildNotification(message));
    }

    @Override public void onDestroy() {
        stopPipelineOnly();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        removeOverlay();
        audioWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // Android SpeechRecognizer fallback callbacks.
    @Override public void onReadyForSpeech(Bundle params) {
        setDiagSpeech("系统语音识别兜底已就绪");
    }

    @Override public void onBeginningOfSpeech() {
        setDiagAudio("麦克风：检测到语音");
    }

    @Override public void onRmsChanged(float rmsdB) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        if (rmsdB < 1f) setDiagAudio("麦克风：▁ 等待语音");
        else if (rmsdB < 5f) setDiagAudio("麦克风：▂▃ 有声音");
        else if (rmsdB < 9f) setDiagAudio("麦克风：▃▅▇ 正常");
        else setDiagAudio("麦克风：▃▅▇█ 较强");
    }

    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {
        setDiagSpeech("系统识别：等待结果");
    }

    @Override public void onError(int error) {
        if (!running || paused) return;
        String message = speechErrorName(error);
        setDiagSpeech("系统识别失败：" + message);
        if (canUseYoudaoSpeech()) {
            destroySystemRecognizer();
            cloudSpeechMode = true;
            startRawAudioCapture();
        } else {
            main.postDelayed(this::startSystemRecognizer, 800);
        }
    }

    @Override public void onResults(Bundle results) {
        String text = bestText(results);
        if (!text.isEmpty()) {
            if (showOriginal && originalText != null) originalText.setText("原文：" + text);
            lastSpeechTextAt = SystemClock.elapsedRealtime();
            setDiagSpeech("系统识别：" + preview(text));
            translateSpeech(text);
        }
        if (running && !paused) main.postDelayed(this::startSystemRecognizer, 350);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        String text = bestText(partialResults);
        if (!text.isEmpty()) {
            lastSpeechTextAt = SystemClock.elapsedRealtime();
            setDiagSpeech("系统识别：" + preview(text));
            if (showOriginal && originalText != null) originalText.setText("原文：" + text);
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
        return line.length() > 32 ? line.substring(0, 32) + "…" : line;
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
