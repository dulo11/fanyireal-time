package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.ParcelFileDescriptor;
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
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final int NOTIFICATION_ID = 3401;
    private static final String CHANNEL_ID = "floating_translation";
    private static final String PREFS = "floating_translator";
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_PRESENT_PEAK = 64;
    private static final long SILENCE_FALLBACK_MS = 8000L;
    private static final long AUDIO_WITHOUT_TEXT_FALLBACK_MS = 10000L;
    private static final long LEVEL_UPDATE_MS = 1200L;
    private static final long CLOUD_CHUNK_MS = 4200L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object pipeLock = new Object();

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private SpeechRecognizer recognizer;
    private TranslationRouter router;
    private OcrCapture ocrCapture;
    private ParcelFileDescriptor readPipe;
    private ParcelFileDescriptor writePipe;
    private OutputStream speechOutput;
    private volatile boolean running;

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView originalText;
    private TextView translatedText;
    private TextView ocrOriginalText;
    private TextView ocrTranslatedText;
    private TextView diagnosticsText;
    private TextView pauseControl;

    private boolean showOriginal;
    private boolean preferOffline;
    private boolean enableOcr;
    private boolean autoMicFallback;
    private boolean allowYoudaoSpeech;
    private volatile boolean paused;
    private volatile boolean fallbackPosted;
    private boolean micFallbackActivated;
    private boolean cloudSpeechMode;
    private volatile boolean cloudSpeechRequestInFlight;
    private volatile boolean ocrTextSeen;
    private boolean ocrTranslationBusy;
    private String pendingOcrText = "";

    private String inputMode = INPUT_PLAYBACK;
    private String speechLanguage;
    private String sourceMlTag;
    private String targetMlTag;
    private String engineId;
    private String lastPartial = "";
    private String lastOcrText = "";
    private Runnable partialTranslation;
    private long playbackStartedAt;
    private long lastAudibleAt;
    private long lastSpeechTextAt;
    private long audibleWithoutTextStartedAt;
    private long lastLevelUiAt;
    private int playbackRecognizerErrors;

    private final ByteArrayOutputStream cloudPcm = new ByteArrayOutputStream(160000);
    private long cloudChunkStartedAt;
    private int cloudChunkPeak;

    private volatile String diagTranslation = "未启动";
    private volatile String diagAudio = "未启动";
    private volatile String diagSpeech = "未启动";
    private volatile String diagScreen = "OCR 已关闭";
    private String lastRenderedDiagnostics = "";

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
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UI_HIDDEN.equals(action)) {
            setUiVisible(false);
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        if (running || projection != null || router != null) stopPipelineOnly();

        inputMode = intent.getStringExtra(EXTRA_INPUT_MODE);
        if (!INPUT_MICROPHONE.equals(inputMode)) inputMode = INPUT_PLAYBACK;
        speechLanguage = intent.getStringExtra(EXTRA_SOURCE_SPEECH);
        sourceMlTag = intent.getStringExtra(EXTRA_SOURCE_MLKIT);
        targetMlTag = intent.getStringExtra(EXTRA_TARGET_MLKIT);
        engineId = intent.getStringExtra(EXTRA_ENGINE_ID);
        if (engineId == null) engineId = TranslationRouter.AUTO;
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, false);
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, false);
        autoMicFallback = intent.getBooleanExtra(EXTRA_AUTO_MIC_FALLBACK, true);
        allowYoudaoSpeech = intent.getBooleanExtra(EXTRA_YOUDAO_SPEECH_FALLBACK, true);
        paused = false;
        fallbackPosted = false;
        micFallbackActivated = false;
        cloudSpeechMode = false;
        cloudSpeechRequestInFlight = false;
        ocrTextSeen = false;
        ocrTranslationBusy = false;
        pendingOcrText = "";
        playbackRecognizerErrors = 0;
        lastPartial = "";
        lastOcrText = "";
        audibleWithoutTextStartedAt = 0L;
        resetCloudChunk();
        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);

        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        boolean playbackMode = INPUT_PLAYBACK.equals(inputMode);
        boolean projectionNeeded = playbackMode || (enableOcr && resultData != null);

        int foregroundType = 0;
        if (projectionNeeded) foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (INPUT_MICROPHONE.equals(inputMode) || (playbackMode && autoMicFallback)) {
            foregroundType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (foregroundType == 0) foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        startForeground(NOTIFICATION_ID, buildNotification("正在准备实时翻译"), foregroundType);

        if (!Settings.canDrawOverlays(this)
            || (projectionNeeded && resultData == null)
            || sourceMlTag == null || targetMlTag == null) {
            stopEverything();
            return START_NOT_STICKY;
        }

        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", true).apply();
        createOverlay(fontSize);
        router = new TranslationRouter(this, sourceMlTag, targetMlTag, engineId);
        setDiagTranslation("引擎：" + router.selectedEngineName());
        setDiagScreen(enableOcr ? "等待屏幕捕获" : "OCR 已关闭");
        setDiagAudio(INPUT_PLAYBACK.equals(inputMode) ? "等待系统声音" : "等待麦克风");
        setDiagSpeech("检测语音识别服务");

        if (projectionNeeded) startProjection(resultCode, resultData);
        else startMicrophoneMode();
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
                        setDiagScreen("屏幕捕获已停止");
                        stopEverything();
                    });
                }
            }, main);
            if (enableOcr) startOcr();
            if (INPUT_MICROPHONE.equals(inputMode)) startMicrophoneMode();
            else startPlaybackMode();
        } catch (Exception e) {
            setDiagScreen("屏幕捕获失败：" + safe(e));
            showStatus("启动屏幕/声音捕获失败：" + safe(e));
        }
    }

    private void startOcr() {
        if (projection == null || !enableOcr) return;
        setDiagScreen("OCR 捕获启动中");
        ocrCapture = new OcrCapture(this, sourceMlTag, new OcrCapture.Callback() {
            @Override public void onFrame(int width, int height) {
                if (!ocrTextSeen) main.post(() -> setDiagScreen("屏幕正常 " + width + "×" + height));
            }

            @Override public void onText(String text) {
                main.post(() -> {
                    ocrTextSeen = true;
                    handleOcrText(text);
                });
            }

            @Override public void onError(Exception error) {
                main.post(() -> setDiagScreen("OCR 错误：" + safe(error)));
            }
        });
        ocrCapture.start(projection);
    }

    private void startPlaybackMode() {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setDiagAudio("缺少录音权限");
                return;
            }
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat format = pcmFormat();
            int min = minBuffer();
            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(capture)
                .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败");
            }
            playbackStartedAt = SystemClock.elapsedRealtime();
            lastAudibleAt = playbackStartedAt;
            lastSpeechTextAt = playbackStartedAt;
            audibleWithoutTextStartedAt = 0L;
            audioRecord.startRecording();
            setDiagAudio("系统声音采集已启动");
            startRecognizerOrCloud();
            worker.execute(this::capturePlaybackLoop);
            showStatus(enableOcr ? "系统声音翻译中 · OCR 已开启" : "系统声音翻译中");
            updateNotification("系统声音实时翻译中");
        } catch (Exception e) {
            setDiagAudio("系统声音启动失败：" + safe(e));
            if (autoMicFallback) {
                main.postDelayed(() -> switchToMicrophoneFallback("系统声音捕获启动失败"), 250);
            } else {
                showStatus("启动系统声音失败：" + safe(e));
            }
        }
    }

    private void startMicrophoneMode() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setDiagAudio("缺少录音权限");
            return;
        }
        setDiagAudio("麦克风模式");
        startRecognizerOrCloud();
        showStatus(enableOcr && projection != null
            ? "麦克风翻译中 · OCR 已开启"
            : "麦克风翻译中");
        updateNotification("麦克风实时翻译中");
    }

    private void startRecognizerOrCloud() {
        if (!running || paused) return;
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startRecognizerSession();
            return;
        }
        if (canUseYoudaoSpeech()) {
            activateCloudSpeech("系统没有语音识别服务，已启用有道云语音");
        } else {
            cloudSpeechMode = false;
            setDiagSpeech("系统没有语音识别服务");
            showStatus("系统没有可用语音识别服务\n可在“翻译引擎/API设置”配置有道语音兜底");
        }
    }

    private boolean canUseYoudaoSpeech() {
        return allowYoudaoSpeech && router != null && router.hasYoudaoCredentials();
    }

    private void activateCloudSpeech(String reason) {
        cloudSpeechMode = true;
        closeSpeechPipe();
        destroyRecognizer();
        resetCloudChunk();
        setDiagSpeech(reason);
        if (INPUT_MICROPHONE.equals(inputMode)) startCloudMicrophoneCapture();
    }

    private void startRecognizerSession() {
        main.post(() -> {
            if (!running || paused || cloudSpeechMode) return;
            closeSpeechPipe();
            destroyRecognizer();
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
                Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);

                if (INPUT_PLAYBACK.equals(inputMode)) {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    readPipe = pipe[0];
                    writePipe = pipe[1];
                    synchronized (pipeLock) {
                        speechOutput = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe);
                    }
                    listen.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                    setDiagSpeech("系统识别器：内部音频");
                } else {
                    setDiagSpeech("系统识别器：麦克风");
                }
                recognizer.startListening(listen);
            } catch (Exception e) {
                setDiagSpeech("识别器启动失败：" + safe(e));
                if (canUseYoudaoSpeech()) activateCloudSpeech("系统识别器不兼容，已切有道云语音");
            }
        });
    }

    private void capturePlaybackLoop() {
        short[] shorts = new short[1600];
        byte[] bytes = new byte[shorts.length * 2];
        while (running && INPUT_PLAYBACK.equals(inputMode)) {
            AudioRecord record = audioRecord;
            if (record == null) break;
            int count;
            try {
                count = record.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                main.post(() -> setDiagAudio("系统声音读取失败：" + safe(e)));
                break;
            }
            if (count <= 0 || paused) continue;
            int peak = shortsToBytes(shorts, count, bytes);
            long now = SystemClock.elapsedRealtime();
            if (peak >= AUDIO_PRESENT_PEAK) {
                lastAudibleAt = now;
                if (now - lastSpeechTextAt > 1500L && audibleWithoutTextStartedAt == 0L) {
                    audibleWithoutTextStartedAt = now;
                }
            } else if (now - lastAudibleAt > 1500L) {
                audibleWithoutTextStartedAt = 0L;
            }
            updateAudioLevelThrottled(peak, true);

            if (cloudSpeechMode) {
                appendCloudPcm(bytes, count * 2, peak);
            } else {
                OutputStream output;
                synchronized (pipeLock) { output = speechOutput; }
                try {
                    if (output != null) output.write(bytes, 0, count * 2);
                } catch (IOException e) {
                    if (canUseYoudaoSpeech()) {
                        main.post(() -> activateCloudSpeech("内部音频管道不兼容，已切有道云语音"));
                    }
                }
            }

            if (autoMicFallback && !micFallbackActivated && !fallbackPosted
                && now - playbackStartedAt >= SILENCE_FALLBACK_MS
                && now - lastAudibleAt >= SILENCE_FALLBACK_MS) {
                fallbackPosted = true;
                main.post(() -> switchToMicrophoneFallback("连续 8 秒未检测到系统声音"));
                break;
            }

            if (!cloudSpeechMode && autoMicFallback && !micFallbackActivated && !fallbackPosted
                && audibleWithoutTextStartedAt > 0L
                && now - audibleWithoutTextStartedAt >= AUDIO_WITHOUT_TEXT_FALLBACK_MS
                && now - lastSpeechTextAt >= AUDIO_WITHOUT_TEXT_FALLBACK_MS) {
                if (canUseYoudaoSpeech()) {
                    main.post(() -> activateCloudSpeech("系统声音有信号但无文字，已切有道云语音"));
                } else {
                    fallbackPosted = true;
                    main.post(() -> switchToMicrophoneFallback("系统声音有信号但 10 秒没有识别文字"));
                    break;
                }
            }
        }
    }

    private void startCloudMicrophoneCapture() {
        releaseAudioRecord();
        try {
            audioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(pcmFormat())
                .setBufferSizeInBytes(minBuffer() * 2)
                .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("麦克风 AudioRecord 初始化失败");
            }
            audioRecord.startRecording();
            setDiagAudio("麦克风采集已启动");
            worker.execute(this::captureCloudMicrophoneLoop);
        } catch (Exception e) {
            setDiagAudio("麦克风云语音启动失败：" + safe(e));
        }
    }

    private void captureCloudMicrophoneLoop() {
        short[] shorts = new short[1600];
        byte[] bytes = new byte[shorts.length * 2];
        while (running && INPUT_MICROPHONE.equals(inputMode) && cloudSpeechMode) {
            AudioRecord record = audioRecord;
            if (record == null) break;
            int count;
            try {
                count = record.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                break;
            }
            if (count <= 0 || paused) continue;
            int peak = shortsToBytes(shorts, count, bytes);
            updateAudioLevelThrottled(peak, false);
            appendCloudPcm(bytes, count * 2, peak);
        }
    }

    private void appendCloudPcm(byte[] bytes, int length, int peak) {
        if (!cloudSpeechMode || router == null) return;
        long now = SystemClock.elapsedRealtime();
        if (cloudChunkStartedAt == 0L) cloudChunkStartedAt = now;
        cloudChunkPeak = Math.max(cloudChunkPeak, peak);
        cloudPcm.write(bytes, 0, length);
        if (now - cloudChunkStartedAt < CLOUD_CHUNK_MS) return;

        byte[] chunk = cloudPcm.toByteArray();
        int peakForChunk = cloudChunkPeak;
        resetCloudChunk();
        if (peakForChunk < AUDIO_PRESENT_PEAK || chunk.length < SAMPLE_RATE * 2) return;
        if (cloudSpeechRequestInFlight) return;
        cloudSpeechRequestInFlight = true;
        setDiagSpeech("有道云语音：识别中…");
        router.translateYoudaoSpeech(chunk, new TranslationRouter.SpeechCallback() {
            @Override public void onSuccess(String original, String translated, String engineName) {
                cloudSpeechRequestInFlight = false;
                lastSpeechTextAt = SystemClock.elapsedRealtime();
                setDiagSpeech(engineName + " 正常");
                setDiagTranslation(engineName + " 直译");
                if (showOriginal && originalText != null) originalText.setText("语音原文：" + original);
                if (translatedText != null) translatedText.setText("语音译文：" + translated);
                saveHistory(original, translated);
            }

            @Override public void onError(String message) {
                cloudSpeechRequestInFlight = false;
                setDiagSpeech("有道云语音失败：" + message);
            }
        });
    }

    private void resetCloudChunk() {
        synchronized (cloudPcm) {
            cloudPcm.reset();
            cloudChunkStartedAt = 0L;
            cloudChunkPeak = 0;
        }
    }

    private int shortsToBytes(short[] shorts, int count, byte[] bytes) {
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int value = Math.abs((int) shorts[i]);
            if (value > peak) peak = value;
            bytes[i * 2] = (byte) (shorts[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xff);
        }
        return peak;
    }

    private AudioFormat pcmFormat() {
        return new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build();
    }

    private int minBuffer() {
        return Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE);
    }

    private void updateAudioLevelThrottled(int peak, boolean system) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        String prefix = system ? "系统声音" : "麦克风";
        String value;
        if (peak < AUDIO_PRESENT_PEAK) value = prefix + "：▁ 近乎静音";
        else if (peak < 500) value = prefix + "：▂ 有信号";
        else if (peak < 2000) value = prefix + "：▃▅ 有声音";
        else if (peak < 8000) value = prefix + "：▃▅▇ 正常";
        else value = prefix + "：▃▅▇█ 较强";
        main.post(() -> setDiagAudio(value));
    }

    private void switchToMicrophoneFallback(String reason) {
        if (!running || !INPUT_PLAYBACK.equals(inputMode) || micFallbackActivated) return;
        micFallbackActivated = true;
        fallbackPosted = true;
        inputMode = INPUT_MICROPHONE;
        cloudSpeechMode = false;
        releaseAudioRecord();
        closeSpeechPipe();
        destroyRecognizer();
        resetCloudChunk();
        setDiagAudio("已切换麦克风兜底");
        showStatus(reason + "\n已改用麦克风，请保持外放声音");
        main.postDelayed(this::startRecognizerOrCloud, 350);
    }

    @Override public void onPartialResults(Bundle results) {
        String text = bestText(results);
        if (text.isEmpty()) return;
        lastSpeechTextAt = SystemClock.elapsedRealtime();
        audibleWithoutTextStartedAt = 0L;
        playbackRecognizerErrors = 0;
        lastPartial = text;
        setDiagSpeech("已识别：" + preview(text));
        if (showOriginal && originalText != null) originalText.setText("语音原文：" + text);
        if (partialTranslation != null) main.removeCallbacks(partialTranslation);
        partialTranslation = () -> translateVoice(text, false);
        main.postDelayed(partialTranslation, 750);
    }

    @Override public void onResults(Bundle results) {
        String text = bestText(results);
        if (!text.isEmpty()) {
            lastSpeechTextAt = SystemClock.elapsedRealtime();
            audibleWithoutTextStartedAt = 0L;
            playbackRecognizerErrors = 0;
            setDiagSpeech("结果：" + preview(text));
            if (showOriginal && originalText != null) originalText.setText("语音原文：" + text);
            translateVoice(text, true);
        }
        if (running && !paused && INPUT_MICROPHONE.equals(inputMode) && !cloudSpeechMode) {
            main.postDelayed(this::startRecognizerSession, 700);
        }
    }

    @Override public void onSegmentResults(Bundle results) {
        onResults(results);
    }

    @Override public void onEndOfSegmentedSession() {
        if (running && !paused && !cloudSpeechMode) main.postDelayed(this::startRecognizerSession, 700);
    }

    private void translateVoice(String text, boolean committed) {
        TranslationRouter current = router;
        if (current == null || text.isEmpty()) return;
        current.translate(text, new TranslationRouter.Callback() {
            @Override public void onSuccess(String value, String engineName) {
                if (committed || text.equals(lastPartial)) {
                    setDiagTranslation(engineName);
                    if (translatedText != null) translatedText.setText("语音译文：" + value);
                    saveHistory(text, value);
                }
            }

            @Override public void onError(String message) {
                setDiagTranslation("翻译失败：" + message);
                if (translatedText != null) translatedText.setText("语音翻译失败：" + message);
            }
        });
    }

    private void handleOcrText(String raw) {
        if (!enableOcr || paused || router == null || raw == null) return;
        String cleaned = sanitizeOcrText(raw);
        if (cleaned.isEmpty() || cleaned.equals(lastOcrText)) return;
        setDiagScreen("OCR 有效文字 " + cleaned.length() + " 字符");
        if (showOriginal && ocrOriginalText != null) ocrOriginalText.setText("屏幕原文：" + cleaned);
        if (ocrTranslationBusy) {
            pendingOcrText = cleaned;
            return;
        }
        startOcrTranslation(cleaned);
    }

    private void startOcrTranslation(String text) {
        ocrTranslationBusy = true;
        lastOcrText = text;
        router.translate(text, new TranslationRouter.Callback() {
            @Override public void onSuccess(String value, String engineName) {
                setDiagTranslation(engineName);
                if (ocrTranslatedText != null && text.equals(lastOcrText)) {
                    ocrTranslatedText.setText("屏幕译文：" + value);
                    saveHistory(text, value);
                }
                finishOcrTranslation();
            }

            @Override public void onError(String message) {
                if (ocrTranslatedText != null) ocrTranslatedText.setText("屏幕翻译失败：" + message);
                finishOcrTranslation();
            }
        });
    }

    private void finishOcrTranslation() {
        ocrTranslationBusy = false;
        if (!pendingOcrText.isEmpty() && !pendingOcrText.equals(lastOcrText)) {
            String next = pendingOcrText;
            pendingOcrText = "";
            main.postDelayed(() -> startOcrTranslation(next), 300);
        } else {
            pendingOcrText = "";
        }
    }

    private String sanitizeOcrText(String value) {
        String[] lines = value.split("\\n");
        StringBuilder out = new StringBuilder();
        int accepted = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() < 2 || isOwnUiLine(line)) continue;
            if (!matchesSourceScript(line, sourceMlTag)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
            accepted++;
            if (accepted >= 4 || out.length() >= 220) break;
        }
        String result = out.toString().trim();
        return result.length() > 220 ? result.substring(0, 220) : result;
    }

    private boolean isOwnUiLine(String line) {
        return line.startsWith("诊断")
            || line.startsWith("语音原文")
            || line.startsWith("语音译文")
            || line.startsWith("屏幕原文")
            || line.startsWith("屏幕译文")
            || line.startsWith("系统声音")
            || line.startsWith("麦克风")
            || line.contains("浮译 0.4")
            || line.contains("翻译引擎")
            || line.contains("开始实时翻译")
            || line.contains("停止翻译")
            || "暂停".equals(line)
            || "继续".equals(line)
            || "关闭".equals(line);
    }

    private boolean matchesSourceScript(String text, String lang) {
        int latin = 0, han = 0, kana = 0, hangul = 0, cyrillic = 0, arabic = 0, devanagari = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT) latin++;
            if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) han++;
            if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) kana++;
            if (b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO) hangul++;
            if (b == Character.UnicodeBlock.CYRILLIC) cyrillic++;
            if (b == Character.UnicodeBlock.ARABIC) arabic++;
            if (b == Character.UnicodeBlock.DEVANAGARI) devanagari++;
        }
        if ("ja".equals(lang)) return kana > 0;
        if ("zh".equals(lang)) return han > 0 && kana == 0;
        if ("ko".equals(lang)) return hangul > 0;
        if ("ru".equals(lang) || "uk".equals(lang) || "bg".equals(lang) || "mk".equals(lang)) return cyrillic > 0;
        if ("ar".equals(lang) || "fa".equals(lang) || "ur".equals(lang)) return arabic > 0;
        if ("hi".equals(lang) || "mr".equals(lang)) return devanagari > 0;
        return latin >= 2 && han == 0 && kana == 0 && hangul == 0;
    }

    private String bestText(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0).trim();
    }

    private void createOverlay(int fontSize) {
        removeOverlay();
        windowManager = getSystemService(WindowManager.class);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(7), dp(14), dp(9));
        box.setBackgroundResource(R.drawable.panel);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        pauseControl = overlayControl("暂停");
        TextView closeControl = overlayControl("关闭");
        pauseControl.setOnClickListener(v -> togglePause());
        closeControl.setOnClickListener(v -> stopEverything());
        controls.addView(pauseControl, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(closeControl, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        diagnosticsText = overlayText(11, Color.rgb(190, 165, 255));
        diagnosticsText.setMaxLines(4);
        diagnosticsText.setGravity(Gravity.START);
        box.addView(diagnosticsText, new LinearLayout.LayoutParams(-1, -2));

        originalText = overlayText(Math.max(12, fontSize - 6), Color.rgb(218, 209, 231));
        translatedText = overlayText(fontSize, Color.WHITE);
        translatedText.setTypeface(null, 1);
        originalText.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        box.addView(originalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translatedText, new LinearLayout.LayoutParams(-1, -2));

        ocrOriginalText = overlayText(Math.max(11, fontSize - 7), Color.rgb(205, 215, 235));
        ocrTranslatedText = overlayText(Math.max(13, fontSize - 3), Color.WHITE);
        ocrTranslatedText.setTypeface(null, 1);
        ocrOriginalText.setVisibility(enableOcr && showOriginal ? View.VISIBLE : View.GONE);
        ocrTranslatedText.setVisibility(enableOcr ? View.VISIBLE : View.GONE);
        box.addView(ocrOriginalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(ocrTranslatedText, new LinearLayout.LayoutParams(-1, -2));

        overlay = box;
        int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        overlayParams = new WindowManager.LayoutParams(
            -1, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.y = dp(100);
        overlayParams.width = getResources().getDisplayMetrics().widthPixels - dp(28);
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
        TextView t = overlayText(13, Color.rgb(190, 165, 255));
        t.setText(value);
        t.setPadding(dp(8), dp(2), dp(8), dp(4));
        return t;
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
        String value = "翻译：" + diagTranslation
            + "\n声音：" + diagAudio
            + "\n语音：" + diagSpeech
            + "\n屏幕：" + diagScreen;
        if (value.equals(lastRenderedDiagnostics)) return;
        lastRenderedDiagnostics = value;
        diagnosticsText.setText(value);
    }

    private void setUiVisible(boolean visible) {
        if (ocrCapture != null) ocrCapture.setPaused(visible);
        if (overlay != null) overlay.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void togglePause() {
        paused = !paused;
        if (ocrCapture != null) ocrCapture.setPaused(paused);
        if (paused) {
            destroyRecognizer();
            pauseControl.setText("继续");
            showStatus("翻译已暂停");
        } else {
            pauseControl.setText("暂停");
            showStatus("正在继续监听……");
            if (!cloudSpeechMode) startRecognizerOrCloud();
        }
    }

    private void saveHistory(String original, String translated) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("last_original", original == null ? "" : original)
            .putString("last_translation", translated == null ? "" : translated)
            .apply();
    }

    private void makeDraggable(View view) {
        final float[] start = new float[2];
        final int[] originalY = new int[1];
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                start[0] = event.getRawX();
                start[1] = event.getRawY();
                originalY[0] = overlayParams.y;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                overlayParams.y = Math.max(0,
                    originalY[0] + Math.round(start[1] - event.getRawY()));
                windowManager.updateViewLayout(overlay, overlayParams);
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP;
        });
    }

    private void showStatus(String value) {
        main.post(() -> {
            if (translatedText != null) translatedText.setText(value);
        });
    }

    private void releaseAudioRecord() {
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            try { record.release(); } catch (Exception ignored) {}
        }
    }

    private void destroyRecognizer() {
        SpeechRecognizer current = recognizer;
        recognizer = null;
        if (current != null) {
            try { current.cancel(); } catch (Exception ignored) {}
            try { current.destroy(); } catch (Exception ignored) {}
        }
    }

    private void closeSpeechPipe() {
        synchronized (pipeLock) {
            try { if (speechOutput != null) speechOutput.close(); } catch (IOException ignored) {}
            try { if (readPipe != null) readPipe.close(); } catch (IOException ignored) {}
            speechOutput = null;
            readPipe = null;
            writePipe = null;
        }
    }

    private void stopPipelineOnly() {
        running = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        if (partialTranslation != null) main.removeCallbacks(partialTranslation);
        releaseAudioRecord();
        closeSpeechPipe();
        destroyRecognizer();
        if (ocrCapture != null) {
            ocrCapture.stop();
            ocrCapture = null;
        }
        MediaProjection currentProjection = projection;
        projection = null;
        if (currentProjection != null) {
            try { currentProjection.stop(); } catch (Exception ignored) {}
        }
        if (router != null) {
            router.close();
            router = null;
        }
        resetCloudChunk();
    }

    private void stopEverything() {
        stopPipelineOnly();
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        originalText = null;
        translatedText = null;
        ocrOriginalText = null;
        ocrTranslatedText = null;
        diagnosticsText = null;
        lastRenderedDiagnostics = "";
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "实时翻译",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("浮译运行状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("浮译 0.4.0")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(message));
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String preview(String text) {
        String one = text.replace('\n', ' ').trim();
        return one.length() > 24 ? one.substring(0, 24) + "…" : one;
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

    @Override public void onDestroy() {
        stopPipelineOnly();
        removeOverlay();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onReadyForSpeech(Bundle params) {
        setDiagSpeech(INPUT_PLAYBACK.equals(inputMode)
            ? "系统识别器已就绪"
            : "麦克风识别器已就绪");
    }

    @Override public void onBeginningOfSpeech() {
        setDiagSpeech("检测到语音");
    }

    @Override public void onRmsChanged(float rmsdB) {
        if (!INPUT_MICROPHONE.equals(inputMode) || cloudSpeechMode) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        if (rmsdB < 1f) setDiagAudio("麦克风：▁ 等待语音");
        else if (rmsdB < 4f) setDiagAudio("麦克风：▂▃ 有声音");
        else if (rmsdB < 8f) setDiagAudio("麦克风：▃▅▇ 正常");
        else setDiagAudio("麦克风：▃▅▇█ 较强");
    }

    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { setDiagSpeech("语音结束，等待结果"); }
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onError(int error) {
        if (!running || paused || cloudSpeechMode) return;
        String message = speechErrorName(error);
        setDiagSpeech("识别错误：" + message);

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            if (canUseYoudaoSpeech()) activateCloudSpeech("系统语音包不可用，已切有道云语音");
            else showStatus("当前系统语音包不可用：" + speechLanguage);
            return;
        }

        playbackRecognizerErrors++;
        if (playbackRecognizerErrors >= 2 && canUseYoudaoSpeech()) {
            activateCloudSpeech("系统识别连续失败，已切有道云语音");
            return;
        }
        if (INPUT_PLAYBACK.equals(inputMode) && playbackRecognizerErrors >= 3 && autoMicFallback) {
            switchToMicrophoneFallback("系统声音语音识别连续失败");
            return;
        }
        main.postDelayed(this::startRecognizerSession, 1200);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
