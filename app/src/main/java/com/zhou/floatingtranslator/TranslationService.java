package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    public static final String EXTRA_SOURCE_SPEECH = "source_speech";
    public static final String EXTRA_SOURCE_MLKIT = "source_mlkit";
    public static final String EXTRA_TARGET_MLKIT = "target_mlkit";
    public static final String EXTRA_SHOW_ORIGINAL = "show_original";
    public static final String EXTRA_FONT_SIZE = "font_size";

    private static final int NOTIFICATION_ID = 3401;
    private static final String CHANNEL_ID = "floating_translation";
    private static final int SAMPLE_RATE = 16000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object pipeLock = new Object();

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private SpeechRecognizer recognizer;
    private Translator translator;
    private ParcelFileDescriptor readPipe;
    private ParcelFileDescriptor writePipe;
    private OutputStream speechOutput;
    private volatile boolean running;

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView originalText;
    private TextView translatedText;
    private boolean showOriginal;
    private String speechLanguage;
    private String lastPartial = "";
    private Runnable partialTranslation;

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

        startForeground(NOTIFICATION_ID, buildNotification("正在准备实时翻译"));
        if (running) stopPipelineOnly();
        speechLanguage = intent.getStringExtra(EXTRA_SOURCE_SPEECH);
        String sourceMl = intent.getStringExtra(EXTRA_SOURCE_MLKIT);
        String targetMl = intent.getStringExtra(EXTRA_TARGET_MLKIT);
        showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true);
        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);
        Intent resultData = android.os.Build.VERSION.SDK_INT >= 33
            ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
            : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        if (!Settings.canDrawOverlays(this) || resultData == null || sourceMl == null || targetMl == null) {
            stopEverything();
            return START_NOT_STICKY;
        }
        createOverlay(fontSize);
        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(sourceMl)
            .setTargetLanguage(targetMl)
            .build());
        showStatus("正在加载翻译模型……");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> startProjection(resultCode, resultData))
            .addOnFailureListener(e -> showStatus("模型不可用，请回到浮译重新下载\n" + safeMessage(e)));
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(TranslationService.this::stopEverything); }
            }, main);

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
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showStatus("缺少录音权限"); return;
            }
            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(capture)
                .build();
            running = true;
            startRecognizerSession();
            audioRecord.startRecording();
            worker.execute(this::captureLoop);
            showStatus("等待其他 App 播放声音……");
            updateNotification("正在翻译系统声音");
        } catch (Exception e) {
            showStatus("启动失败：" + safeMessage(e));
        }
    }

    private void startRecognizerSession() {
        main.post(() -> {
            if (!running) return;
            closeSpeechPipe();
            if (recognizer != null) recognizer.destroy();
            try {
                recognizer = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readPipe = pipe[0];
                writePipe = pipe[1];
                synchronized (pipeLock) {
                    speechOutput = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe);
                }
                Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                recognizer.startListening(listen);
            } catch (Exception e) {
                showStatus("设备端识别启动失败：" + safeMessage(e));
            }
        });
    }

    private void captureLoop() {
        short[] shorts = new short[1600];
        byte[] bytes = new byte[shorts.length * 2];
        while (running && audioRecord != null) {
            int count = audioRecord.read(shorts, 0, shorts.length, AudioRecord.READ_BLOCKING);
            if (count <= 0) continue;
            for (int i = 0; i < count; i++) {
                bytes[i * 2] = (byte) (shorts[i] & 0xff);
                bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xff);
            }
            OutputStream output;
            synchronized (pipeLock) { output = speechOutput; }
            try {
                if (output != null) output.write(bytes, 0, count * 2);
            } catch (IOException ignored) {
                main.postDelayed(this::startRecognizerSession, 250);
            }
        }
    }

    @Override public void onPartialResults(Bundle results) {
        String text = bestText(results);
        if (text.isEmpty()) return;
        lastPartial = text;
        if (showOriginal) originalText.setText(text);
        if (partialTranslation != null) main.removeCallbacks(partialTranslation);
        partialTranslation = () -> translate(text, false);
        main.postDelayed(partialTranslation, 550);
    }

    @Override public void onResults(Bundle results) {
        String text = bestText(results);
        if (!text.isEmpty()) {
            if (showOriginal) originalText.setText(text);
            translate(text, true);
        }
    }

    @Override public void onSegmentResults(Bundle results) {
        onResults(results);
    }

    @Override public void onEndOfSegmentedSession() {
        if (running) main.postDelayed(this::startRecognizerSession, 300);
    }

    private void translate(String text, boolean committed) {
        if (translator == null || text.isEmpty()) return;
        translator.translate(text)
            .addOnSuccessListener(value -> {
                if (committed || text.equals(lastPartial)) translatedText.setText(value);
            })
            .addOnFailureListener(e -> translatedText.setText("翻译失败：" + safeMessage(e)));
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
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        box.setBackgroundResource(R.drawable.panel);
        originalText = overlayText(Math.max(14, fontSize - 4), Color.rgb(218, 209, 231));
        translatedText = overlayText(fontSize, Color.WHITE);
        translatedText.setTypeface(null, 1);
        box.addView(originalText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translatedText, new LinearLayout.LayoutParams(-1, -2));
        originalText.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        overlay = box;

        overlayParams = new WindowManager.LayoutParams(
            -1, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.x = dp(12);
        overlayParams.y = dp(110);
        overlayParams.width = getResources().getDisplayMetrics().widthPixels - dp(24);
        makeDraggable(box);
        windowManager.addView(overlay, overlayParams);
    }

    private TextView overlayText(int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        t.setMaxLines(4); t.setText(" ");
        return t;
    }

    private void makeDraggable(View view) {
        final float[] start = new float[2];
        final int[] originalY = new int[1];
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                start[0] = event.getRawX(); start[1] = event.getRawY(); originalY[0] = overlayParams.y; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                overlayParams.y = Math.max(0, originalY[0] + Math.round(start[1] - event.getRawY()));
                windowManager.updateViewLayout(overlay, overlayParams); return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP;
        });
    }

    private void showStatus(String value) {
        main.post(() -> {
            if (translatedText != null) translatedText.setText(value);
        });
    }

    private void stopPipelineOnly() {
        running = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release(); audioRecord = null;
        }
        closeSpeechPipe();
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (translator != null) { translator.close(); translator = null; }
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
            speechOutput = null; readPipe = null; writePipe = null;
        }
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
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

    @Override public void onDestroy() { stopPipelineOnly(); removeOverlay(); worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}
    @Override public void onError(int error) {
        if (!running) return;
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            showStatus("当前语音包不可用，请在系统语音识别设置中下载：" + speechLanguage);
        }
        main.postDelayed(this::startRecognizerSession, 600);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
