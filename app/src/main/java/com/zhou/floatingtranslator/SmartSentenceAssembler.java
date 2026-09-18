package com.zhou.floatingtranslator;

import android.os.SystemClock;

import java.util.Locale;

/**
 * Lightweight, non-AI sentence continuity helper.
 *
 * It never delays the first visible translation. If an ASR final looks unfinished, that fragment
 * is emitted immediately as provisional text and remembered. The next final fragment can then be
 * joined and retranslated as one complete sentence, replacing the provisional subtitle.
 */
public final class SmartSentenceAssembler {
    public interface Output {
        void emit(String text, boolean complete, String language);
    }

    private static final long CONTEXT_WINDOW_MS = 8000L;
    private static final int MAX_CONTEXT_CHARS = 420;

    private String pending = "";
    private String pendingLanguage = "";
    private long pendingAt;

    public synchronized void offer(String text, boolean finalResult, String language, Output output) {
        if (output == null || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        String lang = language == null ? "" : language.trim();

        // Streaming partials remain fast and never alter the sentence context.
        if (!finalResult) {
            output.emit(cleaned, false, lang);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!pending.isEmpty() && now - pendingAt > CONTEXT_WINDOW_MS) clearPending();

        if (!pending.isEmpty() && languageCompatible(pendingLanguage, lang)) {
            if (sameOrCumulative(pending, cleaned)) {
                pending = longer(pending, cleaned);
                pendingLanguage = chooseLanguage(pendingLanguage, lang);
                pendingAt = now;
                boolean incomplete = looksIncomplete(pending);
                output.emit(pending, !incomplete, pendingLanguage);
                if (!incomplete) clearPending();
                return;
            }

            String combined = join(pending, cleaned);
            String combinedLanguage = chooseLanguage(pendingLanguage, lang);
            if (combined.length() <= MAX_CONTEXT_CHARS) {
                boolean incomplete = looksIncomplete(combined);
                output.emit(combined, !incomplete, combinedLanguage);
                if (incomplete) {
                    pending = combined;
                    pendingLanguage = combinedLanguage;
                    pendingAt = now;
                } else {
                    clearPending();
                }
                return;
            }
            clearPending();
        } else if (!pending.isEmpty()) {
            clearPending();
        }

        boolean incomplete = looksIncomplete(cleaned);
        output.emit(cleaned, !incomplete, lang);
        if (incomplete) {
            pending = cleaned;
            pendingLanguage = lang;
            pendingAt = now;
        }
    }

    public synchronized void reset() {
        clearPending();
    }

    private void clearPending() {
        pending = "";
        pendingLanguage = "";
        pendingAt = 0L;
    }

    static boolean looksIncomplete(String text) {
        if (text == null) return false;
        String value = text.trim();
        if (value.isEmpty()) return false;

        String stripped = stripClosingQuotes(value);
        if (stripped.isEmpty()) return false;
        char last = stripped.charAt(stripped.length() - 1);

        // Strong continuation punctuation.
        if (last == ',' || last == '，' || last == ':' || last == '：'
            || last == ';' || last == '；' || last == '、'
            || last == '-' || last == '—') return true;

        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(and|but|or|so|because|if|when|while|although|though|since|unless|until|that|to|of|for|with|as)\\s*[.!?。！？]?$")) {
            return true;
        }

        // A short trailing conjunction clause with a weak verb is often a chunk boundary:
        // "... and I choose." + "rich every ... time."
        if (lower.matches(".*[,;:]\\s*(and|but|or|so)\\s+(?:\\S+\\s+){0,4}(choose|want|need|think|know|mean|say|tell|ask|try|decide|go|get|have|do|make|take|see)[.!?]?$")) {
            return true;
        }

        boolean terminal = last == '.' || last == '!' || last == '?' || last == '。'
            || last == '！' || last == '？';
        if (terminal) return false;

        // ASR chunks without sentence punctuation are treated as provisional; the first translation
        // is still shown immediately, so this does not add visible latency.
        int words = wordCount(stripped);
        return words == 0 ? stripped.length() <= 80 : words <= 36;
    }

    private static int wordCount(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return 0;
        if (!trimmed.matches(".*[A-Za-zÀ-ɏ].*")) return 0;
        return trimmed.split("\\s+").length;
    }

    private static String stripClosingQuotes(String value) {
        String out = value;
        while (!out.isEmpty()) {
            char c = out.charAt(out.length() - 1);
            if (c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == '）'
                || c == ']' || c == '】') out = out.substring(0, out.length() - 1).trim();
            else break;
        }
        return out;
    }

    private static String join(String a, String b) {
        String left = a.trim();
        String right = b.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        char end = left.charAt(left.length() - 1);
        char start = right.charAt(0);
        if (end == '-' || end == '—') return left + right;
        if (start == ',' || start == '.' || start == '!' || start == '?' || start == '，'
            || start == '。' || start == '！' || start == '？' || start == ';' || start == '；') {
            return left + right;
        }
        return left + " " + right;
    }

    private static boolean sameOrCumulative(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        return x.equals(y) || x.startsWith(y) || y.startsWith(x);
    }

    private static String longer(String a, String b) {
        return a.length() >= b.length() ? a : b;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean languageCompatible(String a, String b) {
        String x = AsrTranscriptGuard.normalizeTag(a);
        String y = AsrTranscriptGuard.normalizeTag(b);
        return x.isEmpty() || y.isEmpty() || OfflineFirstTranslationRouter.sameLanguage(x, y);
    }

    private static String chooseLanguage(String a, String b) {
        String x = AsrTranscriptGuard.normalizeTag(a);
        String y = AsrTranscriptGuard.normalizeTag(b);
        return !x.isEmpty() ? x : y;
    }
}
