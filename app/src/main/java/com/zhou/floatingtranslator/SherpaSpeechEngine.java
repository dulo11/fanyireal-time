package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Non-streaming high-accuracy sherpa-onnx ASR.
 * High-accuracy/balanced modes prefer Silero neural VAD and preserve short audio context.
 * Suspicious or long segments receive a second decode with a larger context window.
 * Audio source is owned by the normal/ROOT capture service and is never changed here.
 */
public final class SherpaSpeechEngine implements AutoCloseable {
    public static final String LANG_SINGLE = "single";
    public static final String LANG_JA_EN = "ja_en";
    public static final String LANG_ZH_EN = "zh_en";
    public static final String LANG_KO_EN = "ko_en";
    public static final String LANG_AUTO = "auto";

    public static final String PRECISION_AUTO = "auto";
    public static final String PRECISION_FP32 = "fp32";
    public static final String PRECISION_INT8 = "int8";

    public static final String PROFILE_ACCURACY = "accuracy";
    public static final String PROFILE_BALANCED = "balanced";
    public static final String PROFILE_LOW_LATENCY = "low_latency";

    public interface Callback {
        void onStatus(String message);
        void onReady(String engineName);
        void onText(String text, String detectedLanguage);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int SPEECH_PEAK = 120;
    private static final int VAD_WINDOW = 512;

    private final Context context;
    private final String modelId;
    private final String languageMode;
    private final String sourceLanguage;
    private final String precision;
    private final String conversationProfile;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream speech = new ByteArrayOutputStream();
    private final float[] vadWindow = new float[VAD_WINDOW];
    private final List<String> hotwords;
    private final float hotwordScore;

    private volatile OfflineRecognizer recognizer;
    private volatile Vad vad;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile boolean neuralVad;
    private volatile String loadedPrecision = "";
    private boolean inSpeech;
    private boolean currentSegmentHasOverlap;
    private long segmentMs;
    private long silenceMs;
    private int vadWindowFill;
    private String lastRawText = "";
    private float[] contextTail = new float[0];
    private float[] reviewTail = new float[0];

    public SherpaSpeechEngine(Context context, String modelId, String languageMode,
                              String sourceLanguage, Callback callback) {
        this.context = context.getApplicationContext();
        this.modelId = modelId;
        this.languageMode = languageMode == null ? LANG_SINGLE : languageMode;
        this.sourceLanguage = sourceLanguage == null ? "ja" : sourceLanguage;
        SharedPreferences prefs = this.context.getSharedPreferences("floating_translator", Context.MODE_PRIVATE);
        this.precision = normalizePrecision(prefs.getString("asr_precision", PRECISION_AUTO));
        this.conversationProfile = normalizeProfile(
            prefs.getString("asr_conversation_profile", PROFILE_ACCURACY));
        this.hotwords = parseHotwords(prefs.getString(AsrHotwordActivity.KEY_HOTWORDS, ""));
        this.hotwordScore = prefs.getFloat(AsrHotwordActivity.KEY_SCORE, 1.8f);
        this.callback = callback;
    }

    public boolean isReady() {
        return ready && recognizer != null && !closed;
    }

    public void prepare() {
        OfflineAsrModelCatalog.Model meta = OfflineAsrModelCatalog.find(modelId);
        if (meta == null) {
            callback.onError("未知 sherpa-onnx 模型：" + modelId);
            return;
        }
        OfflineModelStore store = new OfflineModelStore(context);
        if (!store.isInstalled(modelId)) {
            store.close();
            callback.onError(meta.name + " 尚未下载，请先到“离线模型中心”下载");
            return;
        }
        File modelDir = store.modelDir(modelId);
        store.close();
        worker.execute(() -> {
            try {
                postStatus("正在加载 " + meta.name + " · " + requestedPrecisionLabel(meta)
                    + " · " + profileLabel());
                OfflineRecognizer r;
                try {
                    r = buildRecognizer(meta, modelDir, true);
                } catch (Throwable hotwordFailure) {
                    // A malformed/native-unsupported hotword file must never make ASR unusable.
                    postStatus("热词原生偏置暂不可用，继续加载普通识别器");
                    r = buildRecognizer(meta, modelDir, false);
                }
                Vad preparedVad = null;
                if (!PROFILE_LOW_LATENCY.equals(conversationProfile)) {
                    File vadFile = VadModelStore.ensure(context, this::postStatus);
                    if (vadFile != null && !closed) {
                        try {
                            preparedVad = buildVad(vadFile);
                        } catch (Throwable e) {
                            postStatus("Silero VAD 加载失败，自动回退音量切句：" + safe(e));
                        }
                    }
                }
                if (closed) {
                    r.release();
                    if (preparedVad != null) preparedVad.release();
                    return;
                }
                recognizer = r;
                vad = preparedVad;
                neuralVad = preparedVad != null;
                ready = true;
                String suffix = loadedPrecision.isEmpty() ? "" : " · " + loadedPrecision;
                String vadSuffix = neuralVad ? " · Silero VAD" : " · 兼容切句";
                String hotwordSuffix = hotwords.isEmpty() ? "" : " · 热词" + hotwords.size() + "个";
                main.post(() -> callback.onReady(meta.name + suffix + " · " + profileLabel()
                    + vadSuffix + hotwordSuffix));
            } catch (Throwable e) {
                postError("模型加载失败：" + safe(e));
            }
        });
    }

