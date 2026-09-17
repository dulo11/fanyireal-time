package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline-first smart translation router.
 *
 * v0.7.2 routing rules:
 * - Realtime / ROOT / OCR translation is always "detected source -> configured target".
 *   Detecting the target language never reverses the direction.
 * - Face-to-face continuous conversation keeps session-scoped bidirectional routing:
 *   partner language -> my language, my language -> most recently detected partner language.
 * - A multilingual ASR language code wins over text language ID when it is usable.
 * - Very short/ambiguous text in normal translation keeps the configured source as a safe fallback.
 *
 * Audio capture remains owned by the caller and is never switched here.
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
    private final boolean conversationMode;
    private final LanguageIdentifier languageIdentifier;

    private final TranslationRouter speechRouter;
    private volatile boolean closed;
    private volatile String partnerLanguage;
    private volatile String lastDetectedLanguage = "";
    private String lastTargetLanguage = "";
    private final Set<TranslationRouter> activeRouters = Collections.synchronizedSet(new HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());

    public OfflineFirstTranslationRouter(Context caller, String source, String target, String selectedEngine) {
        this.conversationMode = caller instanceof FaceToFaceActivity;
        this.context = caller.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.selectedEngine = selectedEngine == null || selectedEngine.isEmpty()
            ? TranslationRouter.AUTO : selectedEngine;
        this.source = normalizeTag(source);
        this.target = normalizeTag(target);
        this.partnerLanguage = this.source;
        this.autoLanguage = prefs.getBoolean(PREF_AUTO_LANGUAGE, true);
        this.languageIdentifier = (autoLanguage || conversationMode) ? LanguageIdentification.getClient() : null;
        this.speechRouter = new TranslationRouter(this.context, this.source, this.target, this.selectedEngine);
    }

    public String selectedEngineName() {
        String base = TranslationRouter.AUTO.equals(selectedEngine)
            ? "自动 · ML Kit 离线优先"
            : TranslationRouter.engineLabel(selectedEngine);
        if (!autoLanguage) return base;
        return conversationMode
            ? base + " · 自动识别语言/面对面双向"
            : base + " · 自动识别源语言→" + target;
    }

    public boolean hasYoudaoCredentials() {
        return speechRouter.hasYoudaoCredentials();
    }

    public void translate(String text, Callback callback) {
        translate(text, "", callback);
    }

    /** Prefer ASR language when available; otherwise identify text locally. */
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
            lastDetectedLanguage = source;
            translatePair(cleaned, source, target, "", callback);
            return;
        }

        String fromAsr = normalizeTag(asrDetectedLanguage);
        if (isSupportedTranslationLanguage(fromAsr)) {
            routeDetected(cleaned, fromAsr, true, callback);
            return;
        }

        if (!conversationMode && shouldPreferConfiguredSourceForShortText(cleaned)) {
            lastDetectedLanguage = source;
            routeDetected(cleaned, source, false, callback);
            return;
        }

        languageIdentifier.identifyLanguage(cleaned)
            .addOnSuccessListener(language -> {
                if (closed) return;
                String detected = normalizeTag(language);
                if (!isUsefulDetectedLanguage(detected)) detected = scriptHint(cleaned);
                routeDetected(cleaned, detected, false, callback);
            })
            .addOnFailureListener(error -> {
                if (closed) return;
                routeDetected(cleaned, scriptHint(cleaned), false, callback);
            });
    }

    private void routeDetected(String text, String detected, boolean fromAsr, Callback callback) {
        if (conversationMode) routeConversation(text, detected, fromAsr, callback);
        else routeToFixedTarget(text, detected, fromAsr, callback);
    }

    private void routeToFixedTarget(String text, String detected, boolean fromAsr, Callback callback) {
        String lang = normalizeTag(detected);
        if (!isSupportedTranslationLanguage(lang)) lang = "";
        lastDetectedLanguage = lang;

        if (!lang.isEmpty() && sameLanguage(lang, target)) {
            callback.onSuccess(text, "已是目标语言 · 识别" + lang + (fromAsr ? "(ASR)" : ""));
            return;
        }

        if (!lang.isEmpty()) {
            partnerLanguage = lang;
            translatePair(text, lang, target,
                " · 自动识别 " + lang + "→" + target + (fromAsr ? " · ASR" : ""), callback);
            return;
        }

        lastDetectedLanguage = source;
        translatePair(text, source, target,
            " · 自动识别不确定，使用备用 " + source + "→" + target, callback);
    }

    private void routeConversation(String text, String detected, boolean fromAsr, Callback callback) {
        String lang = normalizeTag(detected);
        if (!isSupportedTranslationLanguage(lang)) lang = "";
        lastDetectedLanguage = lang;

        if (!lang.isEmpty() && sameLanguage(lang, target)) {
            String partner = partnerLanguage;
            if (!isSupportedTranslationLanguage(partner) || sameLanguage(partner, target)) partner = source;
            translatePair(text, target, partner,
                " · 面对面自动双向 " + target + "→" + partner + " · 识别" + lang
                    + (fromAsr ? "(ASR)" : ""), callback);
            return;
        }

        if (!lang.isEmpty()) {
            partnerLanguage = lang;
            translatePair(text, lang, target,
                " · 面对面自动识别 " + lang + "→" + target + (fromAsr ? " · ASR" : ""), callback);
            return;
        }

        lastDetectedLanguage = source;
        translatePair(text, source, target,
            " · 面对面识别不确定，使用备用 " + source + "→" + target, callback);
    }

    /** Continuous conversation uses explicit mode + independently checked language evidence. */
    public void translateConversation(String text, String asrLanguage, String mode, String fixedInput, Callback callback) {
        if (closed) return;
        String cleaned = AsrTranscriptGuard.clean(text);
        if (cleaned.isEmpty()) { callback.onError("没有可翻译文字"); return; }
        String asr = normalizeTag(asrLanguage);
        if (!isSupportedTranslationLanguage(asr)) asr = "";
        final String hint = asr;
        if (ConversationLanguagePolicy.FIXED.equals(mode) || languageIdentifier == null) {
            finishConversationRoute(cleaned, hint, "", 0f, mode, fixedInput, callback);
            return;
        }
        languageIdentifier.identifyPossibleLanguages(cleaned).addOnSuccessListener(candidates -> {
            if (closed) return;
            String detected = "";
            float best = 0f, runnerUp = 0f;
            for (com.google.mlkit.nl.languageid.IdentifiedLanguage candidate : candidates) {
                String language = normalizeTag(candidate.getLanguageTag());
                if (!isSupportedTranslationLanguage(language)) continue;
                float confidence = candidate.getConfidence();
                if (confidence > best) { runnerUp = best; best = confidence; detected = language; }
                else runnerUp = Math.max(runnerUp, confidence);
            }
            if (best - runnerUp < 0.20f) best = 0f;
            finishConversationRoute(cleaned, hint, detected, best, mode, fixedInput, callback);
        }).addOnFailureListener(e -> {
            if (!closed) finishConversationRoute(cleaned, hint, "", 0f, mode, fixedInput, callback);
        });
    }

    private void finishConversationRoute(String text, String asr, String detected, float confidence,
                                          String mode, String fixedInput, Callback callback) {
        ConversationLanguagePolicy.Route route = ConversationLanguagePolicy.decide(mode, target, source,
            partnerLanguage, fixedInput, text, asr, detected, confidence);
        if (!route.valid()) {
            lastDetectedLanguage = ""; lastTargetLanguage = "";
            callback.onError(route.error); return;
        }
        lastDetectedLanguage = route.source;
        lastTargetLanguage = route.target;
        partnerLanguage = route.partner;
        translatePair(text, route.source, route.target,
            " · " + route.source + "→" + route.target + " · " + route.reason, callback);
    }

    public String lastTargetLanguage() { return lastTargetLanguage; }

    public String partnerLanguage() { return partnerLanguage; }
    public String lastDetectedLanguage() { return lastDetectedLanguage; }

    public void translateTurn(String text, boolean partnerSpoke, String detectedLanguage, Callback callback) {
        if (closed) return;
        if (text == null || text.trim().isEmpty()) { callback.onError("没有可翻译文字"); return; }
        if (!partnerSpoke) {
            lastDetectedLanguage = target;
            String partner = partnerLanguage;
            if (!isSupportedTranslationLanguage(partner) || sameLanguage(partner, target)) partner = source;
            translatePair(text.trim(), target, partner, " · 我→对方", callback);
            return;
        }
        String hint = normalizeTag(detectedLanguage);
        if (isSupportedTranslationLanguage(hint) && !sameLanguage(hint, target)) {
            lastDetectedLanguage = hint;
            partnerLanguage = hint;
            translatePair(text.trim(), hint, target, " · 对方→我", callback);
        } else if (isSupportedTranslationLanguage(hint) && sameLanguage(hint, target)) {
            lastDetectedLanguage = hint;
            callback.onSuccess(text.trim(), "对方本句已是我的语言 · 无需翻译");
        } else if (languageIdentifier != null) {
            languageIdentifier.identifyLanguage(text.trim()).addOnSuccessListener(code -> {
                if (closed) return;
                String lang = normalizeTag(code);
                if (isSupportedTranslationLanguage(lang)) lastDetectedLanguage = lang;
                if (isSupportedTranslationLanguage(lang) && sameLanguage(lang, target)) {
                    callback.onSuccess(text.trim(), "对方本句已是我的语言 · 无需翻译");
                    return;
                }
                if (isSupportedTranslationLanguage(lang)) partnerLanguage = lang;
                String partner = isSupportedTranslationLanguage(partnerLanguage) ? partnerLanguage : source;
                translatePair(text.trim(), partner, target, " · 对方→我", callback);
            }).addOnFailureListener(e -> {
                if (!closed) {
                    lastDetectedLanguage = partnerLanguage;
                    String partner = isSupportedTranslationLanguage(partnerLanguage) ? partnerLanguage : source;
                    translatePair(text.trim(), partner, target, " · 备用语言", callback);
                }
            });
        } else {
            lastDetectedLanguage = partnerLanguage;
            String partner = isSupportedTranslationLanguage(partnerLanguage) ? partnerLanguage : source;
            translatePair(text.trim(), partner, target, " · 对方→我", callback);
        }
    }

    private void translatePair(String text, String from, String to, String suffix, Callback result) {
        if (closed) return;
        if (sameLanguage(from, to)) { result.onSuccess(text, "无需翻译" + suffix); return; }

        final Set<TranslationRouter> requestRouters = new HashSet<>();
        final boolean[] finished = {false};
        final Runnable[] timeout = {null};
        Callback callback = new Callback() {
            private boolean finish() {
                if (finished[0]) return false;
                finished[0] = true;
                main.removeCallbacks(timeout[0]);
                List<TranslationRouter> closing = new ArrayList<>(requestRouters);
                for (TranslationRouter router : closing) {
                    activeRouters.remove(router);
                    try { router.close(); } catch (Exception ignored) {}
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
            selected.translate(text, new TranslationRouter.Callback() {
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

    static boolean sameLanguage(String a, String b) {
        String x = normalizeTag(a);
        String y = normalizeTag(b);
        if (x.equals(y)) return true;
        if (("fil".equals(x) || "tl".equals(x)) && ("fil".equals(y) || "tl".equals(y))) return true;
        if (("he".equals(x) || "iw".equals(x)) && ("he".equals(y) || "iw".equals(y))) return true;
        if (("id".equals(x) || "in".equals(x)) && ("id".equals(y) || "in".equals(y))) return true;
        return false;
    }

    private static String normalizeTag(String value) { return AsrTranscriptGuard.normalizeTag(value); }

    static boolean shouldPreferConfiguredSourceForShortText(String text) {
        if (text == null) return true;
        String value = text.trim();
        if (value.isEmpty()) return true;
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= 2) return true;

        int han = 0, kana = 0, hangul = 0, latin = 0, letters = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u31f0' && c <= '\u31ff')) { kana++; letters++; }
            else if (c >= '\uac00' && c <= '\ud7af') { hangul++; letters++; }
            else if (c >= '\u3400' && c <= '\u9fff') { han++; letters++; }
            else if (Character.isLetter(c)) { latin++; letters++; }
        }
        return han > 0 && kana == 0 && hangul == 0 && latin == 0 && letters == han && codePoints <= 6;
    }

    static String scriptHint(String text) {
        int kana = 0;
        int hangul = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u31f0' && c <= '\u31ff')) kana++;
            else if (c >= '\uac00' && c <= '\ud7af') hangul++;
        }
        if (kana >= 1) return "ja";
        if (hangul >= 1) return "ko";
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
        List<TranslationRouter> closing;
        synchronized (activeRouters) { closing = new ArrayList<>(activeRouters); }
        for (TranslationRouter router : closing) {
            try { router.close(); } catch (Exception ignored) {}
        }
        activeRouters.clear();
        try { speechRouter.close(); } catch (Exception ignored) {}
        if (languageIdentifier != null) {
            try { languageIdentifier.close(); } catch (Exception ignored) {}
        }
    }
}
