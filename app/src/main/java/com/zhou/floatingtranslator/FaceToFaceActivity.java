package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Face-to-face conversation mode.
 *
 * The user's language stays fixed. The partner side requests Android's automatic language
 * detection/switching when available, then the transcript is routed again through ML Kit Language ID.
 * The most recently detected foreign language is remembered so the user's next reply automatically
 * translates back to that language. The second language spinner is therefore only a fallback.
 */
public final class FaceToFaceActivity extends Activity implements RecognitionListener {
    private static final String PREFS = "floating_translator";
    private static final int REQUEST_MIC = 81;

    private SharedPreferences prefs;
    private Spinner myLanguage;
    private Spinner partnerFallback;
    private CheckBox autoSpeak;
    private TextView partnerOriginal;
    private TextView partnerTranslated;
    private TextView myOriginal;
    private TextView myTranslated;
    private TextView status;
    private Button partnerButton;
    private Button myButton;

    private SpeechRecognizer recognizer;
    private boolean partnerTurn = true;
    private boolean listening;
    private OfflineFirstTranslationRouter translator;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean("auto_language_enabled", true)
            .putString("language_mode", SherpaSpeechEngine.LANG_AUTO).apply();
        setTitle("面对面翻译");
        setContentView(buildUi());
        initSpeech();
        initTts();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column();
        root.setPadding(dp(18), dp(22), dp(18), dp(110));
        scroll.addView(root);

        TextView title = text("面对面翻译", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView tip = text(
            "我的语言固定；对方语言优先自动识别。系统语音服务不支持自动切换时，才使用“对方备用语言”。每次识别出的对方语言会自动记住，你说中文后会自动翻回对方刚才使用的语言。",
            13, Color.rgb(195, 185, 215));
        tip.setPadding(0, dp(4), 0, dp(12));
        root.addView(tip);

        LinearLayout settings = card(root);
        settings.addView(section("语言"));
        settings.addView(label("我的语言"));
        myLanguage = languageSpinner();
        myLanguage.setSelection(clamp(prefs.getInt("target_index", 0)));
        settings.addView(myLanguage, params());
        settings.addView(label("对方备用语言（自动识别失败时使用）"));
        partnerFallback = languageSpinner();
        partnerFallback.setSelection(clamp(prefs.getInt("source_index", 2)));
        settings.addView(partnerFallback, params());
        autoSpeak = new CheckBox(this);
        autoSpeak.setText("自动朗读译文");
        autoSpeak.setTextColor(Color.WHITE);
        autoSpeak.setChecked(prefs.getBoolean("face_auto_speak", true));
        settings.addView(autoSpeak);

        LinearLayout partnerCard = card(root);
        partnerCard.addView(section("对方"));
        partnerOriginal = text("等待对方说话…", 17, Color.WHITE);
        partnerCard.addView(partnerOriginal);
        partnerTranslated = text("", 22, Color.rgb(216, 198, 255));
        partnerTranslated.setPadding(0, dp(7), 0, dp(8));
        partnerCard.addView(partnerTranslated);
        partnerButton = primaryButton("🎤 对方说话｜自动识别语言");
        partnerButton.setOnClickListener(v -> startTurn(true));
        partnerCard.addView(partnerButton, params());

        LinearLayout myCard = card(root);
        myCard.addView(section("我"));
        myOriginal = text("等待你说话…", 17, Color.WHITE);
        myCard.addView(myOriginal);
        myTranslated = text("", 22, Color.rgb(216, 198, 255));
        myTranslated.setPadding(0, dp(7), 0, dp(8));
        myCard.addView(myTranslated);
        myButton = primaryButton("🎤 我说话｜自动翻回对方语言");
        myButton.setOnClickListener(v -> startTurn(false));
        myCard.addView(myButton, params());

        status = text("就绪 · 不需要 ROOT / 无障碍 / 录屏", 13, Color.rgb(182, 171, 205));
        status.setPadding(dp(2), dp(4), dp(2), dp(8));
        root.addView(status);

        Button clear = secondaryButton("清空本次对话");
        clear.setOnClickListener(v -> {
            partnerOriginal.setText("等待对方说话…");
            partnerTranslated.setText("");
            myOriginal.setText("等待你说话…");
            myTranslated.setText("");
            status.setText("已清空");
        });
        root.addView(clear, params());
        return scroll;
    }