    /** Accept 16 kHz, mono, PCM16 little-endian audio plus its observed peak. */
    public synchronized void acceptPcm(byte[] pcm, int length, int peak) {
        if (!isReady() || pcm == null || length <= 0) return;
        if (neuralVad && vad != null) {
            acceptNeuralVad(pcm, length);
        } else {
            acceptEnergyFallback(pcm, length, peak);
        }
    }

    private void acceptNeuralVad(byte[] pcm, int length) {
        Vad localVad = vad;
        if (localVad == null) return;
        int samples = Math.min(length, pcm.length) / 2;
        for (int i = 0; i < samples; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            short value = (short) ((hi << 8) | lo);
            vadWindow[vadWindowFill++] = value / 32768f;
            if (vadWindowFill == VAD_WINDOW) {
                localVad.acceptWaveform(vadWindow);
                vadWindowFill = 0;
                drainVadSegments(localVad);
            }
        }
    }

    private void drainVadSegments(Vad localVad) {
        while (!localVad.empty()) {
            SpeechSegment segment = localVad.front();
            float[] raw = segment == null ? null : segment.getSamples();
            if (raw != null && raw.length >= SAMPLE_RATE / 2) {
                float[] primaryPrefix = contextTail;
                float[] reviewPrefix = reviewTail;
                float[] primary = merge(primaryPrefix, raw);
                float[] review = merge(reviewPrefix, raw);
                boolean dedupe = primaryPrefix.length > 0;
                long rawMs = sampleDurationMs(raw.length);
                boolean forceReview = PROFILE_ACCURACY.equals(conversationProfile) && rawMs >= 4200L;
                queueDecode(primary, dedupe, review, forceReview, rawMs);
                contextTail = tailSamples(raw, contextMs());
                reviewTail = tailSamples(raw, reviewContextMs());
            }
            localVad.pop();
        }
    }

    private void acceptEnergyFallback(byte[] pcm, int length, int peak) {
        long frameMs = Math.max(1L, Math.round(length / 2.0 * 1000.0 / SAMPLE_RATE));
        boolean voiced = peak >= SPEECH_PEAK;
        if (voiced) {
            if (!inSpeech) {
                inSpeech = true;
                currentSegmentHasOverlap = false;
                segmentMs = 0L;
                silenceMs = 0L;
                speech.reset();
            }
            silenceMs = 0L;
        } else if (inSpeech) {
            silenceMs += frameMs;
        }
        if (!inSpeech) return;
        speech.write(pcm, 0, Math.min(length, pcm.length));
        segmentMs += frameMs;

        boolean naturalEnd = silenceMs >= endSilenceMs() && segmentMs >= minSegmentMs();
        boolean forcedEnd = segmentMs >= forceSegmentMs();
        if (!naturalEnd && !forcedEnd) return;

        byte[] segment = speech.toByteArray();
        boolean dedupe = currentSegmentHasOverlap;
        boolean keepOverlap = forcedEnd && !naturalEnd && voiced && overlapMs() > 0L;
        byte[] overlap = keepOverlap ? tailPcm(segment, overlapMs()) : new byte[0];
        float[] reviewPrefix = reviewTail;

        speech.reset();
        if (overlap.length > 0) speech.write(overlap, 0, overlap.length);
        currentSegmentHasOverlap = overlap.length > 0;
        segmentMs = pcmDurationMs(overlap.length);
        silenceMs = 0L;
        inSpeech = keepOverlap;

        if (segment.length >= SAMPLE_RATE) {
            float[] raw = pcm16ToFloat(segment);
            float[] review = merge(reviewPrefix, raw);
            queueDecode(raw, dedupe, review,
                PROFILE_ACCURACY.equals(conversationProfile) && (forcedEnd || segmentMs >= 4200L),
                pcmDurationMs(segment.length));
            reviewTail = tailSamples(raw, reviewContextMs());
        }
    }

