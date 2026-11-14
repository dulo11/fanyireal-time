package com.zhou.floatingtranslator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Metadata for optional sherpa-onnx ASR model packs. Models are downloaded on demand. */
public final class OfflineAsrModelCatalog {
    public static final String SENSEVOICE = "sensevoice";
    public static final String REAZON_JA = "reazon_ja";
    public static final String PARAKEET_JA = "parakeet_ja";
    public static final String WHISPER_SMALL = "whisper_small";
    public static final String WHISPER_MEDIUM = "whisper_medium";
    public static final String QWEN3_ASR = "qwen3_asr_06b";
    public static final String OMNILINGUAL = "omnilingual_300m";
    public static final String QWEN3_17B = "qwen3_asr_17b";
    public static final String WHISPER_TURBO = "whisper_turbo";
    public static final String WHISPER_LARGE_V3 = "whisper_large_v3";
    public static final String PARAKEET_EN_V2 = "parakeet_en_v2";
    public static final String FUNASR_NANO = "funasr_nano";
    public static final String MOONSHINE_TINY_EN = "moonshine_tiny_en";
    public static final String MOONSHINE_BASE_EN = "moonshine_base_en";
    public static final String MOONSHINE_V2_EN = "moonshine_v2_en";
    public static final String MOONSHINE_V2_JA = "moonshine_v2_ja";
    public static final String MOONSHINE_V2_VI = "moonshine_v2_vi";
    public static final String ZIPFORMER_STREAM_EN = "zipformer_stream_en";

    public static final class Model {
        public final String id;
        public final String name;
        public final String remark;
        public final String url;
        public final String archiveRootHint;
        public final String approximateSize;
        public final String family;
        public final boolean supportsFp32;
        public final boolean supportsInt8;

        Model(String id, String name, String remark, String url,
              String archiveRootHint, String approximateSize, String family,
              boolean supportsFp32, boolean supportsInt8) {
            this.id = id;
            this.name = name;
            this.remark = remark;
            this.url = url;
            this.archiveRootHint = archiveRootHint;
            this.approximateSize = approximateSize;
            this.family = family;
            this.supportsFp32 = supportsFp32;
            this.supportsInt8 = supportsInt8;
        }

        public String precisionText() {
            if (supportsFp32 && supportsInt8) return "FP32 + INT8 可切换";
            if (supportsFp32) return "仅 FP32";
            return "当前官方包仅 INT8";
        }

        public String parameterText() {
            return OfflineAsrModelCatalog.parameterText(id);
        }

        public String optionLabel() {
            return name + "｜" + parameterText() + "｜" + precisionText() + "｜" + remark;
        }

        public boolean requiresIndividualFiles() {
            return QWEN3_17B.equals(id);
        }
    }

