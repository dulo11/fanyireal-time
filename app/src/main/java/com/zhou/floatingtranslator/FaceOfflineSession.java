package com.zhou.floatingtranslator;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, one-utterance microphone capture. Never changes the chosen input source. */
final class FaceOfflineSession implements AutoCloseable {
    interface Callback {
        void status(String text);
        void result(String text, String language);
        void error(String text);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService capture = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile AudioRecord record;
    private SherpaSpeechEngine sherpa;
    private OfflineSpeechEngine vosk;
    private final Callback callback;
    FaceOfflineSession(Context context, String engine, String language, boolean auto, Callback callback) {
        this.callback = callback;
        if ("vosk".equals(engine)) {
            vosk = new OfflineSpeechEngine(context, language, new OfflineSpeechEngine.Callback() {
                public void onStatus(String text) { if (!closed) callback.status(text); }
                public void onReady(String name) { startCapture(name); }
                public void onPartial(String text) { if (!closed) callback.status("正在识别：" + text); }
                public void onFinal(String text) { deliver(text, language); }
                public void onError(String text) { if (!closed) callback.error(text); }
            });
        } else {
            sherpa = new SherpaSpeechEngine(context, engine,
                auto ? SherpaSpeechEngine.LANG_AUTO : SherpaSpeechEngine.LANG_SINGLE,
                language, new SherpaSpeechEngine.Callback() {
                    public void onStatus(String text) { if (!closed) callback.status(text); }
                    public void onReady(String name) { startCapture(name); }
                    public void onText(String text, String lang) { deliver(text, lang); }
                    public void onError(String text) { if (!closed) callback.error(text); }
                });
        }
    }
    void start() { if (vosk != null) vosk.prepare(); else sherpa.prepare(); }
    private void deliver(String text, String language) {
        if (!closed && text != null && !text.trim().isEmpty()) callback.result(text.trim(), language);
    }
    private void startCapture(String name) {
        if (closed) return;
        callback.status(name + " · 请说话，停顿后自动翻译");
        capture.execute(() -> {
            AudioRecord local = null;
            MicAudioEffects effects = null;
            try {
                int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("设备不支持 16kHz 录音");
                local = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 6400));
                if (local.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风初始化失败");
                synchronized (this) {
                    if (closed) return;
                    record = local;
                    local.startRecording();
                }
                if (local.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("麦克风被其他应用占用");
                effects = MicAudioEffects.attach(local, MicAudioEffects.MODE_NEAR);
                byte[] data = new byte[3200];
                while (!closed) {
                    int n = local.read(data, 0, data.length);
                    if (n < 0) throw new IllegalStateException("录音中断（" + n + "）");
                    if (n == 0) continue;
                    int peak = 0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int sample = (short) ((data[i] & 255) | (data[i + 1] << 8));
                        peak = Math.max(peak, Math.abs(sample));
                    }
                    if (vosk != null) vosk.acceptPcm(data, n);
                    else if (sherpa != null) sherpa.acceptPcm(data, n, peak);
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "录音失败" : e.getMessage();
                main.post(() -> { if (!closed) callback.error(message); });
            } finally {
                if (effects != null) effects.close();
                if (local != null) {
                    synchronized (this) {
                        if (record == local) record = null;
                        try { local.stop(); } catch (Exception ignored) {}
                        local.release();
                    }
                }
            }
        });
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (record != null) try { record.stop(); } catch (Exception ignored) {}
        // Serialize native cleanup after any in-flight PCM feed; keep the UI thread free.
        capture.execute(() -> {
            if (vosk != null) vosk.close();
            if (sherpa != null) sherpa.close();
        });
        capture.shutdown();
    }
}
