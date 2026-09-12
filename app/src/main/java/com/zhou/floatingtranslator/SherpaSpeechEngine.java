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
 * Non-streaming high-accuracy sherpa-onnx ASR with lightweight voice segmentation.
 * The existing AudioRecord owns the audio source; this class never changes it.
 */
public final class SherpaSpeechEngine implements AutoCloseable {
    public static final String LANG_SINGLE = "single";
    public static final String LANG_JA_EN = "ja_en";
    public static final String LANG_ZH_EN = "zh_en";
    public static final String LANG_KO_EN = "ko_en";
    public static final String LANG_AUTO = "auto";

    public interface Callback {
        void onStatus(String message);
        void onReady(String engineName);
        void onText(String text, String detectedLanguage);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int SPEECH_PEAK = 120;
    private static final long END_SILENCE_MS = 550L;
    private static final long MIN_SEGMENT_MS = 450L;
    private static final long FORCE_SEGMENT_MS = 4200L;

    private final Context context;
    private final String modelId;
    private final String languageMode;
    private final String sourceLanguage;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream speech = new ByteArrayOutputStream();

    private volatile OfflineRecognizer recognizer;
    private volatile boolean ready;
    private volatile boolean closed;
    private boolean inSpeech;
    private long segmentMs;
    private long silenceMs;

    public SherpaSpeechEngine(Context context, String modelId, String languageMode,
                              String sourceLanguage, Callback callback) {
        this.context = context.getApplicationContext();
        this.modelId = modelId;
        this.languageMode = languageMode == null ? LANG_SINGLE : languageMode;
        this.sourceLanguage = sourceLanguage == null ? "ja" : sourceLanguage;
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
                postStatus("正在加载 " + meta.name + "……");
                OfflineRecognizer r = buildRecognizer(meta, modelDir);
                if (closed) {
                    r.release();
                    return;
                }
                recognizer = r;
                ready = true;
                main.post(() -> callback.onReady(meta.name));
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

        if ((silenceMs >= END_SILENCE_MS && segmentMs >= MIN_SEGMENT_MS)
            || segmentMs >= FORCE_SEGMENT_MS) {
            byte[] segment = speech.toByteArray();
            speech.reset();
            segmentMs = 0L;
            silenceMs = 0L;
            // If this was a forced endpoint, keep listening as the speaker may be continuous.
            inSpeech = voiced;
            if (segment.length >= SAMPLE_RATE) decode(segment);
        }
    }

    public synchronized void flush() {
        if (speech.size() >= SAMPLE_RATE) {
            byte[] segment = speech.toByteArray();
            speech.reset();
            segmentMs = 0L;
            silenceMs = 0L;
            inSpeech = false;
            decode(segment);
        }
    }

