package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.LocaleList;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Android 13+ per-app language selector. */
public final class AppLanguageActivity extends Activity {
    private static final String[] TAGS = {
        "", "zh-CN", "zh-TW", "zh-HK", "en", "ja", "ko", "vi", "th", "fil", "ms", "id",
        "fr", "de", "es", "pt", "ru", "ar"
    };
    private static final String[] LABELS = {
        "🌐 System / 跟随系统", "简体中文", "繁體中文（台灣）", "繁體中文（香港）", "English",
        "日本語", "한국어", "Tiếng Việt", "ไทย", "Filipino", "Bahasa Melayu", "Bahasa Indonesia",
        "Français", "Deutsch", "Español", "Português", "Русский", "العربية"
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(getString(R.string.language_title));
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(110));
        scroll.addView(root);

        TextView title = text(getString(R.string.language_title), 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView sub = text(getString(R.string.language_subtitle), 14, Color.rgb(201, 190, 221));
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        LocaleManager manager = getSystemService(LocaleManager.class);
        String current = manager.getApplicationLocales().isEmpty()
            ? "" : manager.getApplicationLocales().get(0).toLanguageTag();

        for (int i = 0; i < TAGS.length; i++) {
            String tag = TAGS[i];
            Button b = new Button(this);
            boolean active = tag.isEmpty() ? current.isEmpty() : current.equalsIgnoreCase(tag)
                || current.toLowerCase().startsWith(tag.toLowerCase() + "-");
            b.setText((active ? "✓  " : "") + LABELS[i]);
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            b.setBackgroundResource(active ? R.drawable.button_primary : R.drawable.button_secondary);
            b.setOnClickListener(v -> applyLanguage(tag));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.setMargins(0, 0, 0, dp(7));
            root.addView(b, p);
        }
        return scroll;
    }

    private void applyLanguage(String tag) {
        LocaleManager manager = getSystemService(LocaleManager.class);
        manager.setApplicationLocales(tag.isEmpty()
            ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        Toast.makeText(this, getString(R.string.language_changed), Toast.LENGTH_SHORT).show();
        Intent restart = new Intent(this, HomeActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
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
