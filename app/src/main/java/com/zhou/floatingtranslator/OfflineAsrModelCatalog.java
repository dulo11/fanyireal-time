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

        public String optionLabel() {
            return name + "｜" + precisionText() + "｜" + remark;
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
            OMNILINGUAL,
            "Omnilingual ASR 300M",
            "1600+ 语言｜小语种优先｜覆盖最广",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "约 350 MB（当前下载包为 INT8）",
            "omnilingual",
            false,
            true
        )
    ));

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
