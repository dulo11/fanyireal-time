package com.zhou.floatingtranslator;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

/** One accessible click target; the label is decorative, not a second focus target. */
public final class TranslationBubbleView extends FrameLayout {
    private final TextView label;
    public TranslationBubbleView(Context context) {
        super(context);setFocusable(true);setClickable(true);
        label=new TextView(context);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
    }
    public void setText(CharSequence value){label.setText(value);}
    public void setTextColor(int color){label.setTextColor(color);}
    public void setTextSize(float size){label.setTextSize(size);}
    public void setGravity(int gravity){label.setGravity(gravity);}
    @Override public boolean performClick(){return super.performClick();}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.Button.class.getName());
    }
}
