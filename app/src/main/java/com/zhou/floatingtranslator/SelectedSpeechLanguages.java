package com.zhou.floatingtranslator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit language whitelist for custom mixed-language subtitles.
 *
 * An ASR engine still has to recognize the speech; this class limits what is
 * accepted AFTER ASR, independently of Azure/ML Kit translation settings.
 * It never claims a multilingual CTC model can be forced to specific languages.
 */
final class SelectedSpeechLanguages {
    static final String MODE_CUSTOM = "custom";
    static final String PREF_CUSTOM_CODES = "custom_speech_languages";
    static final String EXTRA_CUSTOM_CODES = "custom_speech_languages";
    static final String DEFAULT_CODES = "en,ja";

    private SelectedSpeechLanguages() {}

    static List<String> parse(String stored) {
        List<String> codes = new ArrayList<>();
        if (stored == null) return codes;
        Set<String> seen = new HashSet<>();
        for (String raw : stored.split(",")) {
            String code = AsrTranscriptGuard.normalizeTag(raw);
            if (isSupported(code) && seen.add(code)) codes.add(code);
        }
        return codes;
    }

    static String serialize(List<String> codes) {
        return String.join(",", parse(codes == null ? "" : String.join(",", codes)));
    }

    static List<String> forLegacy(String mode, String saved) {
        if (saved != null && !saved.trim().isEmpty()) return parse(saved);
        if (SherpaSpeechEngine.LANG_JA_EN.equals(mode)) return parse("ja,en");
        if (SherpaSpeechEngine.LANG_ZH_EN.equals(mode)) return parse("zh,en");
        if (SherpaSpeechEngine.LANG_KO_EN.equals(mode)) return parse("ko,en");
        return parse(DEFAULT_CODES);
    }

    static boolean isSupported(String code) {
        if (code == null || code.isEmpty()) return false;
        for (LanguageOption option : LanguageOption.ALL) {
            if (code.equals(AsrTranscriptGuard.normalizeTag(option.mlKitTag))) return true;
        }
        return false;
    }

    /** Locale tags used by optional Android 14+ language detection hints. */
    static ArrayList<String> speechLocales(List<String> selected) {
        ArrayList<String> locales = new ArrayList<>();
        if (selected == null) return locales;
        for (String code : selected) {
            for (LanguageOption option : LanguageOption.ALL) {
                if (code.equals(option.mlKitTag)) {
                    locales.add(option.speechTag);
                    break;
                }
            }
        }
        return locales;
    }

    static String display(List<String> languages) {
        if (languages == null || languages.isEmpty()) return "尚未选择";
        List<String> names = new ArrayList<>();
        for (String code : languages) {
            String label = code;
            for (LanguageOption option : LanguageOption.ALL) {
                if (code.equals(option.mlKitTag)) { label = option.label; break; }
            }
            names.add(label);
        }
        return String.join(" + ", names);
    }

    static final class Evidence {
        final String language;
        final float confidence;
        Evidence(String language, float confidence) {
            this.language = AsrTranscriptGuard.normalizeTag(language);
            this.confidence = confidence;
        }
    }

    /**
     * Return only a chosen language in the user's whitelist; empty means
     * uncertain / unselected and must not enter the translation queue.
     */
    static String choose(String text, String reported, List<String> allowed,
                         List<Evidence> evidence) {
        if (text == null || allowed == null || allowed.size() < 2) return "";
        List<String> matching = new ArrayList<>();
        for (String language : allowed) {
            if (SingleLanguageAsrGuard.acceptsLive(text, language, "")) matching.add(language);
        }
        if (matching.isEmpty()) return "";

        String reportedTag = AsrTranscriptGuard.normalizeTag(reported);
        Evidence best = null;
        Evidence bestMatching = null;
        if (evidence != null) {
            for (Evidence candidate : evidence) {
                if (candidate.language.isEmpty() || "und".equals(candidate.language)) continue;
                if (best == null || candidate.confidence > best.confidence) best = candidate;
                if (matching.contains(candidate.language)
                    && (bestMatching == null || candidate.confidence > bestMatching.confidence)) {
                    bestMatching = candidate;
                }
            }
        }

        // A high-confidence detection of an unselected language is not
        // silently interpreted as whichever allowed language looks similar.
        // If the transcript has only one compatible script, an erroneous
        // language tag is still weaker evidence than its actual characters.
        if (best != null && !allowed.contains(best.language) && best.confidence >= 0.80f) {
            if (matching.size() != 1) return "";
            if (sameScriptFamily(best.language, matching.get(0))) return "";
        }
        // If the model explicitly labels a same-script language that the
        // user did NOT select (e.g. Spanish when only English+Japanese are
        // allowed), demand strong independent textual evidence before
        // accepting it as English. Wrong-script model tags are less credible.
        if (isSupported(reportedTag) && !allowed.contains(reportedTag)
            && matching.size() == 1
            && sameScriptFamily(reportedTag, matching.get(0))
            && (bestMatching == null || bestMatching.confidence < 0.80f)) return "";

        if (bestMatching != null && bestMatching.confidence >= 0.55f) return bestMatching.language;

        // Short one-script utterances can still be translated immediately.
        if (matching.size() == 1) return matching.get(0);

        // For en + fr / other shared-script pairs, only ASR language evidence
        // from an explicitly allowed language may break an uncertain tie.
        if (matching.contains(reportedTag)
            && (best == null || best.confidence < 0.80f || allowed.contains(best.language))) {
            return reportedTag;
        }
        return "";
    }

    private static boolean sameScriptFamily(String a, String b) {
        return (SingleLanguageAsrGuard.acceptsLive("Hello", a, "")
            && SingleLanguageAsrGuard.acceptsLive("Hello", b, ""))
            || (SingleLanguageAsrGuard.acceptsLive("你好", a, "")
                && SingleLanguageAsrGuard.acceptsLive("你好", b, ""));
    }
}
