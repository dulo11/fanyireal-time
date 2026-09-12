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

        Model(String id, String name, String remark, String url,
              String archiveRootHint, String approximateSize, String family) {
            this.id = id;
            this.name = name;
            this.remark = remark;
            this.url = url;
            this.archiveRootHint = archiveRootHint;
            this.approximateSize = approximateSize;
            this.family = family;
        }

        public String optionLabel() {
            return name + "｜" + remark;
        }
    }

    private static final List<Model> MODELS = Collections.unmodifiableList(Arrays.asList(
        new Model(
            SENSEVOICE,
            "SenseVoice INT8",
            "中英日韩粤｜速度快｜日英混合★★★★｜均衡推荐",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            "约 250 MB",
            "sensevoice"
        ),
        new Model(
            REAZON_JA,
            "ReazonSpeech 日语",
            "日语直播★★★★★｜日语专项｜纯日语首选｜不推荐日英混说",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2",
            "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
            "约 150-300 MB",
            "transducer"
        ),
        new Model(
            PARAKEET_JA,
            "NVIDIA Parakeet 日语 0.6B INT8",
            "日语高精度★★★★★｜快语速/长句｜较吃性能｜混合语一般",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8.tar.bz2",
            "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
            "约 625 MB+",
            "nemo"
        ),
        new Model(
            WHISPER_SMALL,
            "Whisper Small INT8",
            "OpenAI 多语言｜日英混合★★★★★｜准确率高｜耗电中等",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            "sherpa-onnx-whisper-small",
            "约 500 MB",
            "whisper"
        ),
        new Model(
            WHISPER_MEDIUM,
            "Whisper Medium INT8",
            "OpenAI 多语言｜日英混合★★★★★｜更高精度｜耗电/内存较高",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-medium.tar.bz2",
            "sherpa-onnx-whisper-medium",
            "约 1.5 GB",
            "whisper"
        ),
        new Model(
            QWEN3_ASR,
            "Qwen3-ASR 0.6B INT8",
            "多语言高精度｜日英混合★★★★★｜快语速/口语强｜约 1 GB 解压后",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            "约 1 GB",
            "qwen3"
        ),
        new Model(
            OMNILINGUAL,
            "Omnilingual ASR 300M INT8",
            "1600+ 语言｜小语种优先｜覆盖最广｜模型较大",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "约 350 MB",
            "omnilingual"
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
}
