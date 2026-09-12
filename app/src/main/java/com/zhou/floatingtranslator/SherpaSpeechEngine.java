package com.zhou.floatingtranslator;

import android.content.Context;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Non-streaming high-accuracy sherpa-onnx ASR with adaptive voice segmentation.
 * The existing AudioRecord/ROOT bridge owns the audio source; this class never changes it.
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

    private volatile OfflineRecognizer recognizer;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String loadedPrecision = "";
    private boolean inSpeech;
    private boolean currentSegmentHasOverlap;
    private long segmentMs;
    private long silenceMs;
    private String lastRawText = "";

    public SherpaSpeechEngine(Context context, String modelId, String languageMode,
                              String sourceLanguage, Callback callback) {
        this.context = context.getApplicationContext();
        this.modelId = modelId;
        this.languageMode = languageMode == null ? LANG_SINGLE : languageMode;
        this.sourceLanguage = sourceLanguage == null ? "ja" : sourceLanguage;
        String saved = this.context.getSharedPreferences("floating_translator", Context.MODE_PRIVATE)
            .getString("asr_precision", PRECISION_AUTO);
        this.precision = normalizePrecision(saved);
        String profile = this.context.getSharedPreferences("floating_translator", Context.MODE_PRIVATE)
            .getString("asr_conversation_profile", PROFILE_ACCURACY);
        this.conversationProfile = normalizeProfile(profile);
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
                OfflineRecognizer r = buildRecognizer(meta, modelDir);
                if (closed) {
                    r.release();
                    return;
                }
                recognizer = r;
                ready = true;
                String suffix = loadedPrecision.isEmpty() ? "" : " · " + loadedPrecision;
                main.post(() -> callback.onReady(meta.name + suffix + " · " + profileLabel()));
            } catch (Throwable e) {
                postError("模型加载失败：" + safe(e));
            }
        });
    }

    /** Accept 16 kHz, mono, PCM16 little-endian audio plus its observed peak. */
    public synchronized void acceptPcm(byte[] pcm, int length, int peak) {
        if (!isReady() || pcm == null || length <= 0) return;
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
        speech.write(pcm, 0, length);
        segmentMs += frameMs;

        boolean naturalEnd = silenceMs >= endSilenceMs() && segmentMs >= minSegmentMs();
        boolean forcedEnd = segmentMs >= forceSegmentMs();
        if (!naturalEnd && !forcedEnd) return;

        byte[] segment = speech.toByteArray();
        boolean segmentNeedsDedupe = currentSegmentHasOverlap;
        boolean keepOverlap = forcedEnd && !naturalEnd && voiced && overlapMs() > 0L;
        byte[] overlap = keepOverlap ? tailPcm(segment, overlapMs()) : new byte[0];

        speech.reset();
        if (overlap.length > 0) speech.write(overlap, 0, overlap.length);
        currentSegmentHasOverlap = overlap.length > 0;
        segmentMs = pcmDurationMs(overlap.length);
        silenceMs = 0L;
        inSpeech = keepOverlap;

        if (segment.length >= SAMPLE_RATE) decode(segment, segmentNeedsDedupe);
    }

    public synchronized void flush() {
        if (speech.size() >= SAMPLE_RATE) {
            byte[] segment = speech.toByteArray();
            boolean segmentNeedsDedupe = currentSegmentHasOverlap;
            speech.reset();
            segmentMs = 0L;
            silenceMs = 0L;
            inSpeech = false;
            currentSegmentHasOverlap = false;
            decode(segment, segmentNeedsDedupe);
        }
    }

    private void decode(byte[] pcm, boolean dedupeOverlap) {
        worker.execute(() -> {
            OfflineRecognizer r = recognizer;
            if (closed || r == null) return;
            OfflineStream stream = null;
            try {
                float[] samples = pcm16ToFloat(pcm);
                stream = r.createStream();
                stream.acceptWaveform(samples, SAMPLE_RATE);
                r.decode(stream);
                OfflineRecognizerResult result = r.getResult(stream);
                String raw = result == null || result.getText() == null ? "" : result.getText().trim();
                String lang = result == null || result.getLang() == null ? "" : result.getLang().trim();
                if (raw.isEmpty()) return;
                String text = dedupeOverlap ? stripRepeatedOverlap(lastRawText, raw) : raw;
                lastRawText = raw;
                if (!text.isEmpty()) main.post(() -> callback.onText(text, lang));
            } catch (Throwable e) {
                postError("识别失败：" + safe(e));
            } finally {
                if (stream != null) {
                    try { stream.release(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private OfflineRecognizer buildRecognizer(OfflineAsrModelCatalog.Model meta, File installedDir) {
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
                cfg.setMaxTotalLen(512);
                cfg.setMaxNewTokens(PROFILE_ACCURACY.equals(conversationProfile) ? 220 : 160);
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
        if (PROFILE_ACCURACY.equals(conversationProfile)
            && OfflineAsrModelCatalog.REAZON_JA.equals(modelId)) {
            config.setDecodingMethod("modified_beam_search");
        } else {
            config.setDecodingMethod("greedy_search");
        }
        return new OfflineRecognizer(null, config);
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

    private String profileLabel() {
        if (PROFILE_LOW_LATENCY.equals(conversationProfile)) return "低延迟";
        if (PROFILE_BALANCED.equals(conversationProfile)) return "均衡对话";
        return "高精度·快语速";
    }

    private static long pcmDurationMs(int bytes) {
        if (bytes <= 0) return 0L;
        return Math.round((bytes / 2.0) * 1000.0 / SAMPLE_RATE);
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

    /** Removes the exact text duplicated by the forced audio overlap. */
    private static String stripRepeatedOverlap(String previous, String current) {
        if (previous == null || current == null) return current == null ? "" : current.trim();
        String a = previous.trim();
        String b = current.trim();
        if (a.isEmpty() || b.isEmpty()) return b;
        int max = Math.min(32, Math.min(a.length(), b.length()));
        for (int n = max; n >= 2; n--) {
            if (a.regionMatches(a.length() - n, b, 0, n)) {
                return b.substring(n).trim();
            }
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
        loadedPrecision = file.getName().toLowerCase(Locale.ROOT).contains("int8")
            ? "INT8"
            : "FP32";
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
        currentSegmentHasOverlap = false;
        OfflineRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.release(); } catch (Throwable ignored) {}
        }
        worker.shutdownNow();
    }
}