    public synchronized void flush() {
        Vad localVad = vad;
        if (neuralVad && localVad != null) {
            if (vadWindowFill > 0) {
                float[] tail = new float[VAD_WINDOW];
                System.arraycopy(vadWindow, 0, tail, 0, vadWindowFill);
                localVad.acceptWaveform(tail);
                vadWindowFill = 0;
            }
            localVad.flush();
            drainVadSegments(localVad);
            return;
        }
        if (speech.size() >= SAMPLE_RATE) {
            byte[] segment = speech.toByteArray();
            boolean dedupe = currentSegmentHasOverlap;
            speech.reset();
            segmentMs = 0L;
            silenceMs = 0L;
            inSpeech = false;
            currentSegmentHasOverlap = false;
            float[] raw = pcm16ToFloat(segment);
            queueDecode(raw, dedupe, merge(reviewTail, raw), false, pcmDurationMs(segment.length));
            reviewTail = tailSamples(raw, reviewContextMs());
        }
    }

    private void queueDecode(float[] primarySamples, boolean dedupeOverlap,
                             float[] reviewSamples, boolean forceReview, long rawDurationMs) {
        worker.execute(() -> {
            OfflineRecognizer r = recognizer;
            if (closed || r == null || primarySamples == null || primarySamples.length == 0) return;
            try {
                Decoded first = decodeOnce(r, primarySamples);
                String firstVisible = dedupeOverlap
                    ? stripRepeatedOverlap(lastRawText, first.text) : first.text;
                Decoded chosen = first;
                String chosenVisible = firstVisible;

                boolean shouldReview = PROFILE_ACCURACY.equals(conversationProfile)
                    && reviewSamples != null
                    && reviewSamples.length > primarySamples.length
                    && (forceReview || lowQuality(firstVisible, rawDurationMs));
                if (shouldReview) {
                    Decoded second = decodeOnce(r, reviewSamples);
                    String secondVisible = stripRepeatedOverlap(lastRawText, second.text);
                    double firstScore = qualityScore(firstVisible, rawDurationMs);
                    double secondScore = qualityScore(secondVisible, rawDurationMs);
                    if (!secondVisible.isEmpty() && secondScore > firstScore + 0.6d) {
                        chosen = second;
                        chosenVisible = secondVisible;
                        postStatus("低质量/长句已完成二次上下文校正");
                    }
                }

                if (chosen.text == null || chosen.text.isEmpty()) return;
                lastRawText = chosen.text;
                String out = chosenVisible == null ? "" : chosenVisible.trim();
                if (!out.isEmpty()) {
                    String lang = chosen.lang == null ? "" : chosen.lang;
                    main.post(() -> callback.onText(out, lang));
                }
            } catch (Throwable e) {
                postError("识别失败：" + safe(e));
            }
        });
    }

    private static final class Decoded {
        final String text;
        final String lang;
        Decoded(String text, String lang) {
            this.text = text == null ? "" : text.trim();
            this.lang = lang == null ? "" : lang.trim();
        }
    }

