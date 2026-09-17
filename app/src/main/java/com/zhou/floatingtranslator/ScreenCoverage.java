package com.zhou.floatingtranslator;

import android.graphics.Rect;

final class ScreenCoverage {
    static boolean covers(String existing, Rect box, String candidate, Rect area) {
        String a = compact(existing), b = compact(candidate);
        if (b.isEmpty() || !a.contains(b)) return false;
        Rect overlap = new Rect();
        if (!overlap.setIntersect(box, area)) return false;
        return (long) overlap.width() * overlap.height() >= (long) area.width() * area.height() * 0.85;
    }
    private static String compact(String text) { return text == null ? "" : text.replaceAll("\\s+", ""); }
}
