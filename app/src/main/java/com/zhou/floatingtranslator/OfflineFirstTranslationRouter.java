package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import android.os.Handler;
import android.os.Looper;

/**
 * Offline-first smart translation router.
 *
 * Session-scoped automatic language routing:
 * - Text language is detected automatically by ML Kit Language ID.
 * - Foreign speech/text is translated to the configured target (the user's language).
 * - When the user speaks the target language, translation automatically reverses to the most
 *   recently detected partner language, with the configured source language as fallback.
 * - The old fixed source/target pair remains the safe fallback for short or undetermined text.
 *
 * This changes translation routing only. Audio capture remains fixed and is never switched here.
 */
public final class OfflineFirstTranslationRouter implements AutoCloseable {
    public interface Callback {
        void onSuccess(String translated, String engineName);
        void onError(String message);
    }

    private static final String PREFS = "floating_translator";
    private static final String PREF_AUTO_LANGUAGE = "auto_language_enabled";

    private final Context context;
    private final SharedPreferences prefs;
    private final String selectedEngine;
    private final String source;
    private final String target;
    private final boolean autoLanguage;
    private final LanguageIdentifier languageIdentifier;

    // Kept for cloud speech fallback, whose API expects a fixed configured pair.
    private final TranslationRouter speechRouter;
    private volatile boolean closed;
    private String partnerLanguage;
    private final Set<TranslationRouter> activeRouters = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public OfflineFirstTranslationRouter(Context context, String source, String target, String selectedEngine) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.selectedEngine = selectedEngine == null || selectedEngine.isEmpty()
            ? TranslationRouter.AUTO : selectedEngine;
        this.source = normalizeTag(source);
        this.target = normalizeTag(target);
        this.partnerLanguage = this.source;
        this.autoLanguage = prefs.getBoolean(PREF_AUTO_LANGUAGE, true);
        this.languageIdentifier = autoLanguage ? LanguageIdentification.getClient() : null;
        this.speechRouter = new TranslationRouter(this.context, this.source, this.target, this.selectedEngine);
    }

    public String selectedEngineName() {
        String base = TranslationRouter.AUTO.equals(selectedEngine)
            ? "自动 · ML Kit 离线优先"
            : TranslationRouter.engineLabel(selectedEngine);
        return autoLanguage ? base + " · 自动识别语言/双向" : base;
    }

    public boolean hasYoudaoCredentials() {
        return speechRouter.hasYoudaoCredentials();
    }

    public void translate(String text, Callback callback) {
        translate(text, "", callback);
    }

    /**
     * Prefer a language code already detected by a multilingual ASR. If absent, Language ID detects
     * the transcript locally. This avoids requiring the user to change source language every time.
     */
    public void translate(String text, String asrDetectedLanguage, Callback callback) {
        if (closed) {
            callback.onError("翻译引擎已经关闭");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            callback.onError("没有可翻译文字");
            return;
        }

        String cleaned = text.trim();
        if (!autoLanguage || languageIdentifier == null) {
            translatePair(cleaned, source, target, "", callback);
            return;
        }

        String fromAsr = normalizeTag(asrDetectedLanguage);
        if (isUsefulDetectedLanguage(fromAsr)) {
            routeDetected(cleaned, fromAsr, callback);
            return;
        }

        languageIdentifier.identifyLanguage(cleaned)
            .addOnSuccessListener(language -> {
                if (closed) return;
                String detected = normalizeTag(language);
                if (!isUsefulDetectedLanguage(detected)) detected = scriptHint(cleaned);
                routeDetected(cleaned, detected, callback);
            })
            .addOnFailureListener(error -> {
                if (closed) return;
                routeDetected(cleaned, scriptHint(cleaned), callback);
            });
    }

    private void routeDetected(String text, String detected, Callback callback) {
        String lang = normalizeTag(detected);
        if (!isSupportedTranslationLanguage(lang)) lang = "";

        // My language -> last detected partner language.
        if (!lang.isEmpty() && sameLanguage(lang, target)) {
            String partner = partnerLanguage;
            if (!isSupportedTranslationLanguage(partner) || sameLanguage(partner, target)) partner = source;
            translatePair(text, target, partner,
                " · 自动双向 " + target + "→" + partner + " · 识别" + lang, callback);
            return;
        }

        // Any supported foreign language -> my language, and remember it for the next reply.
        if (!lang.isEmpty() && !sameLanguage(lang, target)) {
            partnerLanguage = lang;
            translatePair(text, lang, target,
                " · 自动识别 " + lang + "→" + target, callback);
            return;
        }

        // Very short/uncertain text keeps the configured pair rather than guessing wildly.
        translatePair(text, source, target, " · 自动识别不确定，使用备用 " + source + "→" + target, callback);
    }

    public String partnerLanguage() { return partnerLanguage; }

    /** A button-labelled turn has a fixed direction even when language detection is uncertain. */
    public void translateTurn(String text, boolean partnerSpoke, String detectedLanguage, Callback callback) {
        if (closed) return;
        if (text == null || text.trim().isEmpty()) { callback.onError("没有可翻译文字"); return; }
        if (!partnerSpoke) {
            translatePair(text.trim(), target, partnerLanguage, " · 我→对方", callback);
            return;
        }
        String hint = normalizeTag(detectedLanguage);
        if (isSupportedTranslationLanguage(hint) && !sameLanguage(hint, target)) {
            partnerLanguage = hint;
            translatePair(text.trim(), hint, target, " · 对方→我", callback);
        } else if (languageIdentifier != null) {
            languageIdentifier.identifyLanguage(text.trim()).addOnSuccessListener(code -> {
                if (closed) return;
                String lang = normalizeTag(code);
                if (isSupportedTranslationLanguage(lang) && !sameLanguage(lang, target)) partnerLanguage = lang;
                translatePair(text.trim(), partnerLanguage, target, " · 对方→我", callback);
            }).addOnFailureListener(e -> {
                if (!closed) translatePair(text.trim(), partnerLanguage, target, " · 备用语言", callback);
            });
        } else translatePair(text.trim(), partnerLanguage, target, " · 对方→我", callback);
    }

    private void translatePair(String text, String from, String to, String suffix, Callback result) {
        if (closed) return;
        if (sameLanguage(from, to)) { result.onSuccess(text, "无需翻译" + suffix); return; }
        // One completion, bounded wait, and no callbacks into a stopped screen/service.
        final Set<TranslationRouter> requestRouters = new HashSet<>();
        final boolean[] finished = {false};
        final Runnable[] timeout = {null};
        Callback callback = new Callback() {
            private boolean finish() {
                if (finished[0]) return false;
                finished[0] = true;
                main.removeCallbacks(timeout[0]);
                for (TranslationRouter router : requestRouters) {
                    activeRouters.remove(router);
                    router.close();
                }
                return !closed;
            }
            public void onSuccess(String value, String engine) { if (finish()) result.onSuccess(value, engine); }
            public void onError(String error) { if (finish()) result.onError(error); }
        };
        timeout[0] = () -> callback.onError("翻译超时，请检查网络或先下载离线语言包后重试");
        main.postDelayed(timeout[0], 60000L);
        TranslationRouter local;
        TranslationRouter selected;
        try {
            local = new TranslationRouter(context, from, to, TranslationRouter.MLKIT);
            requestRouters.add(local);
            activeRouters.add(local);
            selected = TranslationRouter.MLKIT.equals(selectedEngine)
                ? local : new TranslationRouter(context, from, to, selectedEngine);
            requestRouters.add(selected);
            activeRouters.add(selected);
        } catch (Exception e) {
            callback.onError("语言路由初始化失败：" + safe(e));
            return;
        }

        if (!TranslationRouter.AUTO.equals(selectedEngine)) {
            TranslationRouter finalSelected = selected;
            finalSelected.translate(text, new TranslationRouter.Callback() {
                @Override public void onSuccess(String translated, String engineName) {
                    callback.onSuccess(translated, engineName + suffix);
                }

                @Override public void onError(String message) {
                    callback.onError(message);
                }
            });
            return;
        }

        TranslationRouter finalSelected = selected;
        TranslationRouter finalLocal = local;
        finalLocal.translate(text, new TranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                callback.onSuccess(translated, engineName + " · 离线优先" + suffix);
            }

            @Override public void onError(String localError) {
                if (closed || finished[0]) return;
                finalSelected.translate(text, new TranslationRouter.Callback() {
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

    private static boolean isSupportedTranslationLanguage(String code) {
        String normalized = normalizeTag(code);
        if (normalized.isEmpty() || "und".equals(normalized)) return false;
        for (LanguageOption option : LanguageOption.ALL) {
            if (sameLanguage(option.mlKitTag, normalized)) return true;
        }
        return false;
    }

    private static boolean isUsefulDetectedLanguage(String code) {
        String normalized = normalizeTag(code);
        return !normalized.isEmpty() && !"und".equals(normalized);
    }

    private static boolean sameLanguage(String a, String b) {
        String x = normalizeTag(a);
        String y = normalizeTag(b);
        if (x.equals(y)) return true;
        if (("fil".equals(x) || "tl".equals(x)) && ("fil".equals(y) || "tl".equals(y))) return true;
        if (("he".equals(x) || "iw".equals(x)) && ("he".equals(y) || "iw".equals(y))) return true;
        if (("id".equals(x) || "in".equals(x)) && ("id".equals(y) || "in".equals(y))) return true;
        return false;
    }

    private static String normalizeTag(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = normalized.indexOf('-');
        if (dash > 0) normalized = normalized.substring(0, dash);
        if ("fil".equals(normalized)) return "tl";
        if ("iw".equals(normalized)) return "he";
        if ("in".equals(normalized)) return "id";
        return normalized;
    }

    static String scriptHint(String text) {
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
        // Han is shared by Chinese/Japanese; Latin is shared by many languages.
        // Keep the configured pair instead of inventing English/Chinese.
        return "";
    }

    public void translateYoudaoSpeech(byte[] pcm16le, TranslationRouter.SpeechCallback callback) {
        speechRouter.translateYoudaoSpeech(pcm16le, callback);
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
            ? e.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        for (TranslationRouter router : activeRouters) router.close();
        activeRouters.clear();
        try { speechRouter.close(); } catch (Exception ignored) {}
        if (languageIdentifier != null) {
            try { languageIdentifier.close(); } catch (Exception ignored) {}
        }
    }
}
