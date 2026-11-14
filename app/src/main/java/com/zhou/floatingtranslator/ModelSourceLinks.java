package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * Human-auditable upstream projects (developers) versus deployment archives.
 * Visiting a URL is always an explicit action; this class never fetches or
 * transmits anything automatically.
 */
public final class ModelSourceLinks {
    private ModelSourceLinks() {}

    public static String officialModelUrl(String id) {
        switch (id == null ? "" : id) {
            case OfflineAsrModelCatalog.SENSEVOICE:
                return "https://github.com/QwenAudio/SenseVoice";
            case OfflineAsrModelCatalog.REAZON_JA:
                return "https://github.com/reazon-research/ReazonSpeech";
            case OfflineAsrModelCatalog.PARAKEET_JA:
            case OfflineAsrModelCatalog.PARAKEET_EN_V2:
                return "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2";
            case OfflineAsrModelCatalog.WHISPER_SMALL:
            case OfflineAsrModelCatalog.WHISPER_MEDIUM:
            case OfflineAsrModelCatalog.WHISPER_TURBO:
            case OfflineAsrModelCatalog.WHISPER_LARGE_V3:
                return "https://github.com/openai/whisper";
            case OfflineAsrModelCatalog.QWEN3_ASR:
            case OfflineAsrModelCatalog.QWEN3_17B:
                return "https://github.com/QwenLM/Qwen3-ASR";
            case OfflineAsrModelCatalog.OMNILINGUAL:
                return "https://github.com/facebookresearch/omnilingual-asr";
            case OfflineAsrModelCatalog.FUNASR_NANO:
                return "https://www.funasr.com/en/docs/model-selection.html";
            case OfflineAsrModelCatalog.MOONSHINE_TINY_EN:
            case OfflineAsrModelCatalog.MOONSHINE_BASE_EN:
            case OfflineAsrModelCatalog.MOONSHINE_V2_EN:
            case OfflineAsrModelCatalog.MOONSHINE_V2_JA:
            case OfflineAsrModelCatalog.MOONSHINE_V2_VI:
                return "https://github.com/moonshine-ai/moonshine";
            case OfflineAsrModelCatalog.ZIPFORMER_STREAM_EN:
                return "https://github.com/k2-fsa/sherpa-onnx";
            default:
                return "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/";
        }
    }

    public static String speechRecognizerUrl(String mode) {
        if (mode == null) return "";
        switch (mode) {
            case TranslationService.ASR_SYSTEM:
                return "https://developer.android.com/reference/android/speech/SpeechRecognizer";
            case TranslationService.ASR_VOSK:
                return "https://alphacephei.com/vosk/";
            case TranslationService.ASR_GROQ_LARGE:
            case TranslationService.ASR_GROQ_TURBO:
                return "https://console.groq.com/docs/speech-to-text";
            case TranslationService.ASR_CLOUDFLARE:
                return "https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/";
            case TranslationService.ASR_YOUDAO:
                return "https://ai.youdao.com/";
            default:
                return "";
        }
    }

    public static String translatorUrl(String engine) {
        if (engine == null) return "";
        String id = engine.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("azure")) {
            return "https://learn.microsoft.com/azure/ai-services/translator/";
        }
        if (id.contains("mlkit") || id.contains("ml_kit") || id.contains("offline")) {
            return "https://developers.google.com/ml-kit/language/translation/android";
        }
        if (id.contains("baidu")) return "https://fanyi-api.baidu.com/";
        if (id.contains("youdao")) return "https://ai.youdao.com/";
        if (id.contains("aliyun") || id.contains("alibaba")) {
            return "https://help.aliyun.com/zh/machine-translation/";
        }
        return "";
    }

    public static String packSourceUrl(OfflineAsrModelCatalog.Model model) {
        if (model == null || ImportedAsrModels.isImported(model.id)) return "";
        return model.requiresIndividualFiles()
            ? "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8"
            : model.url;
    }

    public static void open(Context context, String url) {
        if (url == null || !url.startsWith("https://")) {
            Toast.makeText(context, "此项目没有可验证的官网地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) {
            Toast.makeText(context, "无法打开链接：" + url, Toast.LENGTH_LONG).show();
        }
    }
}
