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
import java.util.List;

/** Non-touchable accessibility overlay that paints translated text over the original bounds. */
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
        background.setColor(Color.argb(226, 20, 16, 31));
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(false);
    }

    public void setEntries(List<Entry> value) {
        entries = value == null ? Collections.emptyList() : new ArrayList<>(value);
        setVisibility(entries.isEmpty() ? INVISIBLE : VISIBLE);
        invalidate();
    }

    public void clear() {
        setEntries(Collections.emptyList());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.max(3, Math.round(4f * density));
        float radius = 6f * density;

        for (Entry entry : entries) {
            Rect b = entry.bounds;
            if (b.width() < 8 || b.height() < 8 || entry.translated.trim().isEmpty()) continue;

            float textSize = Math.max(11f * density,
                Math.min(18f * density, b.height() * 0.48f));
            textPaint.setTextSize(textSize);

            int width = Math.max(Math.round(48f * density), b.width() - pad * 2);
            StaticLayout layout = StaticLayout.Builder
                .obtain(entry.translated, 0, entry.translated.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setMaxLines(5)
                .build();

            int boxHeight = Math.max(b.height(), layout.getHeight() + pad * 2);
            int left = Math.max(0, b.left);
            int top = Math.max(0, b.top);
            int right = Math.min(getWidth(), Math.max(left + pad * 2 + 1, b.right));
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
