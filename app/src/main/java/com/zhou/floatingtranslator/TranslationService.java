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

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslationService extends Service implements RecognitionListener {
    public static final String ACTION_START = "com.zhou.floatingtranslator.START";
    public static final String ACTION_STOP = "com.zhou.floatingtranslator.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_INPUT_MODE = "input_mode";
    public static final String INPUT_PLAYBACK = "playback";
    public static final String INPUT_MICROPHONE = "microphone";
    public static final String EXTRA_SOURCE_SPEECH = "source_speech";
    public static final String EXTRA_SOURCE_MLKIT = "source_mlkit";
    public static final String EXTRA_TARGET_MLKIT = "target_mlkit";
    public static final String EXTRA_SHOW_ORIGINAL = "show_original";
    public static final String EXTRA_PREFER_OFFLINE = "prefer_offline";
    public static final String EXTRA_FONT_SIZE = "font_size";
    public static final String EXTRA_ENABLE_OCR = "enable_ocr";
    public static final String EXTRA_AUTO_MIC_FALLBACK = "auto_mic_fallback";

    private static final int NOTIFICATION_ID = 3401;
    private static final String CHANNEL_ID = "floating_translation";
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_PRESENT_PEAK = 64;
    private static final long SILENCE_FALLBACK_MS = 8000L;
    private static final long AUDIO_WITHOUT_TEXT_FALLBACK_MS = 10000L;
    private static final long LEVEL_UPDATE_MS = 450L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object pipeLock = new Object();

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private SpeechRecognizer recognizer;
    private Translator translator;
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
    private TextView inputLevelText;
    private TextView diagnosticsText;
    private TextView pauseControl;

    private boolean showOriginal;
    private boolean preferOffline;
    private boolean enableOcr;
    private boolean autoMicFallback;
    private volatile boolean paused;
    private volatile boolean fallbackPosted;
    private boolean micFallbackActivated;
    private volatile boolean ocrTextSeen;
    private String inputMode = INPUT_PLAYBACK;
    private String speechLanguage;
    private String sourceMlTag;
    private String targetMlTag;
    private String lastPartial = "";
    private String lastOcrText = "";
    private Runnable partialTranslation;
    private long playbackStartedAt;
    private long lastAudibleAt;
    private long lastSpeechTextAt;
    private long audibleWithoutTextStartedAt;
    private long lastLevelUiAt;
    private int playbackRecognizerErrors;

    private volatile String diagTranslation = "加载中";
    private volatile String diagAudio = "未启动";
    private volatile String diagSpeech = "未启动";
    private volatile String diagScreen = "未启动";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        inputMode = intent.getStringExtra(EXTRA_INPUT_MODE);
        if (!INPUT_MICROPHONE.equals(inputMode)) inputMode = INPUT_PLAYBACK;
        speechLanguage = intent.getStringExtra(EXTRA_SOURCE_SPEECH);
        sourceMlTag = intent.getStringExtra(EXTRA_SOURCE_MLKIT);
        targetMlTag = intent.getStringExtra(EXTRA_TARGET_MLKIT);
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, false);
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, true);
        autoMicFallback = intent.getBooleanExtra(EXTRA_AUTO_MIC_FALLBACK, true);
        paused = false;
        fallbackPosted = false;
        micFallbackActivated = false;
        ocrTextSeen = false;
        playbackRecognizerErrors = 0;
        lastPartial = "";
        lastOcrText = "";
        audibleWithoutTextStartedAt = 0L;
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

        if (running || projection != null || translator != null) stopPipelineOnly();

        if (!Settings.canDrawOverlays(this)
            || (projectionNeeded && resultData == null)
            || sourceMlTag == null || targetMlTag == null) {
            stopEverything();
            return START_NOT_STICKY;
        }

        createOverlay(fontSize);
        setDiagTranslation("加载模型 " + sourceMlTag + "→" + targetMlTag);
        setDiagScreen(enableOcr ? "等待屏幕捕获" : "OCR 已关闭");
        setDiagAudio(INPUT_PLAYBACK.equals(inputMode) ? "等待系统声音" : "等待麦克风");
        setDiagSpeech("等待识别器");

        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlTag)
            .setTargetLanguage(targetMlTag)
            .build());
        showStatus("正在加载翻译模型……");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> {
                setDiagTranslation("ML Kit 已就绪 " + sourceMlTag + "→" + targetMlTag);
                if (projectionNeeded) startProjection(resultCode, resultData);
                else startMicrophoneMode();
            })
            .addOnFailureListener(e -> {
                setDiagTranslation("模型失败：" + safeMessage(e));
                showStatus("模型不可用，请回到浮译重新下载\n" + safeMessage(e));
            });
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
                        setDiagScreen("屏幕捕获已被系统停止");
                        stopEverything();
                    });
                }
            }, main);

            if (enableOcr) startOcr();
            if (INPUT_MICROPHONE.equals(inputMode)) startMicrophoneMode();
            else startPlaybackMode();
        } catch (Exception e) {
            setDiagScreen("屏幕捕获失败：" + safeMessage(e));
            showStatus("启动屏幕/声音捕获失败：" + safeMessage(e));
        }
    }

    private void startOcr() {
        if (projection == null || !enableOcr) return;
        setDiagScreen("正在创建 OCR 捕获");
        ocrCapture = new OcrCapture(this, sourceMlTag, new OcrCapture.Callback() {
            @Override public void onFrame(int width, int height) {
                if (!ocrTextSeen) {
                    main.post(() -> setDiagScreen("屏幕捕获正常 " + width + "×" + height + "，等待文字"));
                }
            }

            @Override public void onText(String text) {
                main.post(() -> {
                    ocrTextSeen = true;
                    setDiagScreen("OCR 已识别 " + text.length() + " 个字符");
                    handleOcrText(text);
                });
            }

            @Override public void onError(Exception error) {
                main.post(() -> {
                    setDiagScreen("OCR 错误：" + safeMessage(error));
                    if (ocrTranslatedText != null) {
                        ocrTranslatedText.setText("屏幕 OCR 暂不可用：" + safeMessage(error));
                    }
                });
            }
        });
        ocrCapture.start(projection);
    }

    private void startPlaybackMode() {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showStatus("缺少录音权限");
                setDiagAudio("缺少录音权限");
                return;
            }

            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
            int min = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE);
            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(capture)
                .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败");
            }

            running = true;
            playbackStartedAt = SystemClock.elapsedRealtime();
            lastAudibleAt = playbackStartedAt;
            lastSpeechTextAt = playbackStartedAt;
            audibleWithoutTextStartedAt = 0L;
            audioRecord.startRecording();
            setDiagAudio("系统声音采集已启动");
            startRecognizerSession();
            worker.execute(this::captureLoop);
            showStatus(enableOcr
                ? "等待系统声音……\n屏幕 OCR 已同时开启"
                : "等待其他 App 播放声音……");
            updateNotification("正在翻译系统声音 + 屏幕文字");
        } catch (Exception e) {
            setDiagAudio("系统声音启动失败：" + safeMessage(e));
            if (autoMicFallback) {
                showStatus("系统声音不可直接捕获，正在切换麦克风……");
                main.postDelayed(() -> switchToMicrophoneFallback("系统声音捕获启动失败"), 250);
            } else {
                showStatus("启动系统声音失败：" + safeMessage(e));
            }
        }
    }

    private void startMicrophoneMode() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("缺少录音权限");
            setDiagAudio("缺少录音权限");
            return;
        }
        running = true;
        setDiagAudio("麦克风已启动，等待声音");
        startRecognizerSession();
        showStatus(enableOcr && projection != null
            ? "正在监听麦克风……\n屏幕 OCR 已同时开启"
            : "正在监听麦克风……\n电话翻译时请打开免提并尽量降低环境噪声");
        updateNotification("麦克风/免提 + 屏幕翻译中");
    }

    private void startRecognizerSession() {
        main.post(() -> {
            if (!running || paused) return;
            closeSpeechPipe();
            destroyRecognizer();

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                setDiagSpeech("系统没有可用语音识别服务");
                showStatus("系统没有可用的语音识别服务");
                if (INPUT_PLAYBACK.equals(inputMode) && autoMicFallback) {
                    switchToMicrophoneFallback("系统语音识别服务不可用");
                }
                return;
            }

            try {
                // Use the system default recognizer for maximum OEM compatibility.
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
                    setDiagSpeech("识别器启动中（内部 PCM）");
                } else {
                    setDiagSpeech("识别器启动中（麦克风）");
                }
                recognizer.startListening(listen);
            } catch (Exception e) {
                setDiagSpeech("识别器启动失败：" + safeMessage(e));
                if (INPUT_PLAYBACK.equals(inputMode) && autoMicFallback) {
                    switchToMicrophoneFallback("系统语音识别器不接受内部音频");
                } else {
                    showStatus("语音识别启动失败：" + safeMessage(e));
                }
            }
        });
    }

    private void captureLoop() {
        short[] shorts = new short[1600];
        byte[] bytes = new byte[shorts.length * 2];

        while (running && INPUT_PLAYBACK.equals(inputMode)) {
            AudioRecord record = audioRecord;
            if (record == null) break;
            int count;
            try {
                count = record.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                setDiagAudio("读取系统声音失败：" + safeMessage(e));
                break;
            }
            if (count <= 0) continue;
            if (paused) continue;

            int peak = 0;
            for (int i = 0; i < count; i++) {
                int value = Math.abs((int) shorts[i]);
                if (value > peak) peak = value;
                bytes[i * 2] = (byte) (shorts[i] & 0xff);
                bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xff);
            }

            long now = SystemClock.elapsedRealtime();
            if (peak >= AUDIO_PRESENT_PEAK) {
                lastAudibleAt = now;
                if (now - lastSpeechTextAt > 1500L && audibleWithoutTextStartedAt == 0L) {
                    audibleWithoutTextStartedAt = now;
                }
                if (now - lastSpeechTextAt <= 1500L) audibleWithoutTextStartedAt = 0L;
            } else if (now - lastAudibleAt > 1500L) {
                audibleWithoutTextStartedAt = 0L;
            }

            if (now - lastLevelUiAt >= LEVEL_UPDATE_MS) {
                lastLevelUiAt = now;
                final int currentPeak = peak;
                main.post(() -> setDiagAudio(audioLevelLabel(currentPeak)));
            }

            if (autoMicFallback && !micFallbackActivated && !fallbackPosted
                && now - playbackStartedAt >= SILENCE_FALLBACK_MS
                && now - lastAudibleAt >= SILENCE_FALLBACK_MS) {
                fallbackPosted = true;
                main.post(() -> switchToMicrophoneFallback("连续 8 秒未检测到可捕获的系统声音"));
                break;
            }

            if (autoMicFallback && !micFallbackActivated && !fallbackPosted
                && audibleWithoutTextStartedAt > 0L
                && now - audibleWithoutTextStartedAt >= AUDIO_WITHOUT_TEXT_FALLBACK_MS
                && now - lastSpeechTextAt >= AUDIO_WITHOUT_TEXT_FALLBACK_MS) {
                fallbackPosted = true;
                main.post(() -> switchToMicrophoneFallback("系统声音有信号，但 10 秒没有识别出文字"));
                break;
            }

            OutputStream output;
            synchronized (pipeLock) { output = speechOutput; }
            try {
                if (output != null) output.write(bytes, 0, count * 2);
            } catch (IOException e) {
                setDiagSpeech("内部音频管道断开，正在重启识别器");
                if (!paused && INPUT_PLAYBACK.equals(inputMode)) {
                    main.postDelayed(this::startRecognizerSession, 250);
                }
            }
        }
    }

    private String audioLevelLabel(int peak) {
        if (peak < AUDIO_PRESENT_PEAK) return "系统声音：▁ 近乎静音";
        if (peak < 500) return "系统声音：▂ 有信号";
        if (peak < 2000) return "系统声音：▃▅ 有声音";
        if (peak < 8000) return "系统声音：▃▅▇ 正常";
        return "系统声音：▃▅▇█ 较强";
    }

    private void switchToMicrophoneFallback(String reason) {
        if (!running || !INPUT_PLAYBACK.equals(inputMode) || micFallbackActivated) return;
        micFallbackActivated = true;
        fallbackPosted = true;
        inputMode = INPUT_MICROPHONE;
        releaseAudioRecord();
        closeSpeechPipe();
        destroyRecognizer();
        setDiagAudio("已切换麦克风兜底");
        setDiagSpeech("准备麦克风识别器");
        showStatus(reason + "\n已自动改用麦克风，请保持视频/直播外放声音");
        updateNotification("系统声音受限，已切换麦克风翻译");
        main.postDelayed(this::startRecognizerSession, 300);
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
        main.postDelayed(partialTranslation, 450);
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
        if (running && !paused && INPUT_MICROPHONE.equals(inputMode)) {
            main.postDelayed(this::startRecognizerSession, 350);
        }
    }

    @Override public void onSegmentResults(Bundle results) {
        onResults(results);
    }

    @Override public void onEndOfSegmentedSession() {
        if (running && !paused) main.postDelayed(this::startRecognizerSession, 300);
    }

    private void translateVoice(String text, boolean committed) {
        Translator current = translator;
        if (current == null || text.isEmpty()) return;
        current.translate(text)
            .addOnSuccessListener(value -> {
                if (committed || text.equals(lastPartial)) {
                    setDiagTranslation("ML Kit 正常 " + sourceMlTag + "→" + targetMlTag);
                    if (translatedText != null) translatedText.setText("语音译文：" + value);
                    saveHistory(text, value);
                }
            })
            .addOnFailureListener(e -> {
                setDiagTranslation("翻译失败：" + safeMessage(e));
                if (translatedText != null) translatedText.setText("语音翻译失败：" + safeMessage(e));
            });
    }

    private void handleOcrText(String text) {
        if (!enableOcr || paused || translator == null || text == null) return;
        String cleaned = sanitizeOcrText(text);
        if (cleaned.isEmpty() || cleaned.equals(lastOcrText)) return;
        lastOcrText = cleaned;
        if (showOriginal && ocrOriginalText != null) {
            ocrOriginalText.setText("屏幕原文：" + cleaned);
        }
        Translator current = translator;
        current.translate(cleaned)
            .addOnSuccessListener(value -> {
                if (!cleaned.equals(lastOcrText) || ocrTranslatedText == null) return;
                setDiagTranslation("ML Kit 正常 " + sourceMlTag + "→" + targetMlTag);
                ocrTranslatedText.setText("屏幕译文：" + value);
                saveHistory(cleaned, value);
            })
            .addOnFailureListener(e -> {
                setDiagTranslation("OCR 翻译失败：" + safeMessage(e));
                if (ocrTranslatedText != null) {
                    ocrTranslatedText.setText("屏幕翻译失败：" + safeMessage(e));
                }
            });
    }

    private String sanitizeOcrText(String value) {
        String[] lines = value.split("\\n");
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("诊断")
                || line.startsWith("语音原文：")
                || line.startsWith("语音译文：")
                || line.startsWith("屏幕原文：")
                || line.startsWith("屏幕译文：")
                || line.startsWith("系统声音：")
                || line.startsWith("麦克风：")
                || "暂停".equals(line)
                || "继续".equals(line)
                || "关闭".equals(line)) {
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
            if (out.length() >= 700) break;
        }
        String result = out.toString().trim();
        return result.length() > 700 ? result.substring(0, 700) : result;
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
        box.setPadding(dp(16), dp(8), dp(16), dp(10));
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
        diagnosticsText.setMaxLines(5);
        diagnosticsText.setGravity(Gravity.START);
        box.addView(diagnosticsText, new LinearLayout.LayoutParams(-1, -2));

        inputLevelText = overlayText(12, Color.rgb(210, 190, 255));
        inputLevelText.setMaxLines(1);
        box.addView(inputLevelText, new LinearLayout.LayoutParams(-1, -2));

        originalText = overlayText(Math.max(13, fontSize - 5), Color.rgb(218, 209, 231));
        translatedText = overlayText(fontSize, Color.WHITE);
        translatedText.setTypeface(null, 1);
        box.addView(originalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translatedText, new LinearLayout.LayoutParams(-1, -2));
        originalText.setVisibility(showOriginal ? View.VISIBLE : View.GONE);

        ocrOriginalText = overlayText(Math.max(12, fontSize - 6), Color.rgb(205, 215, 235));
        ocrTranslatedText = overlayText(Math.max(14, fontSize - 2), Color.WHITE);
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
        overlayParams.x = dp(12);
        overlayParams.y = dp(110);
        overlayParams.width = getResources().getDisplayMetrics().widthPixels - dp(24);
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
        t.setPadding(dp(8), dp(3), dp(8), dp(5));
        return t;
    }

    private void setDiagTranslation(String value) {
        diagTranslation = value;
        main.post(this::renderDiagnostics);
    }

    private void setDiagAudio(String value) {
        diagAudio = value;
        main.post(() -> {
            if (inputLevelText != null) inputLevelText.setText(value);
            renderDiagnostics();
        });
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
            "诊断｜翻译：" + diagTranslation +
            "\n声音：" + diagAudio +
            "\n语音：" + diagSpeech +
            "\n屏幕：" + diagScreen
        );
    }

    private void togglePause() {
        paused = !paused;
        if (paused) {
            if (recognizer != null) recognizer.cancel();
            pauseControl.setText("继续");
            showStatus("翻译已暂停");
            setDiagSpeech("已暂停");
        } else {
            pauseControl.setText("暂停");
            showStatus("正在继续监听……");
            setDiagSpeech("正在重新启动");
            startRecognizerSession();
        }
    }

    private void saveHistory(String original, String translated) {
        getSharedPreferences("floating_translator", MODE_PRIVATE).edit()
            .putString("last_original", original)
            .putString("last_translation", translated)
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

    private void stopPipelineOnly() {
        running = false;
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
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }

    private void stopEverything() {
        stopPipelineOnly();
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        diagnosticsText = null;
        inputLevelText = null;
        originalText = null;
        translatedText = null;
        ocrOriginalText = null;
        ocrTranslatedText = null;
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
            .setContentTitle("浮译")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(message));
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String preview(String text) {
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > 28 ? oneLine.substring(0, 28) + "…" : oneLine;
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
            ? "识别器已就绪，等待内部声音"
            : "识别器已就绪，等待麦克风语音");
    }

    @Override public void onBeginningOfSpeech() {
        setDiagSpeech("检测到语音");
        if (INPUT_MICROPHONE.equals(inputMode)) setDiagAudio("麦克风：检测到语音");
    }

    @Override public void onRmsChanged(float rmsdB) {
        if (!INPUT_MICROPHONE.equals(inputMode)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        if (rmsdB < 1f) setDiagAudio("麦克风：▁ 等待语音");
        else if (rmsdB < 4f) setDiagAudio("麦克风：▂▃ 有声音");
        else if (rmsdB < 8f) setDiagAudio("麦克风：▃▅▇ 正常");
        else setDiagAudio("麦克风：▃▅▇█ 较强");
    }

    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {
        setDiagSpeech("语音结束，等待结果");
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onError(int error) {
        if (!running || paused) return;
        String message = speechErrorName(error);
        setDiagSpeech("识别错误：" + message);

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            showStatus("当前语音包不可用：" + speechLanguage);
            return;
        }

        if (INPUT_PLAYBACK.equals(inputMode) && autoMicFallback) {
            playbackRecognizerErrors++;
            if (playbackRecognizerErrors >= 3) {
                switchToMicrophoneFallback("系统声音语音识别连续失败（" + message + "）");
                return;
            }
        }

        main.postDelayed(this::startRecognizerSession, 700);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
