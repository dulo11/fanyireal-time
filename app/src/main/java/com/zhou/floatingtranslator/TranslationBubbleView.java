package com.zhou.floatingtranslator;

import android.content.Context;
import android.widget.TextView;

/** The touch gesture and accessibility click both dispatch the registered click action. */
public final class TranslationBubbleView extends TextView {
    public TranslationBubbleView(Context context) { super(context); }
    @Override public boolean performClick() { return super.performClick(); }
}
