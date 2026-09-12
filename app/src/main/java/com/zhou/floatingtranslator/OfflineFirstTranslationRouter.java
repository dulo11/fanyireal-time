package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.Locale;

/**
 * Offline-first wrapper around TranslationRouter.
 *
 * Normal mode:
 *   AUTO = ML Kit first; only if the local model cannot translate do we try configured clouds.
 *
 * v0.6.0 automatic multi-language mode:
 *   When the existing ASR language mode is set to LANG_AUTO, text language is identified locally
 *   with ML Kit Language ID. If the utterance matches the selected target language, the router
 *   automatically reverses the selected pair. This turns an existing source/target pair into a
 *   practical two-way conversation pair without changing the fixed audio source.
 */
public final class OfflineFirstTranslationRouter implements AutoCloseable {
    public interface Callback {
        void onSuccess(String translated, String engineName);
        void onError(String message);
    }

    private static final String PREFS = "floating_translator";

    private final String selectedEngine;
    private final String source;
    private final String target;
    private final boolean bidirectional;

    private final TranslationRouter local;
    private final TranslationRouter selected;
    private final TranslationRouter reverseLocal;
    private final TranslationRouter reverseSelected;
    private final LanguageIdentifier languageIdentifier;

    private boolean closed;

    public OfflineFirstTranslationRouter(Context context, String source, String target, String selectedEngine) {
        String normalized = selectedEngine == null || selectedEngine.isEmpty()
            ? TranslationRouter.AUTO : selectedEngine;
        this.selectedEngine = normalized;
        this.source = normalizeTag(source);
        this.target = normalizeTag(target);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.bidirectional = SherpaSpeechEngine.LANG_AUTO.equals(
            prefs.getString("language_mode", SherpaSpeechEngine.LANG_SINGLE));

        this.local = new TranslationRouter(context, source, target, TranslationRouter.MLKIT);
        this.selected = TranslationRouter.MLKIT.equals(normalized)
            ? this.local
            : new TranslationRouter(context, source, target, normalized);

        if (bidirectional) {
            this.reverseLocal = new TranslationRouter(context, target, source, TranslationRouter.MLKIT);
            this.reverseSelected = TranslationRouter.MLKIT.equals(normalized)
                ? this.reverseLocal
                : new TranslationRouter(context, target, source, normalized);
            this.languageIdentifier = LanguageIdentification.getClient();
        } else {
            this.reverseLocal = null;
            this.reverseSelected = null;
            this.languageIdentifier = null;
        }
    }

    public String selectedEngineName() {
        String base = TranslationRouter.AUTO.equals(selectedEngine)
            ? "自动 · ML Kit 离线优先"
            : TranslationRouter.engineLabel(selectedEngine);
        return bidirectional ? base + " · 自动双向" : base;
    }

    public boolean hasYoudaoCredentials() {
        return selected.hasYoudaoCredentials();
    }

    public void translate(String text, Callback callback) {
        if (closed) {
            callback.onError("翻译引擎已经关闭");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            callback.onError("没有可翻译文字");
            return;
        }

        String cleaned = text.trim();
        if (!bidirectional || languageIdentifier == null) {
            translateDirection(cleaned, false, "", callback);
            return;
        }

        languageIdentifier.identifyLanguage(cleaned)
            .addOnSuccessListener(language -> {
                if (closed) return;
                String detected = normalizeTag(language);
                boolean reverse = shouldReverse(detected, cleaned);
                translateDirection(cleaned, reverse, detected, callback);
            })
            .addOnFailureListener(error -> {
                if (closed) return;
                // Language ID is a routing enhancement only. If it ever fails, keep the old
                // forward-only behaviour rather than failing a translation that would otherwise work.
                translateDirection(cleaned, false, "", callback);
            });
    }

