package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Feeds app-captured 16 kHz mono PCM directly into Android's installed RecognitionService.
 *
 * Android 13+ exposes RecognizerIntent.EXTRA_AUDIO_SOURCE for an already-opened audio source.
 * We chunk PCM ourselves, close the pipe after each utterance, and wait for a final result before
 * sending the next chunk. If the OEM recognizer rejects external PCM the caller can fall back to
 * free online/local ASR without changing the audio capture source.
 */
public final class AndroidPcmSpeechEngine implements RecognitionListener, AutoCloseable {
    public interface Callback {
        void onStatus(String message);
        void onReady(String engineName);
        void onText(String text, String detectedLanguage);
        void onError(String message, boolean fatal);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int SPEECH_PEAK = 90;
    private static final long END_SILENCE_MS = 420L;
    private static final long MIN_SEGMENT_MS = 600L;
    private static final long FORCE_SEGMENT_MS = 4800L;
    private static final int MAX_QUEUE = 5;
    private static final long RESULT_TIMEOUT_MS = 18000L;

    private final Context context;
    private final String languageMode;
    private final String speechLanguage;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream speech = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

    private volatile SpeechRecognizer recognizer;
    private volatile boolean closed;
    private volatile boolean ready;
    private boolean inSpeech;
    private long segmentMs;
    private long silenceMs;
    private boolean requestBusy;
    private int consecutiveFailures;
    private String lastText = "";
    private String detectedLanguage = "";
    private ParcelFileDescriptor readFd;
    private ParcelFileDescriptor writeFd;
    private Runnable resultTimeout;

    public AndroidPcmSpeechEngine(Context context, String languageMode, String speechLanguage,
                                  Callback callback) {
        this.context = context.getApplicationContext();
        this.languageMode = languageMode == null ? SherpaSpeechEngine.LANG_AUTO : languageMode;
        this.speechLanguage = speechLanguage == null ? "" : speechLanguage.trim();
        this.callback = callback;
    }

    public boolean isReady() {
        return ready && !closed && recognizer != null;
    }

