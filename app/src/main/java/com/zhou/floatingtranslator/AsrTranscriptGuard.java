package com.zhou.floatingtranslator;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small, dependency-free cleanup layer between ASR output and translation routing. */
final class AsrTranscriptGuard {
    private static final Pattern CONTROL_TOKEN = Pattern.compile("(?i)<\\|[^\\r\\n|>]{1,96}\\|>");
    private static final Pattern SIMPLE_CONTROL = Pattern.compile(
        "(?i)</?(?:s|pad|unk|blank|eos|bos|eot|eof|no[_ -]?speech|endoftext)>"
    );
    private static final Pattern BRACKET_CONTROL = Pattern.compile(
        "(?i)\\[(?:blank_audio|silence|noise|music|no_speech|endoftext)\\]"
    );

    private AsrTranscriptGuard() {}

    /** Remove model control tokens such as <|endoftext|> before they reach UI/history/translation. */
    static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String value = CONTROL_TOKEN.matcher(raw).replaceAll(" ");
        value = SIMPLE_CONTROL.matcher(value).replaceAll(" ");
        value = BRACKET_CONTROL.matcher(value).replaceAll(" ");
        value = value.replace('\u0000', ' ')
            .replaceAll("[\\t ]+", " ")
            .replaceAll("\\s+([,，。.!！？?])", "$1")
            .trim();
        if (value.isEmpty()) return "";

        boolean meaningful = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || isCjk(c)) {
                meaningful = true;
                break;
            }
        }
        return meaningful ? value : "";
    }

    /**
     * Detect short ASR hallucinations before the transcript is shown or translated.
     * In particular, Omnilingual CTC can output stray Arabic/Latin glyphs or
     * punctuation when listening to silence, music, or an incomplete chunk.
     * A full phrase is not judged by language here; single-language checks
     * remain the separate responsibility of SingleLanguageAsrGuard.
     */
    static boolean isLikelyNoise(String raw) {
        String text = clean(raw);
        if (text.isEmpty()) return true;

        int letters = 0;
        int digits = 0;
        int punctuation = 0;
        int firstLetter = -1;
        boolean sameLetter = true;
        Character.UnicodeScript firstScript = null;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (firstLetter < 0) {
                    firstLetter = Character.toLowerCase(cp);
                    firstScript = Character.UnicodeScript.of(cp);
                } else if (Character.toLowerCase(cp) != firstLetter) {
                    sameLetter = false;
                }
            } else if (Character.isDigit(cp)) digits++;
            else if (!Character.isWhitespace(cp)) punctuation++;
        }

        if (letters == 0 && digits == 0) return true;
        if (letters == 1 && digits == 0) {
            // English "I" / "a" are valid words, unlike the spurious
            // Arabic "ا" or a lone lowercase "l" emitted by noisy ASR.
            if (text.equalsIgnoreCase("I") || text.equalsIgnoreCase("a")) return false;
            // Keep legitimate single-character CJK words.
            return firstScript != Character.UnicodeScript.HAN
                && firstScript != Character.UnicodeScript.HIRAGANA
                && firstScript != Character.UnicodeScript.KATAKANA
                && firstScript != Character.UnicodeScript.HANGUL;
        }
        if (letters >= 5 && sameLetter && digits == 0) return true;
        return letters <= 2 && digits == 0 && punctuation >= 3;
    }

    /** Preserve provider evidence; do not invent English from Latin words or force the user's language. */
    static String stabilizeLanguage(String text, String detected, String myLanguage) {
        String lang = normalizeTag(detected);
        return ConversationLanguagePolicy.scriptCompatible(clean(text), lang) ? lang : "";
    }

    static String normalizeTag(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        value = value.replace("<|", "").replace("|>", "");
        switch (value) {
            case "english": return "en";
            case "chinese": case "mandarin": return "zh";
            case "japanese": return "ja";
            case "korean": return "ko";
            case "vietnamese": return "vi";
            case "malay": return "ms";
            case "tagalog": case "filipino": return "tl";
            case "indonesian": return "id";
            case "thai": return "th";
            case "cantonese": return "yue";
            case "auto": case "und": case "unknown": return "";
            default: break;
        }
        int dash = value.indexOf('-');
        if (dash > 0) value = value.substring(0, dash);
        if ("fil".equals(value)) return "tl";
        if ("iw".equals(value)) return "he";
        if ("in".equals(value)) return "id";
        if (value.startsWith("zh")) return "zh";
        return value;
    }

    private static boolean sameLanguage(String a, String b) {
        return normalizeTag(a).equals(normalizeTag(b));
    }

    private static boolean isCjk(char c) {
        return (c >= '\u3040' && c <= '\u30ff')
            || (c >= '\u3400' && c <= '\u9fff')
            || (c >= '\uac00' && c <= '\ud7af')
            || (c >= '\uf900' && c <= '\ufaff');
    }
}