    private static Decoded decodeOnce(OfflineRecognizer r, float[] samples) {
        OfflineStream stream = null;
        try {
            stream = r.createStream();
            stream.acceptWaveform(samples, SAMPLE_RATE);
            r.decode(stream);
            OfflineRecognizerResult result = r.getResult(stream);
            return new Decoded(result == null ? "" : result.getText(), result == null ? "" : result.getLang());
        } finally {
            if (stream != null) {
                try { stream.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean lowQuality(String text, long durationMs) {
        String compact = compactText(text);
        if (compact.isEmpty()) return true;
        if (durationMs >= 1800L && compact.length() <= 2) return true;
        if (durationMs >= 3500L && compact.length() < 5) return true;
        return maxRepeatedRun(compact) >= 5;
    }

    private double qualityScore(String text, long durationMs) {
        String compact = compactText(text);
        if (compact.isEmpty()) return -100d;
        double score = Math.min(60, compact.length());
        if (durationMs >= 2500L && compact.length() <= 3) score -= 18d;
        int run = maxRepeatedRun(compact);
        if (run >= 4) score -= run * 2.5d;
        for (String word : hotwords) {
            if (!word.isEmpty() && text.contains(word)) score += 5d;
        }
        return score;
    }

    private static String compactText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s\\p{Punct}。！？、，．・…]+", "");
    }

    private static int maxRepeatedRun(String value) {
        if (value == null || value.isEmpty()) return 0;
        int best = 1;
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == value.charAt(i - 1)) run++;
            else run = 1;
            if (run > best) best = run;
        }
        return best;
    }

