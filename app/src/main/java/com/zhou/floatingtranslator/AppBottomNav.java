package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Global fixed bottom navigation shown on every in-app Activity. */
public final class AppBottomNav {
    private static final String TAG = "floating_translator_global_bottom_nav";

    private AppBottomNav() {}

    public static void attach(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) return;
        FrameLayout content = (FrameLayout) root;

        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (TAG.equals(child.getTag())) content.removeViewAt(i);
        }

        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.rgb(24, 19, 38));
        bar.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, 5), dp(activity, 5));
        bar.setElevation(dp(activity, 18));

        addTab(activity, bar, "⌂\n" + activity.getString(R.string.nav_home), HomeActivity.class,
            activity instanceof HomeActivity);
        addTab(activity, bar, "▶\n" + activity.getString(R.string.nav_realtime), MainActivity.class,
            activity instanceof MainActivity);
        addTab(activity, bar, "译\n" + activity.getString(R.string.nav_screen), ScreenTranslationActivity.class,
            activity instanceof ScreenTranslationActivity);
        addTab(activity, bar, "▣\n" + activity.getString(R.string.nav_models), ModelManagerActivity.class,
            activity instanceof ModelManagerActivity);
        addTab(activity, bar, "⚙\n" + activity.getString(R.string.nav_settings), SettingsHubActivity.class,
            activity instanceof SettingsHubActivity || activity instanceof AppLanguageActivity
                || activity instanceof ApiSettingsActivity || activity instanceof AsrPrecisionActivity
                || activity instanceof RootCallActivity || activity instanceof HistoryActivity);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        content.addView(bar, lp);

        bar.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
            v.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, 5), dp(activity, 5) + nav.bottom);
            return insets;
        });
        bar.requestApplyInsets();
    }

    private static void addTab(Activity activity, LinearLayout bar, String label,
                               Class<? extends Activity> destination, boolean active) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(activity, 52));
        button.setMinimumHeight(dp(activity, 52));
        button.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        button.setBackgroundResource(active ? R.drawable.button_primary : R.drawable.button_secondary);
        button.setEnabled(!active);
        button.setAlpha(active ? 1f : 0.94f);
        button.setOnClickListener(v -> open(activity, destination));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(activity, 56), 1f);
        p.setMargins(dp(activity, 2), 0, dp(activity, 2), 0);
        bar.addView(button, p);
    }

    private static void open(Activity activity, Class<? extends Activity> destination) {
        if (destination.isInstance(activity)) return;
        Intent intent = new Intent(activity, destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
