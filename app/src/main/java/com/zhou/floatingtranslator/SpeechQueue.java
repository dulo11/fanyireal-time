package com.zhou.floatingtranslator;

import java.util.ArrayDeque;

/** Final utterances are FIFO; only interim hypotheses may be replaced. */
final class SpeechQueue {
    static final class Item {
        final String text, language;
        final boolean complete;
        Item(String text, boolean complete, String language) {
            this.text = text; this.complete = complete;
            this.language = language == null ? "" : language;
        }
    }
    private final ArrayDeque<Item> finals = new ArrayDeque<>();
    private Item partial;
    synchronized void offer(String text, boolean complete, String language) {
        Item item = new Item(text, complete, language);
        if (complete) { partial = null; finals.addLast(item); }
        else partial = item;
    }
    synchronized Item poll() { return finals.isEmpty() ? takePartial() : finals.removeFirst(); }
    private Item takePartial() { Item result = partial; partial = null; return result; }
    synchronized void clear() { finals.clear(); partial = null; }
    synchronized int size() { return finals.size() + (partial == null ? 0 : 1); }
}
