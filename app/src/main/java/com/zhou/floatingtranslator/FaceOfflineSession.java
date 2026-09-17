package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Continuous foreground microphone capture for face-to-face local ASR. */
final class FaceOfflineSession implements AutoCloseable {
    interface Callback {
        void status(String text);
        void result(String text, String language);
        void error(String text);
    }

    private static final String PREFS = "floating_translator";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService capture = Executors.newSingleThreadExecutor();
    private final Callback callback;
    private final String myLanguage;
    private volatile boolean closed;
    private volatile AudioRecord record;
    private SherpaSpeechEngine sherpa;
    private OfflineSpeechEngine vosk;

    FaceOfflineSession(Context context, String engine, String language, boolean auto, Callback callback) {
        this.callback = callback;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int targetIndex = Math.max(0, Math.min(
            prefs.getInt("face_target_index", prefs.getInt("target_index", 0)),
            LanguageOption.ALL.length - 1));
        myLanguage = LanguageOption.ALL[targetIndex].mlKitTag;

        String resolvedEngine = resolveAutoConversationEngine(context, engine, auto, prefs);
        if ("vosk".equals(resolvedEngine)) {
            vosk = new OfflineSpeechEngine(context, language, new OfflineSpeechEngine.Callback() {
                public void onStatus(String text) { if (!closed) callback.status(text); }
                public void onReady(String name) { startCapture(name); }
                public void onPartial(String text) { if (!closed) callback.status("正在识别：" + text); }
                public void onFinal(String text) { deliver(text, language); }
                public void onError(String text) { if (!closed) callback.error(text); }
            });
        } else {
            sherpa = new SherpaSpeechEngine(context, resolvedEngine,
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
        if (closed) return;
        String cleaned = AsrTranscriptGuard.clean(text);
        if (cleaned.isEmpty()) {
            callback.status("持续监听 · 已忽略 ASR 控制标记/空结果");
            return;
        }
        String stableLanguage = AsrTranscriptGuard.stabilizeLanguage(cleaned, language, myLanguage);
        callback.result(cleaned, stableLanguage);
    }

    /**
     * Face-to-face Auto mode is optimized for language switching rather than the normal realtime
     * battery/latency balance. Prefer Qwen3 when installed, then Whisper Medium/Small. Explicit
     * user model selections are never replaced here.
     */
    private static String resolveAutoConversationEngine(Context context, String requested,
                                                        boolean autoLanguage, SharedPreferences prefs) {
        if (!autoLanguage || !"auto".equals(prefs.getString("face_asr", "auto"))) return requested;
        OfflineModelStore store = new OfflineModelStore(context);
        try {
            String[] preference = {
                TranslationService.ASR_QWEN3,
                TranslationService.ASR_WHISPER_MEDIUM,
                TranslationService.ASR_WHISPER_SMALL,
                TranslationService.ASR_OMNILINGUAL,
                TranslationService.ASR_SENSEVOICE
            };
            for (String id : preference) {
                if (store.isInstalled(id)) return id;
            }
            return requested;
        } finally {
            store.close();
        }
    }

    private void startCapture(String name) {
        if (closed) return;
        callback.status(name + " · 持续监听，停顿后自动切句");
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
            } catch (SecurityException e) {
                main.post(() -> { if (!closed) callback.error("录音权限已撤销，请重新授予麦克风权限"); });
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
        capture.execute(() -> {
            if (vosk != null) vosk.close();
            if (sherpa != null) sherpa.close();
        });
        capture.shutdown();
    }
}
