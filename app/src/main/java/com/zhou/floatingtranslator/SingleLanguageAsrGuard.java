package com.zhou.floatingtranslator;

import java.util.Locale;

/**
 * Best-effort source-language gate for realtime single-language ASR.
 *
 * The Omnilingual CTC model cannot be conditioned on a language. This guard
 * rejects obvious wrong-script results and confidently identified other
 * languages; it does not pretend to distinguish all same-script languages.
 */
final class SingleLanguageAsrGuard {
    private SingleLanguageAsrGuard() {}

    static boolean accepts(String text, String sourceLanguage, String reportedLanguage) {
        String expected = AsrTranscriptGuard.normalizeTag(sourceLanguage);
        String reported = AsrTranscriptGuard.normalizeTag(reportedLanguage);
        if (expected.isEmpty()) return true;
        if (!reported.isEmpty() && isKnownLanguage(reported) && !reported.equals(expected)) {
            return false;
        }

        String value = AsrTranscriptGuard.clean(text);
        if (value.isEmpty()) return false;

        int matching = 0;
        int foreign = 0;
        boolean knownScript = true;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (isLatinLanguage(expected)) {
                if (script == Character.UnicodeScript.LATIN) matching++;
                else foreign++;
            } else {
                boolean matched;
                switch (expected) {
                    case "zh": case "yue":
                        matched = script == Character.UnicodeScript.HAN;
                        break;
                    case "ja":
                        matched = script == Character.UnicodeScript.HAN
                            || script == Character.UnicodeScript.HIRAGANA
                            || script == Character.UnicodeScript.KATAKANA;
                        break;
                    case "ko":
                        matched = script == Character.UnicodeScript.HANGUL
                            || script == Character.UnicodeScript.HAN;
                        break;
                    case "ar": case "fa": case "ur":
                        matched = script == Character.UnicodeScript.ARABIC;
                        break;
                    case "th":
                        matched = script == Character.UnicodeScript.THAI;
                        break;
                    case "ru": case "uk": case "be": case "bg": case "mk":
                        matched = script == Character.UnicodeScript.CYRILLIC;
                        break;
                    case "hi": case "mr":
                        matched = script == Character.UnicodeScript.DEVANAGARI;
                        break;
                    case "bn":
                        matched = script == Character.UnicodeScript.BENGALI;
                        break;
                    case "gu":
                        matched = script == Character.UnicodeScript.GUJARATI;
                        break;
                    case "ka":
                        matched = script == Character.UnicodeScript.GEORGIAN;
                        break;
                    case "kn":
                        matched = script == Character.UnicodeScript.KANNADA;
                        break;
                    case "ta":
                        matched = script == Character.UnicodeScript.TAMIL;
                        break;
                    case "te":
                        matched = script == Character.UnicodeScript.TELUGU;
                        break;
                    case "he":
                        matched = script == Character.UnicodeScript.HEBREW;
                        break;
                    case "el":
                        matched = script == Character.UnicodeScript.GREEK;
                        break;
                    default:
                        knownScript = false;
                        matched = true;
                }
                if (matched) matching++;
                else foreign++;
            }
        }
        // Reject wrong-script hallucinations such as Arabic "ال" for Malay.
        // Numbers, whitespace and model control tokens are not evidence of speech.
        if (knownScript) return matching > 0 && foreign == 0;
        return matching > 0;
    }

    /**
     * A multilingual model's reported language can be unreliable even when
     * its actual transcript is correct English (or another Latin-script
     * language). Never drop an entire Latin-script sentence on the model's
     * language tag alone. The caller then runs ML Kit's conservative text
     * language identification on longer phrases.
     */
    static boolean acceptsLive(String text, String sourceLanguage, String reportedLanguage) {
        if (!accepts(text, sourceLanguage, "")) return false;
        String source = AsrTranscriptGuard.normalizeTag(sourceLanguage);
        if (isLatinLanguage(source)) return true;
        return accepts(text, sourceLanguage, reportedLanguage);
    }

    static boolean needsLanguageId(String text, String sourceLanguage) {
        if (!isLatinLanguage(AsrTranscriptGuard.normalizeTag(sourceLanguage))) return false;
        int latin = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)
                && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) latin++;
        }
        return latin >= 16;
    }

    static boolean rejectConfidentOther(String sourceLanguage, String detectedLanguage,
                                         float bestConfidence, float sourceConfidence) {
        String source = AsrTranscriptGuard.normalizeTag(sourceLanguage);
        String detected = AsrTranscriptGuard.normalizeTag(detectedLanguage);
        if (source.isEmpty() || detected.isEmpty() || source.equals(detected)) return false;
        // Malay/Indonesian are often indistinguishable in short live subtitles.
        // Do not drop likely Malay just because ML Kit labels it Indonesian.
        if (("ms".equals(source) && "id".equals(detected))
            || ("id".equals(source) && "ms".equals(detected))) return false;
        return bestConfidence >= 0.92f && sourceConfidence < 0.15f;
    }

    private static boolean isKnownLanguage(String code) {
        for (LanguageOption option : LanguageOption.ALL) {
            if (AsrTranscriptGuard.normalizeTag(option.mlKitTag).equals(code)) return true;
        }
        return "yue".equals(code);
    }

    private static boolean isLatinLanguage(String language) {
        switch (language.toLowerCase(Locale.ROOT)) {
            case "ms": case "id": case "en": case "vi": case "tl":
            case "fr": case "de": case "es": case "pt": case "it": case "nl":
            case "tr": case "pl": case "cs": case "ro": case "sk": case "sl":
            case "hr": case "sq": case "hu": case "sw": case "no": case "sv":
            case "da": case "fi": case "is": case "et": case "lv": case "lt": case "mt":
            case "ca": case "cy": case "ga": case "gl": case "af": case "ht":
            case "eo":
                return true;
            default:
                return false;
        }
    }
}