    private OfflineRecognizer buildRecognizer(OfflineAsrModelCatalog.Model meta, File installedDir,
                                               boolean allowNativeHotwords) {
        File root = OfflineModelStore.findPayloadRoot(installedDir, meta.archiveRootHint);
        if (root == null) throw new IllegalStateException("模型目录不存在");

        OfflineModelConfig model = new OfflineModelConfig();
        model.setNumThreads(recommendedThreads(modelId));
        model.setDebug(false);
        model.setProvider("cpu");

        switch (modelId) {
            case OfflineAsrModelCatalog.SENSEVOICE: {
                File onnx = require(findNamedPreferred(root, "model.onnx", "model.int8.onnx"), "SenseVoice model");
                markPrecision(onnx);
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineSenseVoiceModelConfig cfg = new OfflineSenseVoiceModelConfig();
                cfg.setModel(onnx.getAbsolutePath());
                cfg.setLanguage(senseVoiceLanguage());
                cfg.setUseInverseTextNormalization(true);
                model.setSenseVoice(cfg);
                model.setTokens(tokens.getAbsolutePath());
                model.setModelType("sense_voice");
                break;
            }
            case OfflineAsrModelCatalog.REAZON_JA: {
                File encoder = require(findContainsPreferred(root, "encoder", ".onnx"), "Reazon encoder");
                File decoder = require(findContainsPreferred(root, "decoder", ".onnx"), "Reazon decoder");
                File joiner = require(findContainsPreferred(root, "joiner", ".onnx"), "Reazon joiner");
                markPrecision(encoder);
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineTransducerModelConfig cfg = new OfflineTransducerModelConfig();
                cfg.setEncoder(encoder.getAbsolutePath());
                cfg.setDecoder(decoder.getAbsolutePath());
                cfg.setJoiner(joiner.getAbsolutePath());
                model.setTransducer(cfg);
                model.setTokens(tokens.getAbsolutePath());
                model.setModelType("transducer");
                break;
            }
            case OfflineAsrModelCatalog.PARAKEET_JA: {
                File onnx = require(findNamed(root, "model.int8.onnx", "model.onnx"), "Parakeet model");
                markPrecision(onnx);
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineNemoEncDecCtcModelConfig cfg = new OfflineNemoEncDecCtcModelConfig();
                cfg.setModel(onnx.getAbsolutePath());
                model.setNemo(cfg);
                model.setTokens(tokens.getAbsolutePath());
                model.setModelType("nemo_ctc");
                break;
            }
            case OfflineAsrModelCatalog.WHISPER_SMALL:
            case OfflineAsrModelCatalog.WHISPER_MEDIUM: {
                File encoder = require(findContainsPreferred(root, "encoder", ".onnx"), "Whisper encoder");
                File decoder = require(findContainsPreferred(root, "decoder", ".onnx"), "Whisper decoder");
                markPrecision(encoder);
                File tokens = require(findTokens(root), "Whisper tokens");
                OfflineWhisperModelConfig cfg = new OfflineWhisperModelConfig();
                cfg.setEncoder(encoder.getAbsolutePath());
                cfg.setDecoder(decoder.getAbsolutePath());
                cfg.setLanguage(whisperLanguage());
                cfg.setTask("transcribe");
                model.setWhisper(cfg);
                model.setTokens(tokens.getAbsolutePath());
                model.setModelType("whisper");
                break;
            }
            case OfflineAsrModelCatalog.QWEN3_ASR: {
                File conv = require(findContains(root, "conv_frontend", ".onnx", false), "Qwen3 conv_frontend");
                File encoder = require(findContains(root, "encoder", ".onnx", true), "Qwen3 encoder");
                File decoder = require(findContains(root, "decoder", ".onnx", true), "Qwen3 decoder");
                markPrecision(encoder);
                File tokenizer = require(findDirectoryNamed(root, "tokenizer"), "Qwen3 tokenizer");
                OfflineQwen3AsrModelConfig cfg = new OfflineQwen3AsrModelConfig();
                cfg.setConvFrontend(conv.getAbsolutePath());
                cfg.setEncoder(encoder.getAbsolutePath());
                cfg.setDecoder(decoder.getAbsolutePath());
                cfg.setTokenizer(tokenizer.getAbsolutePath());
                cfg.setMaxTotalLen(PROFILE_ACCURACY.equals(conversationProfile) ? 640 : 512);
                cfg.setMaxNewTokens(PROFILE_ACCURACY.equals(conversationProfile) ? 260 : 160);
                model.setQwen3Asr(cfg);
                model.setModelType("qwen3_asr");
                break;
            }
            case OfflineAsrModelCatalog.OMNILINGUAL: {
                File onnx = require(findNamed(root, "model.int8.onnx", "model.onnx"), "Omnilingual model");
                markPrecision(onnx);
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineOmnilingualAsrCtcModelConfig cfg = new OfflineOmnilingualAsrCtcModelConfig();
                cfg.setModel(onnx.getAbsolutePath());
                model.setOmnilingual(cfg);
                model.setTokens(tokens.getAbsolutePath());
                model.setModelType("omnilingual_ctc");
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的模型 " + modelId);
        }

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        boolean reazonAccuracy = PROFILE_ACCURACY.equals(conversationProfile)
            && OfflineAsrModelCatalog.REAZON_JA.equals(modelId);
        config.setDecodingMethod(reazonAccuracy ? "modified_beam_search" : "greedy_search");
        if (reazonAccuracy) config.setMaxActivePaths(8);
        if (allowNativeHotwords && reazonAccuracy && !hotwords.isEmpty()) {
            File hotwordFile = writeHotwordsFile();
            if (hotwordFile != null) {
                config.setHotwordsFile(hotwordFile.getAbsolutePath());
                config.setHotwordsScore(hotwordScore);
            }
        }
        return new OfflineRecognizer(null, config);
    }

    private Vad buildVad(File modelFile) {
        float threshold = PROFILE_ACCURACY.equals(conversationProfile) ? 0.45f : 0.50f;
        float minSilence = PROFILE_ACCURACY.equals(conversationProfile) ? 0.58f : 0.42f;
        float minSpeech = PROFILE_ACCURACY.equals(conversationProfile) ? 0.18f : 0.22f;
        float maxSpeech = PROFILE_ACCURACY.equals(conversationProfile) ? 11.5f : 7.5f;
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
            .setModel(modelFile.getAbsolutePath())
            .setThreshold(threshold)
            .setMinSilenceDuration(minSilence)
            .setMinSpeechDuration(minSpeech)
            .setWindowSize(VAD_WINDOW)
            .setMaxSpeechDuration(maxSpeech)
            .build();
        VadModelConfig config = VadModelConfig.builder()
            .setSileroVadModelConfig(silero)
            .setSampleRate(SAMPLE_RATE)
            .setNumThreads(1)
            .setDebug(false)
            .setProvider("cpu")
            .build();
        return new Vad(config);
    }

    private File writeHotwordsFile() {
        if (hotwords.isEmpty()) return null;
        try {
            File file = new File(context.getFilesDir(), "asr-hotwords.txt");
            StringBuilder out = new StringBuilder();
            for (String word : hotwords) out.append(word).append('\n');
            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private long endSilenceMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 360L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 560L;
        return 780L;
    }

    private long minSegmentMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 320L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 480L;
        return 700L;
    }

    private long forceSegmentMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 3600L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 6500L;
        return 10000L;
    }

    private long overlapMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 0L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 450L;
        return 900L;
    }

