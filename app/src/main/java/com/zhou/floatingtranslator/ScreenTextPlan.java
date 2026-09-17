package com.zhou.floatingtranslator;

import java.util.ArrayList;
import java.util.List;

/** Lossless request partitioning. Limits bound requests, never discard screen text. */
final class ScreenTextPlan {
    static List<String> split(String text, int limit) {
        if (limit < 2) throw new IllegalArgumentException("limit");
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) { out.add(""); return out; }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                int boundary = end;
                for (int i = end - 1; i > start + limit / 2; i--) {
                    char c = text.charAt(i);
                    if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == ' ') {
                        boundary = i + 1; break;
                    }
                }
                end = boundary;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }
    static int batchEnd(List<String> texts, int start, int items, int chars) {
        int end = start, total = 0;
        while (end < texts.size() && end - start < items) {
            int next = texts.get(end).length();
            if (end > start && total + next > chars) break;
            total += next; end++;
        }
        return end;
    }
}
