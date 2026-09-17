package com.zhou.floatingtranslator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Non-touchable accessibility overlay that paints translated text near the original bounds. */
public final class ScreenTranslationOverlayView extends View {
    public static final class Entry {
        public final Rect bounds;
        public final String original;
        public final String translated;

        public Entry(Rect bounds, String original, String translated) {
            this.bounds = new Rect(bounds);
            this.original = original == null ? "" : original;
            this.translated = translated == null ? "" : translated;
        }
    }

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private List<Entry> entries = Collections.emptyList();

    public ScreenTranslationOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        background.setColor(Color.argb(182, 20, 16, 31));
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(false);
    }

    public void setEntries(List<Entry> value) {
        entries = normalizeEntries(value);
        setVisibility(entries.isEmpty() ? INVISIBLE : VISIBLE);
        invalidate();
    }

    public void clear() {
        setEntries(Collections.emptyList());
    }

    /**
     * Accessibility trees often expose the same text both on a large container node and on its
     * children. Drawing both creates giant overlapping rectangles and makes translations look
     * incomplete. Remove only matching text, never discard unique parent paragraph content.
     */
    private List<Entry> normalizeEntries(List<Entry> value) {
        if (value == null || value.isEmpty()) return Collections.emptyList();
        ArrayList<Entry> candidates = new ArrayList<>();
        for (Entry entry : value) {
            if (entry == null || entry.bounds == null || entry.translated.trim().isEmpty()) continue;
            if (entry.bounds.width() < 8 || entry.bounds.height() < 8) continue;
            candidates.add(entry);
        }
        candidates.sort(Comparator.comparingLong(e -> area(e.bounds)));

        ArrayList<Entry> kept = new ArrayList<>();
        for (Entry candidate : candidates) {
            boolean skip = false;

            long candidateArea = area(candidate.bounds);
            for (Entry small : kept) {
                Rect intersection = new Rect();
                if (!intersection.setIntersect(candidate.bounds, small.bounds)) continue;
                long overlap = area(intersection);
                long smallArea = Math.max(1L, area(small.bounds));
                long minArea = Math.max(1L, Math.min(candidateArea, smallArea));

                boolean sameText = normalized(candidate.original).equals(normalized(small.original))
                    || normalized(candidate.translated).equals(normalized(small.translated));
                if (sameText && overlap >= minArea * 0.60f) {
                    skip = true;
                    break;
                }

            }
            if (!skip) kept.add(candidate);
        }

        kept.sort((a, b) -> {
            int top = Integer.compare(a.bounds.top, b.bounds.top);
            return top != 0 ? top : Integer.compare(a.bounds.left, b.bounds.left);
        });
        return kept;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static long area(Rect rect) {
        return Math.max(0, rect.width()) * (long) Math.max(0, rect.height());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.max(3, Math.round(4f * density));
        float radius = 5f * density;
        int minWidth = Math.round(112f * density);
        int maxGrowWidth = Math.round(300f * density);
        int longBlockHeight = Math.round(110f * density);

        for (Entry entry : entries) {
            Rect b = entry.bounds;
            if (b.width() < 8 || b.height() < 8 || entry.translated.trim().isEmpty()) continue;

            float textSize = Math.max(10f * density,
                Math.min(16f * density, b.height() * 0.34f));
            textPaint.setTextSize(textSize);

            int left = Math.max(0, b.left);
            int available = Math.max(1, getWidth() - left - pad);
            int wanted = Math.max(b.width(), minWidth);
            boolean longBlock = b.height() >= longBlockHeight
                || entry.original.length() >= 90 || entry.translated.length() >= 90;
            if (longBlock || entry.translated.length() > Math.max(8, entry.original.length())) {
                wanted = Math.max(wanted, Math.min(maxGrowWidth, available));
            }
            int outerWidth = Math.min(available, wanted);
            int textWidth = Math.max(1, outerWidth - pad * 2);

            int linesFromSourceHeight = Math.max(4,
                Math.round(b.height() / Math.max(textSize * 1.12f, 1f)));
            // Inline preview is intentionally compact; the service exposes all text in its scrollable panel.
            int maxLines = 4;
            StaticLayout.Builder builder = StaticLayout.Builder
                .obtain(entry.translated, 0, entry.translated.length(), textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(maxLines);
            builder.setEllipsize(TextUtils.TruncateAt.END);
            StaticLayout layout = builder.build();

            int boxHeight = layout.getHeight() + pad * 2;
            int top = Math.max(0, b.top + (longBlock ? 0 : Math.max(0, (b.height() - boxHeight) / 2)));
            int right = Math.min(getWidth(), left + outerWidth);
            int bottom = Math.min(getHeight(), top + boxHeight);
            if (right <= left || bottom <= top) continue;

            RectF box = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(box, radius, radius, background);

            int save = canvas.save();
            canvas.clipRect(box);
            canvas.translate(left + pad, top + pad);
            layout.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