    public void prepare() {
        main.post(() -> {
            if (closed) return;
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                postError("Android 系统没有可用 SpeechRecognizer", true);
                return;
            }
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
                ready = true;
                callback.onReady("Android 系统 SpeechRecognizer · 外部 PCM");
                callback.onStatus("系统识别器已就绪；首段将实测 EXTRA_AUDIO_SOURCE");
            } catch (Throwable e) {
                postError("系统识别器初始化失败：" + safe(e), true);
            }
        });
    }

    /** Accepts 16 kHz mono PCM16 little-endian audio. */
    public synchronized void acceptPcm(byte[] pcm, int length, int peak) {
        if (!isReady() || pcm == null || length <= 0) return;
        int safeLength = Math.min(length, pcm.length);
        long frameMs = Math.max(1L, Math.round((safeLength / 2.0) * 1000.0 / SAMPLE_RATE));
        boolean voiced = peak >= SPEECH_PEAK;

        if (voiced) {
            if (!inSpeech) {
                inSpeech = true;
                segmentMs = 0L;
                silenceMs = 0L;
                speech.reset();
            }
            silenceMs = 0L;
        } else if (inSpeech) {
            silenceMs += frameMs;
        } else {
            return;
        }

        speech.write(pcm, 0, safeLength);
        segmentMs += frameMs;
        boolean natural = silenceMs >= END_SILENCE_MS && segmentMs >= MIN_SEGMENT_MS;
        boolean forced = segmentMs >= FORCE_SEGMENT_MS;
        if (natural || forced) finishSegmentLocked();
    }

    public synchronized void flush() {
        if (closed) return;
        if (speech.size() >= SAMPLE_RATE) finishSegmentLocked();
    }

    private void finishSegmentLocked() {
        byte[] chunk = speech.toByteArray();
        speech.reset();
        inSpeech = false;
        segmentMs = 0L;
        silenceMs = 0L;
        if (chunk.length < SAMPLE_RATE) return;
        if (pending.size() >= MAX_QUEUE) {
            pending.pollFirst();
            postStatus("Android 系统识别队列拥堵，已丢弃最旧片段");
        }
        pending.offerLast(chunk);
        processNextLocked();
    }

    private void processNextLocked() {
        if (closed || requestBusy) return;
        byte[] chunk = pending.pollFirst();
        if (chunk == null) return;
        requestBusy = true;
        main.post(() -> startSession(chunk));
    }

    private void startSession(byte[] chunk) {
        if (closed) return;
        SpeechRecognizer r = recognizer;
        if (r == null) {
            failCurrent("系统识别器已关闭", true);
            return;
        }
        closePipe();
        detectedLanguage = "";
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readFd = pipe[0];
            writeFd = pipe[1];

            Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);

            if (SherpaSpeechEngine.LANG_SINGLE.equals(languageMode) && !speechLanguage.isEmpty()) {
                listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage);
            } else {
                listen.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            }

            postStatus("Android 系统识别 · 注入 16k PCM");
            r.startListening(listen);

            ParcelFileDescriptor localWrite = writeFd;
            writer.execute(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(localWrite)) {
                    out.write(chunk);
                    out.flush();
                } catch (Throwable e) {
                    if (!closed) main.post(() -> failCurrent("写入系统识别 PCM 失败：" + safe(e), true));
                } finally {
                    synchronized (AndroidPcmSpeechEngine.this) {
                        if (writeFd == localWrite) writeFd = null;
                    }
                }
            });

            resultTimeout = () -> failCurrent("Android 系统外部 PCM 识别超时", true);
            main.postDelayed(resultTimeout, RESULT_TIMEOUT_MS);
        } catch (Throwable e) {
            failCurrent("Android 系统外部 PCM 启动失败：" + safe(e), true);
        }
    }

    private void emit(Bundle bundle, boolean finalResult) {
        String text = bestText(bundle);
        if (text.isEmpty()) {
            if (finalResult) finishCurrent(false);
            return;
        }
        if (!finalResult) {
            postStatus("Android 系统流式 · " + preview(text));
            return;
        }
        consecutiveFailures = 0;
        if (!text.equals(lastText)) {
            lastText = text;
            String lang = normalizeLanguage(detectedLanguage);
            main.post(() -> {
                if (!closed) callback.onText(text, lang);
            });
        }
        finishCurrent(false);
    }

    private void finishCurrent(boolean fatal) {
        if (resultTimeout != null) {
            main.removeCallbacks(resultTimeout);
            resultTimeout = null;
        }
        closePipe();
        synchronized (this) {
            requestBusy = false;
            processNextLocked();
        }
    }

    private void failCurrent(String message, boolean fatalCandidate) {
        if (closed) return;
        if (resultTimeout != null) {
            main.removeCallbacks(resultTimeout);
            resultTimeout = null;
        }
        closePipe();
        consecutiveFailures++;
        boolean fatal = fatalCandidate && consecutiveFailures >= 2;
        callback.onError(message, fatal);
        synchronized (this) {
            requestBusy = false;
            if (!fatal) processNextLocked();
            else pending.clear();
        }
    }

    private String bestText(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() ? "" : values.get(0).trim();
    }

    @Override public void onReadyForSpeech(Bundle params) {
        postStatus("Android 系统外部 PCM · 已开始识别");
    }

    @Override public void onBeginningOfSpeech() {}

    @Override public void onRmsChanged(float rmsdB) {}

    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {
        postStatus("Android 系统外部 PCM · 等待结果");
    }

    @Override public void onError(int error) {
        boolean transientError = error == SpeechRecognizer.ERROR_NO_MATCH
            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
        failCurrent("系统外部 PCM 错误：" + errorName(error), !transientError);
    }

    @Override public void onResults(Bundle results) {
        emit(results, true);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        emit(partialResults, false);
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onLanguageDetection(Bundle results) {
        if (results == null) return;
        String language = results.getString(SpeechRecognizer.DETECTED_LANGUAGE, "");
        if (language != null && !language.isEmpty()) detectedLanguage = language;
    }

    private String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "权限不足";
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

    private void closePipe() {
        ParcelFileDescriptor r = readFd;
        readFd = null;
        ParcelFileDescriptor w = writeFd;
        writeFd = null;
        if (w != null) try { w.close(); } catch (Exception ignored) {}
        if (r != null) try { r.close(); } catch (Exception ignored) {}
    }

    private void postStatus(String message) {
        main.post(() -> {
            if (!closed) callback.onStatus(message);
        });
    }

    private void postError(String message, boolean fatal) {
        main.post(() -> {
            if (!closed) callback.onError(message, fatal);
        });
    }

    private static String normalizeLanguage(String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    private static String preview(String value) {
        String v = value == null ? "" : value.replace('\n', ' ').trim();
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }

    private static String safe(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override public void close() {
        closed = true;
        ready = false;
        synchronized (this) {
            pending.clear();
            speech.reset();
            requestBusy = false;
        }
        if (resultTimeout != null) main.removeCallbacks(resultTimeout);
        resultTimeout = null;
        closePipe();
        writer.shutdownNow();
        main.post(() -> {
            SpeechRecognizer r = recognizer;
            recognizer = null;
            if (r != null) {
                try { r.cancel(); } catch (Throwable ignored) {}
                try { r.destroy(); } catch (Throwable ignored) {}
            }
        });
    }
}