    private void decode(byte[] pcm) {
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
                String text = result == null || result.getText() == null ? "" : result.getText().trim();
                String lang = result == null || result.getLang() == null ? "" : result.getLang().trim();
                if (!text.isEmpty()) {
                    main.post(() -> callback.onText(text, lang));
                }
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

        OfflineModelConfig.Builder model = OfflineModelConfig.builder()
            .setNumThreads(recommendedThreads(modelId))
            .setDebug(false)
            .setProvider("cpu");

        switch (modelId) {
            case OfflineAsrModelCatalog.SENSEVOICE: {
                File onnx = require(findNamed(root, "model.int8.onnx", "model.onnx"), "SenseVoice model");
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineSenseVoiceModelConfig cfg = OfflineSenseVoiceModelConfig.builder()
                    .setModel(onnx.getAbsolutePath())
                    .setLanguage(senseVoiceLanguage())
                    .setInverseTextNormalization(true)
                    .build();
                model.setSenseVoice(cfg).setTokens(tokens.getAbsolutePath()).setModelType("sense_voice");
                break;
            }
            case OfflineAsrModelCatalog.REAZON_JA: {
                File encoder = require(findContains(root, "encoder", ".onnx", true), "Reazon encoder");
                File decoder = require(findContains(root, "decoder", ".onnx", false), "Reazon decoder");
                File joiner = require(findContains(root, "joiner", ".onnx", true), "Reazon joiner");
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineTransducerModelConfig cfg = OfflineTransducerModelConfig.builder()
                    .setEncoder(encoder.getAbsolutePath())
                    .setDecoder(decoder.getAbsolutePath())
                    .setJoiner(joiner.getAbsolutePath())
                    .build();
                model.setTransducer(cfg).setTokens(tokens.getAbsolutePath()).setModelType("transducer");
                break;
            }
            case OfflineAsrModelCatalog.PARAKEET_JA: {
                File onnx = require(findNamed(root, "model.int8.onnx", "model.onnx"), "Parakeet model");
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineNemoEncDecCtcModelConfig cfg = OfflineNemoEncDecCtcModelConfig.builder()
                    .setModel(onnx.getAbsolutePath()).build();
                model.setNemo(cfg).setTokens(tokens.getAbsolutePath()).setModelType("nemo_ctc");
                break;
            }
            case OfflineAsrModelCatalog.WHISPER_SMALL:
            case OfflineAsrModelCatalog.WHISPER_MEDIUM: {
                File encoder = require(findContains(root, "encoder", ".onnx", true), "Whisper encoder");
                File decoder = require(findContains(root, "decoder", ".onnx", true), "Whisper decoder");
                File tokens = require(findTokens(root), "Whisper tokens");
                OfflineWhisperModelConfig cfg = OfflineWhisperModelConfig.builder()
                    .setEncoder(encoder.getAbsolutePath())
                    .setDecoder(decoder.getAbsolutePath())
                    .setLanguage(whisperLanguage())
                    .setTask("transcribe")
                    .build();
                model.setWhisper(cfg).setTokens(tokens.getAbsolutePath()).setModelType("whisper");
                break;
            }
            case OfflineAsrModelCatalog.QWEN3_ASR: {
                File conv = require(findContains(root, "conv_frontend", ".onnx", false), "Qwen3 conv_frontend");
                File encoder = require(findContains(root, "encoder", ".onnx", true), "Qwen3 encoder");
                File decoder = require(findContains(root, "decoder", ".onnx", true), "Qwen3 decoder");
                File tokenizer = require(findDirectoryNamed(root, "tokenizer"), "Qwen3 tokenizer");
                OfflineQwen3AsrModelConfig cfg = OfflineQwen3AsrModelConfig.builder()
                    .setConvFrontend(conv.getAbsolutePath())
                    .setEncoder(encoder.getAbsolutePath())
                    .setDecoder(decoder.getAbsolutePath())
                    .setTokenizer(tokenizer.getAbsolutePath())
                    .setMaxTotalLen(512)
                    .setMaxNewTokens(160)
                    .build();
                model.setQwen3Asr(cfg).setModelType("qwen3_asr");
                break;
            }
            case OfflineAsrModelCatalog.OMNILINGUAL: {
                File onnx = require(findNamed(root, "model.int8.onnx", "model.onnx"), "Omnilingual model");
                File tokens = require(findTokens(root), "tokens.txt");
                OfflineOmnilingualAsrCtcModelConfig cfg = OfflineOmnilingualAsrCtcModelConfig.builder()
                    .setModel(onnx.getAbsolutePath()).build();
                model.setOmnilingual(cfg).setTokens(tokens.getAbsolutePath()).setModelType("omnilingual_ctc");
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的模型 " + modelId);
        }

        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(model.build())
            .setDecodingMethod("greedy_search")
            .build();
        return new OfflineRecognizer(config);
    }

    private int recommendedThreads(String id) {
        if (OfflineAsrModelCatalog.QWEN3_ASR.equals(id)
            || OfflineAsrModelCatalog.WHISPER_MEDIUM.equals(id)
            || OfflineAsrModelCatalog.PARAKEET_JA.equals(id)) return 4;
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
        OfflineRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.release(); } catch (Throwable ignored) {}
        }
        worker.shutdownNow();
    }
}
