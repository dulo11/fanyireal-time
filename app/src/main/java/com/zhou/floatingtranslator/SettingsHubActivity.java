package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One-hop settings hub so common pages no longer require nested navigation. */
public final class SettingsHubActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(getString(R.string.settings_title));
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(110));
        scroll.addView(root);

        TextView title = text(getString(R.string.settings_title), 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView sub = text(getString(R.string.settings_subtitle), 14, Color.rgb(201, 190, 221));
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        addEntry(root, R.string.settings_app_language, R.string.settings_app_language_desc, AppLanguageActivity.class);
        addEntry(root, R.string.settings_translation_engine, R.string.settings_translation_engine_desc, ApiSettingsActivity.class);
        addEntry(root, R.string.settings_screen, R.string.settings_screen_desc, ScreenTranslationActivity.class);
        addEntry(root, R.string.settings_models, R.string.settings_models_desc, ModelManagerActivity.class);
        addEntry(root, R.string.settings_asr, R.string.settings_asr_desc, AsrPrecisionActivity.class);
        addEntry(root, R.string.settings_root, R.string.settings_root_desc, RootCallActivity.class);
        addEntry(root, R.string.settings_history, R.string.settings_history_desc, HistoryActivity.class);
        return scroll;
    }

    private void addEntry(LinearLayout root, int titleId, int descId, Class<? extends Activity> target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.panel);
        TextView title = text(getString(titleId), 18, Color.WHITE);
        title.setTypeface(null, 1);
        card.addView(title);
        TextView desc = text(getString(descId), 13, Color.rgb(190, 180, 212));
        desc.setPadding(0, dp(4), 0, dp(8));
        card.addView(desc);
        Button open = new Button(this);
        open.setText(getString(R.string.settings_open));
        open.setTextColor(Color.WHITE);
        open.setAllCaps(false);
        open.setBackgroundResource(R.drawable.button_secondary);
        open.setOnClickListener(v -> startActivity(new Intent(this, target)));
        card.addView(open, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        root.addView(card, p);
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