    private void initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("本机没有可用的系统语音识别服务；可改用实时翻译里的离线 ASR");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
    }

    private void initTts() {
        tts = new TextToSpeech(this, result -> ttsReady = result == TextToSpeech.SUCCESS);
    }

    private void startTurn(boolean partner) {
        if (recognizer == null) {
            toast("系统语音识别不可用");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (listening) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            listening = false;
        }

        partnerTurn = partner;
        saveLanguageSettings();
        rebuildTranslator();

        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.getBoolean("prefer_offline", false));
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, partner ? fallback.speechTag : my.speechTag);
        if (partner) {
            // Android 14+ recognition services may honor these extras. Older providers safely ignore them.
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", true);
        }
        try {
            recognizer.startListening(intent);
            listening = true;
            status.setText(partner ? "正在听对方说话 · 自动检测语言…" : "正在听你说话…");
            updateButtons();
        } catch (Exception e) {
            listening = false;
            updateButtons();
            status.setText("启动语音识别失败：" + safe(e));
        }
    }

    private void rebuildTranslator() {
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
        }
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String engine = prefs.getString("engine_id", TranslationRouter.AUTO);
        translator = new OfflineFirstTranslationRouter(this, fallback.mlKitTag, my.mlKitTag, engine);
    }

    private void translateTranscript(String original) {
        if (translator == null) rebuildTranslator();
        if (partnerTurn) partnerOriginal.setText(original); else myOriginal.setText(original);
        status.setText("正在自动识别语言并翻译…");
        translator.translate(original, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (partnerTurn) partnerTranslated.setText(translated); else myTranslated.setText(translated);
                status.setText(engineName);
                prefs.edit().putString("last_original", original)
                    .putString("last_translation", translated).apply();
                if (autoSpeak.isChecked()) speakResult(translated, partnerTurn);
            }

            @Override public void onError(String message) {
                status.setText("翻译失败：" + message);
            }
        });
    }

    private void speakResult(String value, boolean partnerSpoke) {
        if (!ttsReady || tts == null || value == null || value.trim().isEmpty()) return;
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String lang = partnerSpoke ? my.mlKitTag
            : prefs.getString("last_partner_language", fallback.mlKitTag);
        try { tts.setLanguage(Locale.forLanguageTag(lang)); } catch (Exception ignored) {}
        try { tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "face-translation"); }
        catch (Exception ignored) {}
    }

    private void saveLanguageSettings() {
        int my = myLanguage.getSelectedItemPosition();
        int partner = partnerFallback.getSelectedItemPosition();
        prefs.edit()
            .putInt("target_index", my)
            .putInt("source_index", partner)
            .putBoolean("face_auto_speak", autoSpeak.isChecked())
            .putBoolean("auto_language_enabled", true)
            .putString("language_mode", SherpaSpeechEngine.LANG_AUTO)
            .apply();
    }

    @Override public void onReadyForSpeech(Bundle params) {
        status.setText(partnerTurn ? "请让对方说话…" : "请说话…");
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { status.setText("识别中…"); }

    @Override public void onError(int error) {
        listening = false;
        updateButtons();
        status.setText("语音识别未完成（" + error + "），可以重新点麦克风");
    }

    @Override public void onResults(Bundle results) {
        listening = false;
        updateButtons();
        ArrayList<String> list = results == null ? null
            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
            status.setText("没有识别到语音");
            return;
        }
        translateTranscript(list.get(0).trim());
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MIC && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTurn(partnerTurn);
        }
    }

    private void updateButtons() {
        if (partnerButton != null) partnerButton.setEnabled(!listening);
        if (myButton != null) myButton.setEnabled(!listening);
    }

    @Override protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
        }
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private Spinner languageSpinner() {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL));
        spinner.setBackgroundColor(Color.rgb(51, 45, 73));
        return spinner;
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.panel);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        root.addView(card, p);
        return card;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private TextView section(String value) {
        TextView t = text(value, 19, Color.WHITE);
        t.setTypeface(null, 1);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 13, Color.rgb(194, 184, 212));
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button primaryButton(String value) {
        Button b = button(value);
        b.setBackgroundResource(R.drawable.button_primary);
        return b;
    }

    private Button secondaryButton(String value) {
        Button b = button(value);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(value, LanguageOption.ALL.length - 1));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
