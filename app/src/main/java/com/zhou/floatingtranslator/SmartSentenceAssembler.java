package com.zhou.floatingtranslator;

/**
 * Preserves recognition event boundaries. Text equality is not an utterance identity:
 * a speaker may intentionally repeat a word, phrase, or complete sentence.
 * Only ASR interim events are provisional; punctuation never downgrades a final event.
 */
public final class SmartSentenceAssembler {
    public interface Output {
        void emit(String text, boolean complete, String language);
    }

    public synchronized void offer(String text, boolean finalResult, String language, Output output) {
        if (output == null || text == null || text.trim().isEmpty()) return;
        output.emit(text.trim(), finalResult, language == null ? "" : language.trim());
    }

    public synchronized void reset() { /* No cross-utterance text state. */ }
}
