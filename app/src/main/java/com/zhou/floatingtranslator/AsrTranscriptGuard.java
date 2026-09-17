package com.zhou.floatingtranslator;

import java.util.Locale;
import java.util.regex.Matcher;
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
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");

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
     * ASR language IDs on very short code-switch utterances can flip to the user's language.
     * Do not let a short, clearly Latin-heavy mixed sentence be treated as the local speaker only
     * because a few Han characters were hallucinated phonetically (for example an English phrase
     * decoded partly as Chinese characters). The transcript itself is left untouched; this only
     * protects speaker/direction routing. Qwen3 is preferred in Auto mode to reduce the bad decode
     * at the source.
     */
    static String stabilizeLanguage(String text, String detected, String myLanguage) {
        String value = clean(text);
        String lang = normalizeTag(detected);
        String mine = normalizeTag(myLanguage);
        if (value.isEmpty()) return lang;

        int kana = 0;
        int hangul = 0;
        int han = 0;
        int latinLetters = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u3040' && c <= '\u30ff') kana++;
            else if (c >= '\uac00' && c <= '\ud7af') hangul++;
            else if ((c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff')) han++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latinLetters++;
        }

        // Distinctive scripts are stronger evidence than a noisy model language tag.
        if (kana > 0) return "ja";
        if (hangul > 0) return "ko";

        int latinWords = 0;
        Matcher matcher = LATIN_WORD.matcher(value);
        while (matcher.find()) latinWords++;

        boolean detectedAsMe = !mine.isEmpty() && sameLanguage(lang, mine);
        boolean cjkMyLanguage = "zh".equals(mine) || "ja".equals(mine) || "ko".equals(mine);
        boolean shortMixed = latinWords >= 2 && latinLetters >= 4 && han <= 5;
        if (detectedAsMe && cjkMyLanguage && shortMixed) return "en";

        return lang;
    }

    static String normalizeTag(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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