    private long contextMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 0L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 320L;
        return 700L;
    }

    private long reviewContextMs() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return 0L;
        if (PROFILE_BALANCED.equals(conversationProfile)) return 700L;
        return 1700L;
    }

    private String profileLabel() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return "低延迟";
        if (PROFILE_BALANCED.equals(conversationProfile)) return "均衡对话";
        return "高精度·快语速";
    }

    private static long pcmDurationMs(int bytes) {
        if (bytes <= 0) return 0L;
        return Math.round((bytes / 2.0) * 1000.0 / SAMPLE_RATE);
    }

    private static long sampleDurationMs(int samples) {
        if (samples <= 0) return 0L;
        return Math.round(samples * 1000.0 / SAMPLE_RATE);
    }

    private static byte[] tailPcm(byte[] pcm, long durationMs) {
        if (pcm == null || pcm.length == 0 || durationMs <= 0L) return new byte[0];
        long wanted = Math.round(SAMPLE_RATE * 2.0 * durationMs / 1000.0);
        int count = (int) Math.min((long) pcm.length, Math.max(0L, wanted));
        count -= count % 2;
        if (count <= 0) return new byte[0];
        byte[] out = new byte[count];
        System.arraycopy(pcm, pcm.length - count, out, 0, count);
        return out;
    }

    private static float[] tailSamples(float[] samples, long durationMs) {
        if (samples == null || samples.length == 0 || durationMs <= 0L) return new float[0];
        int wanted = (int) Math.min(samples.length,
            Math.max(0L, Math.round(SAMPLE_RATE * durationMs / 1000.0)));
        if (wanted <= 0) return new float[0];
        float[] out = new float[wanted];
        System.arraycopy(samples, samples.length - wanted, out, 0, wanted);
        return out;
    }

    private static float[] merge(float[] prefix, float[] samples) {
        if (prefix == null || prefix.length == 0) return samples == null ? new float[0] : samples.clone();
        if (samples == null || samples.length == 0) return prefix.clone();
        float[] out = new float[prefix.length + samples.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(samples, 0, out, prefix.length, samples.length);
        return out;
    }

    /** Removes exact text duplicated by preserved audio context/overlap. */
    private static String stripRepeatedOverlap(String previous, String current) {
        if (previous == null || current == null) return current == null ? "" : current.trim();
        String a = previous.trim();
        String b = current.trim();
        if (a.isEmpty() || b.isEmpty()) return b;
        int max = Math.min(48, Math.min(a.length(), b.length()));
        for (int n = max; n >= 2; n--) {
            if (a.regionMatches(a.length() - n, b, 0, n)) return b.substring(n).trim();
        }
        return b;
    }

    private String requestedPrecisionLabel(OfflineAsrModelCatalog.Model meta) {
        if (PRECISION_FP32.equals(precision)) {
            return meta.supportsFp32 ? "请求 FP32 原始权重" : "请求 FP32，但该下载包只有 INT8，将自动回退";
        }
        if (PRECISION_INT8.equals(precision)) return "请求 INT8 手机优化";
        return "自动精度（INT8 优先）";
    }

    private int recommendedThreads(String id) {
        if (PROFILE_ACCURACY.equals(conversationProfile)) return 4;
        if (OfflineAsrModelCatalog.QWEN3_ASR.equals(id)
            || OfflineAsrModelCatalog.WHISPER_MEDIUM.equals(id)
            || OfflineAsrModelCatalog.PARAKEET_JA.equals(id)) return 4;
        if (PRECISION_FP32.equals(precision)) return 4;
        return 2;
    }

    private String senseVoiceLanguage() {
        if (!LANG_SINGLE.equals(languageMode)) return "auto";
        switch (sourceLanguage) {
            case "zh": return "zh";
            case "ja": return "ja";
            case "ko": return "ko";
            case "en": return "en";
            default: return "auto";
        }
    }

    private String whisperLanguage() {
        if (!LANG_SINGLE.equals(languageMode)) return "";
        switch (sourceLanguage) {
            case "zh": return "zh";
            case "ja": return "ja";
            case "ko": return "ko";
            case "vi": return "vi";
            case "tl": return "tl";
            case "ms": return "ms";
            case "th": return "th";
            case "id": return "id";
            case "en": return "en";
            default: return "";
        }
    }

    private static float[] pcm16ToFloat(byte[] pcm) {
        int count = pcm.length / 2;
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            short value = (short) ((hi << 8) | lo);
            samples[i] = value / 32768f;
        }
        return samples;
    }

    private File findNamedPreferred(File root, String fp32Name, String int8Name) {
        if (PRECISION_FP32.equals(precision)) {
            File fp32 = findNamed(root, fp32Name);
            if (fp32 != null) return fp32;
            return findNamed(root, int8Name);
        }
        File int8 = findNamed(root, int8Name);
        if (int8 != null) return int8;
        return findNamed(root, fp32Name);
    }

    private File findContainsPreferred(File root, String contains, String suffix) {
        if (PRECISION_FP32.equals(precision)) {
            File fp32 = findContainsExactPrecision(root, contains, suffix, false);
            if (fp32 != null) return fp32;
            return findContainsExactPrecision(root, contains, suffix, true);
        }
        File int8 = findContainsExactPrecision(root, contains, suffix, true);
        if (int8 != null) return int8;
        return findContainsExactPrecision(root, contains, suffix, false);
    }

    private static File findContainsExactPrecision(File root, String contains, String suffix, boolean int8) {
        String c = contains.toLowerCase(Locale.ROOT);
        String s = suffix.toLowerCase(Locale.ROOT);
        return OfflineModelStore.findFirst(root, f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!f.isFile() || !n.contains(c) || !n.endsWith(s)) return false;
            boolean isInt8 = n.contains("int8");
            return int8 == isInt8;
        });
    }

    private void markPrecision(File file) {
        if (file == null) return;
        loadedPrecision = file.getName().toLowerCase(Locale.ROOT).contains("int8") ? "INT8" : "FP32";
        if (PRECISION_FP32.equals(precision) && "INT8".equals(loadedPrecision)) {
            loadedPrecision += "（该模型包无 FP32，已回退）";
        }
    }

    private static String normalizePrecision(String value) {
        if (PRECISION_FP32.equals(value) || PRECISION_INT8.equals(value)) return value;
        return PRECISION_AUTO;
    }

    private static String normalizeProfile(String value) {
        if (PROFILE_BALANCED.equals(value) || PROFILE_LOW_LATENCY.equals(value)) return value;
        return PROFILE_ACCURACY;
    }

    private static List<String> parseHotwords(String raw) {
        ArrayList<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\\r?\\n")) {
            String word = line.trim();
            if (!word.isEmpty() && !out.contains(word)) out.add(word);
            if (out.size() >= 200) break;
        }
        return out;
    }

    private static File findNamed(File root, String... names) {
        for (String name : names) {
            File found = OfflineModelStore.findFirst(root,
                f -> f.isFile() && f.getName().equalsIgnoreCase(name));
            if (found != null) return found;
        }
        return null;
    }

    private static File findTokens(File root) {
        File exact = findNamed(root, "tokens.txt", "small-tokens.txt", "medium-tokens.txt");
        if (exact != null) return exact;
        return OfflineModelStore.findFirst(root,
            f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).contains("tokens")
                && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt"));
    }

    private static File findContains(File root, String contains, String suffix, boolean preferInt8) {
        String c = contains.toLowerCase(Locale.ROOT);
        String s = suffix.toLowerCase(Locale.ROOT);
        if (preferInt8) {
            File int8 = OfflineModelStore.findFirst(root, f -> {
                String n = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && n.contains(c) && n.contains("int8") && n.endsWith(s);
            });
            if (int8 != null) return int8;
        }
        return OfflineModelStore.findFirst(root, f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && n.contains(c) && n.endsWith(s);
        });
    }

    private static File findDirectoryNamed(File root, String name) {
        return OfflineModelStore.findFirst(root,
            f -> f.isDirectory() && f.getName().equalsIgnoreCase(name));
    }

    private static File require(File file, String what) {
        if (file == null) throw new IllegalStateException("缺少 " + what);
        return file;
    }

    private void postStatus(String message) {
        main.post(() -> callback.onStatus(message));
    }

    private void postError(String message) {
        main.post(() -> callback.onError(message));
    }

    private static String safe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        ready = false;
        speech.reset();
        lastRawText = "";
        contextTail = new float[0];
        reviewTail = new float[0];
        currentSegmentHasOverlap = false;
        Vad localVad = vad;
        vad = null;
        neuralVad = false;
        if (localVad != null) {
            try { localVad.release(); } catch (Throwable ignored) {}
        }
        OfflineRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.release(); } catch (Throwable ignored) {}
        }
        worker.shutdownNow();
    }
}