    private void translateDirection(String text, boolean reverse, String detected, Callback callback) {
        TranslationRouter directionLocal = reverse ? reverseLocal : local;
        TranslationRouter directionSelected = reverse ? reverseSelected : selected;
        if (directionLocal == null || directionSelected == null) {
            directionLocal = local;
            directionSelected = selected;
            reverse = false;
        }

        final boolean finalReverse = reverse;
        final String suffix = directionSuffix(finalReverse, detected);

        if (!TranslationRouter.AUTO.equals(selectedEngine)) {
            directionSelected.translate(text, new TranslationRouter.Callback() {
                @Override public void onSuccess(String translated, String engineName) {
                    callback.onSuccess(translated, engineName + suffix);
                }

                @Override public void onError(String message) {
                    callback.onError(message);
                }
            });
            return;
        }

        TranslationRouter finalDirectionSelected = directionSelected;
        directionLocal.translate(text, new TranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                callback.onSuccess(translated, engineName + " · 离线优先" + suffix);
            }

            @Override public void onError(String localError) {
                // AUTO TranslationRouter skips cloud engines whose credentials are absent.
                finalDirectionSelected.translate(text, new TranslationRouter.Callback() {
                    @Override public void onSuccess(String translated, String engineName) {
                        callback.onSuccess(translated, engineName + " · 离线失败后兜底" + suffix);
                    }

                    @Override public void onError(String cloudError) {
                        callback.onError("ML Kit：" + localError + "；备用：" + cloudError);
                    }
                });
            }
        });
    }

    /**
     * Reverse only when the detected language confidently matches the configured target side.
     * For short/undetermined text, use script hints for Chinese/Japanese/Korean and otherwise
     * preserve the original forward direction.
     */
    private boolean shouldReverse(String detected, String text) {
        if (!detected.isEmpty() && !"und".equals(detected)) {
            if (sameLanguage(detected, target) && !sameLanguage(detected, source)) return true;
            if (sameLanguage(detected, source)) return false;
        }

        String script = scriptHint(text);
        if (!script.isEmpty()) {
            if (sameLanguage(script, target) && !sameLanguage(script, source)) return true;
            if (sameLanguage(script, source)) return false;
        }
        return false;
    }

    private String directionSuffix(boolean reverse, String detected) {
        if (!bidirectional) return "";
        String from = reverse ? target : source;
        String to = reverse ? source : target;
        String detectedText = detected == null || detected.isEmpty() || "und".equals(detected)
            ? "" : " · 识别" + detected;
        return " · 双向 " + from + "→" + to + detectedText;
    }

    private static boolean sameLanguage(String a, String b) {
        String x = normalizeTag(a);
        String y = normalizeTag(b);
        if (x.equals(y)) return true;
        // ML Kit can report Filipino as fil while translation models commonly use tl.
        if (("fil".equals(x) || "tl".equals(x)) && ("fil".equals(y) || "tl".equals(y))) return true;
        // Legacy Android/Java aliases.
        if (("he".equals(x) || "iw".equals(x)) && ("he".equals(y) || "iw".equals(y))) return true;
        if (("id".equals(x) || "in".equals(x)) && ("id".equals(y) || "in".equals(y))) return true;
        return false;
    }

    private static String normalizeTag(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = normalized.indexOf('-');
        if (dash > 0) normalized = normalized.substring(0, dash);
        return normalized;
    }

    private static String scriptHint(String text) {
        int kana = 0;
        int han = 0;
        int hangul = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u31f0' && c <= '\u31ff')) kana++;
            else if (c >= '\uac00' && c <= '\ud7af') hangul++;
            else if (c >= '\u4e00' && c <= '\u9fff') han++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '\u00c0' && c <= '\u024f')) latin++;
        }
        if (kana >= 1) return "ja";
        if (hangul >= 1) return "ko";
        if (han >= 2) return "zh";
        if (latin >= 3) return "en";
        return "";
    }

    public void translateYoudaoSpeech(byte[] pcm16le, TranslationRouter.SpeechCallback callback) {
        selected.translateYoudaoSpeech(pcm16le, callback);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { local.close(); } catch (Exception ignored) {}
        if (selected != local) {
            try { selected.close(); } catch (Exception ignored) {}
        }
        if (reverseLocal != null) {
            try { reverseLocal.close(); } catch (Exception ignored) {}
        }
        if (reverseSelected != null && reverseSelected != reverseLocal) {
            try { reverseSelected.close(); } catch (Exception ignored) {}
        }
        if (languageIdentifier != null) {
            try { languageIdentifier.close(); } catch (Exception ignored) {}
        }
    }
}