    private static final List<Model> MODELS = Collections.unmodifiableList(Arrays.asList(
        new Model(
            SENSEVOICE,
            "SenseVoice",
            "中英日韩粤｜速度快｜日英混合★★★★｜均衡推荐",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            "下载约 1.0 GB；解压约 1.1 GB（FP32 894 MB + INT8 228 MB）",
            "sensevoice",
            true,
            true
        ),
        new Model(
            REAZON_JA,
            "ReazonSpeech 日语",
            "日语直播★★★★★｜日语专项｜纯日语首选｜不推荐日英混说",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2",
            "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
            "解压约 740 MB（同包含 FP32 + INT8）",
            "transducer",
            true,
            true
        ),
        new Model(
            PARAKEET_JA,
            "NVIDIA Parakeet 日语 0.6B",
            "日语高精度★★★★★｜快语速/长句｜较吃性能｜混合语一般",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8.tar.bz2",
            "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
            "约 625 MB（INT8）",
            "nemo",
            false,
            true
        ),
        new Model(
            WHISPER_SMALL,
            "Whisper Small",
            "OpenAI 多语言｜日英混合★★★★★｜准确率高｜耗电中等",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            "sherpa-onnx-whisper-small",
            "同一模型包包含 FP32 + INT8",
            "whisper",
            true,
            true
        ),
        new Model(
            WHISPER_MEDIUM,
            "Whisper Medium",
            "OpenAI 多语言｜比 Small 更强｜日英混合★★★★★｜耗电/内存高",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-medium.tar.bz2",
            "sherpa-onnx-whisper-medium",
            "大型模型包；同时包含 FP32 + INT8",
            "whisper",
            true,
            true
        ),
        new Model(
            QWEN3_ASR,
            "Qwen3-ASR 0.6B",
            "多语言高精度｜日英混合★★★★★｜快语速/口语强",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            "解压约 1 GB（当前 GitHub 一键包为 INT8）",
            "qwen3",
            false,
            true
        ),
        new Model(
            OMNILINGUAL, "Omnilingual ASR 300M",
            "1600+ 语言｜小语种优先｜覆盖最广",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "约 350 MB（INT8）", "omnilingual", false, true
        ),
        new Model(
            QWEN3_17B, "Qwen3-ASR 1.7B（第三方 INT8）",
            "高精度/多语种｜大型模型占内存｜首次使用请先验证真机速度",
            "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8/resolve/main/",
            "", "约 2–3 GB｜逐文件下载｜第三方导出，请校验来源", "qwen3", false, true
        ),
        new Model(
            WHISPER_TURBO, "Whisper Large V3 Turbo",
            "多语言｜可锁定识别语言｜高精度/比 Large V3 快",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2",
            "sherpa-onnx-whisper-turbo", "压缩约 564 MB", "whisper", true, true
        ),
        new Model(
            WHISPER_LARGE_V3, "Whisper Large V3",
            "多语言｜最高精度档｜手机内存/推理开销很大",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-large-v3.tar.bz2",
            "sherpa-onnx-whisper-large-v3", "压缩约 1.07 GB", "whisper", true, true
        ),
        new Model(
            PARAKEET_EN_V2, "NVIDIA Parakeet TDT 0.6B V2（英语）",
            "英语专项｜高精度｜VAD 分段离线推理（非真流式）",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", "压缩约 482 MB", "nemo_transducer", false, true
        ),
        new Model(
            FUNASR_NANO, "Fun-ASR-Nano 2512（中英日）",
            "中英日及中文口音｜Qwen3 0.6B 解码器｜离线分段",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2",
            "sherpa-onnx-funasr-nano-int8-2025-12-30", "压缩约 842 MB", "funasr_nano", false, true
        ),
        new Model(
            MOONSHINE_TINY_EN, "Moonshine V1 Tiny（英语）",
            "英语轻量｜省电低延迟｜VAD 分段",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            "sherpa-onnx-moonshine-tiny-en-int8", "压缩约 108 MB", "moonshine_v1", false, true
        ),
        new Model(
            MOONSHINE_BASE_EN, "Moonshine V1 Base（英语）",
            "英语轻量｜比 Tiny 更准确｜VAD 分段",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-en-int8.tar.bz2",
            "sherpa-onnx-moonshine-base-en-int8", "压缩约 251 MB", "moonshine_v1", false, true
        ),
        new Model(
            MOONSHINE_V2_EN, "Moonshine V2 Base（英语）",
            "英语小语种专项｜量化版｜VAD 分段（不等于原厂 Streaming Small）",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2",
            "sherpa-onnx-moonshine-base-en-quantized-2026-02-27", "压缩约 111 MB", "moonshine_v2", false, true
        ),
        new Model(
            MOONSHINE_V2_JA, "Moonshine V2 Base（日语）",
            "日语直播轻量模型｜量化版｜VAD 分段",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-ja-quantized-2026-02-27.tar.bz2",
            "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27", "压缩约 104 MB", "moonshine_v2", false, true
        ),
        new Model(
            MOONSHINE_V2_VI, "Moonshine V2 Base（越南语）",
            "越南语直播轻量模型｜量化版｜VAD 分段",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-base-vi-quantized-2026-02-27.tar.bz2",
            "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27", "压缩约 107 MB", "moonshine_v2", false, true
        ),
        new Model(
            ZIPFORMER_STREAM_EN, "Zipformer Streaming 20M（英语）",
            "英语专用真正流式 ASR｜超轻量｜与离线模型分开的流式引擎",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", "压缩约 128 MB", "online_zipformer", true, true
        )
    ));

    /** Parameter counts are architectural values, independent of FP32/INT8 file size. */
    public static String parameterText(String id) {
        if (id == null) return "参数量未知";
        switch (id) {
            case SENSEVOICE: return "2.34亿参数";
            case REAZON_JA: return "参数量待核实";
            case PARAKEET_JA: return "6亿参数";
            case WHISPER_SMALL: return "2.44亿参数";
            case WHISPER_MEDIUM: return "7.69亿参数";
            case QWEN3_ASR: return "6亿参数";
            case OMNILINGUAL: return "约3亿参数";
            case QWEN3_17B: return "17亿参数";
            case WHISPER_TURBO: return "8.09亿参数";
            case WHISPER_LARGE_V3: return "15.5亿参数";
            case PARAKEET_EN_V2: return "6亿参数";
            case FUNASR_NANO: return "约8亿参数";
            case MOONSHINE_TINY_EN: return "0.27亿参数";
            case MOONSHINE_BASE_EN: return "0.62亿参数";
            case MOONSHINE_V2_EN:
            case MOONSHINE_V2_JA:
            case MOONSHINE_V2_VI: return "约1.23亿参数";
            case ZIPFORMER_STREAM_EN: return "0.2亿参数";
            default: return "参数量未知";
        }
    }

    /** The user requested these as well, but the current sherpa Android runtime
     * cannot simply load their original checkpoints. They are displayed as
     * unimplemented candidates, NEVER as downloadable working models.
     */
    public static String pendingModelsText() {
        return "仍需独立适配（尚不可选，不会假装能下载使用）："
            + "\n• NVIDIA Canary-Qwen 2.5B｜25亿参数｜需新的原生运行时"
            + "\n• Moonshine Streaming Small｜1.23亿参数｜原厂增量流式接口待接入"
            + "\n• Moonshine Streaming Medium｜2.45亿参数｜原厂增量流式接口待接入"
            + "\n目前已加入 Moonshine V1/V2 的 sherpa-onnx 可用分段版本，"
            + "并加入 Zipformer 真正流式作为低延迟替代。";
    }

    private OfflineAsrModelCatalog() {}

    public static List<Model> all() {
        return MODELS;
    }

    public static Model find(String id) {
        if (id == null) return null;
        for (Model model : MODELS) {
            if (model.id.equals(id)) return model;
        }
        return null;
    }

    public static boolean supportsFp32(String id) {
        Model model = find(id);
        return model != null && model.supportsFp32;
    }

    public static boolean supportsInt8(String id) {
        Model model = find(id);
        return model != null && model.supportsInt8;
    }
}
