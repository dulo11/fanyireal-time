package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    private boolean translating;
    private boolean destroyed;
    private int generation;
    private String detectedLanguage = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private FaceOfflineSession offlineSession;
    private Spinner asrSpinner;
    private final ArrayList<String> asrIds = new ArrayList<>();
    private String translatorPair = "";
    private String spokenTarget = "";
    private final Runnable timeout = () -> { cancelTurn(); status.setText("等待超时，已停止；请检查语音模型或网络后重试"); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setTitle("面对面翻译");
        setContentView(buildUi());
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
            "我的语言固定，对方语言可自动识别。已下载的多语言模型优先；Vosk 和单语言模型按备用语言识别。每次说完请稍作停顿，译文完成后再让另一方说话。",
            13, Color.rgb(195, 185, 215));
        tip.setPadding(0, dp(4), 0, dp(12));
        root.addView(tip);

        LinearLayout settings = card(root);
        settings.addView(section("语言"));
        settings.addView(label("我的语言"));
        myLanguage = languageSpinner();
        myLanguage.setSelection(clamp(prefs.getInt("face_target_index", prefs.getInt("target_index", 0))));
        settings.addView(myLanguage, params());
        settings.addView(label("对方备用语言（自动识别失败时使用）"));
        partnerFallback = languageSpinner();
        partnerFallback.setSelection(clamp(prefs.getInt("face_source_index", prefs.getInt("source_index", 2))));
        settings.addView(partnerFallback, params());
        settings.addView(label("语音识别引擎（离线模型需先在设置中下载）"));
        asrSpinner = new Spinner(this);
        ArrayList<String> asrNames = new ArrayList<>();
        asrIds.add("auto"); asrNames.add("自动：已下载多语言模型优先");
        asrIds.add("system"); asrNames.add("Android 系统语音识别");
        asrIds.add("vosk"); asrNames.add("Vosk 离线（按备用语言识别）");
        for (String id : new String[]{TranslationService.ASR_SENSEVOICE, TranslationService.ASR_WHISPER_SMALL,
            TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_QWEN3, TranslationService.ASR_OMNILINGUAL,
            TranslationService.ASR_REAZON, TranslationService.ASR_PARAKEET}) {
            OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(id);
            if (model != null) { asrIds.add(id); asrNames.add(model.name); }
        }
        asrSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, asrNames));
        asrSpinner.setSelection(Math.max(0, asrIds.indexOf(prefs.getString("face_asr", "auto"))));
        settings.addView(asrSpinner, params());
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

        Button cancel = secondaryButton("停止 / 取消本句");
        cancel.setOnClickListener(v -> { cancelTurn(); status.setText("已停止"); });
        root.addView(cancel, params());
        Button clear = secondaryButton("清空本次对话");
        clear.setOnClickListener(v -> {
            cancelTurn();
            translatorPair = "";
            partnerOriginal.setText("等待对方说话…");
            partnerTranslated.setText("");
            myOriginal.setText("等待你说话…");
            myTranslated.setText("");
            status.setText("已清空");
        });
        root.addView(clear, params());
        return scroll;
    }

    private void initTts() {
        tts = new TextToSpeech(this, result -> ttsReady = result == TextToSpeech.SUCCESS);
    }

    private void startTurn(boolean partner) {
        if (listening || translating) return;
        partnerTurn = partner;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        partnerTurn = partner;
        saveLanguageSettings();
        rebuildTranslator();
        if (tts != null) tts.stop();
        detectedLanguage = "";
        generation++;
        int turn = generation;
        listening = true;
        updateButtons();
        main.postDelayed(timeout, 90000L);

        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String asr = selectAsr(partner ? fallback.mlKitTag : my.mlKitTag);
        if (asr == null) { cancelTurn(); status.setText("所选离线模型尚未下载，请到设置→模型中心下载"); return; }
        if (!"system".equals(asr)) {
            offlineSession = new FaceOfflineSession(this, asr, partner ? fallback.mlKitTag : my.mlKitTag,
                partner, new FaceOfflineSession.Callback() {
                    public void status(String value) { if (generation == turn) status.setText(value); }
                    public void result(String text, String language) {
                        if (generation != turn || !listening) return;
                        detectedLanguage = language;
                        stopRecognition();
                        translateTranscript(text);
                    }
                    public void error(String text) {
                        if (generation != turn) return;
                        cancelTurn(); status.setText(text);
                    }
                });
            offlineSession.start();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            cancelTurn(); status.setText("系统语音服务不可用，请下载并选择离线模型"); return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { if (generation == turn) FaceToFaceActivity.this.onReadyForSpeech(b); }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] v) {}
            public void onEndOfSpeech() { if (generation == turn) FaceToFaceActivity.this.onEndOfSpeech(); }
            public void onError(int e) { if (generation == turn) FaceToFaceActivity.this.onError(e); }
            public void onResults(Bundle b) { if (generation == turn && listening) FaceToFaceActivity.this.onResults(b); }
            public void onPartialResults(Bundle b) {}
            public void onEvent(int e, Bundle b) {}
            public void onLanguageDetection(Bundle b) {
                if (generation == turn && b != null) detectedLanguage = b.getString("detected_language", "");
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.getBoolean("prefer_offline", false));
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, partner ? fallback.speechTag : my.speechTag);
        if (partner && Build.VERSION.SDK_INT >= 34) {
            // Android 14+ recognition services may honor these extras. Older providers safely ignore them.
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced");
        }
        try {
            recognizer.startListening(intent);
            listening = true;
            status.setText(partner ? "正在听对方说话 · 自动检测语言…" : "正在听你说话…");
            updateButtons();
        } catch (Exception e) {
            cancelTurn();
            status.setText("启动语音识别失败：" + safe(e));
        }
    }

    private String selectAsr(String language) {
        String selected = asrIds.get(asrSpinner.getSelectedItemPosition());
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            if ("system".equals(selected)) return selected;
            if ("vosk".equals(selected)) return OfflineSpeechEngine.isLanguageInstalled(this, language) ? selected : null;
            if (!"auto".equals(selected)) return store.isInstalled(selected) ? selected : null;
            String[] candidates = {TranslationService.ASR_WHISPER_SMALL, TranslationService.ASR_SENSEVOICE,
                TranslationService.ASR_QWEN3, TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_OMNILINGUAL};
            for (String id : candidates) {
                if (TranslationService.ASR_SENSEVOICE.equals(id)
                    && !("zh".equals(language) || "en".equals(language) || "ja".equals(language)
                        || "ko".equals(language) || "yue".equals(language))) continue;
                if (store.isInstalled(id)) return id;
            }
            if (OfflineSpeechEngine.isLanguageInstalled(this, language)) return "vosk";
            return "system";
        } finally { store.close(); }
    }

    private void rebuildTranslator() {
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String engine = prefs.getString("engine_id", TranslationRouter.AUTO);
        String pair = my.mlKitTag + ":" + fallback.mlKitTag + ":" + engine;
        if (translator != null && pair.equals(translatorPair)) return;
        if (translator != null) translator.close();
        translator = new OfflineFirstTranslationRouter(this, fallback.mlKitTag, my.mlKitTag, engine);
        translatorPair = pair;
    }

    private void translateTranscript(String original) {
        if (translator == null) rebuildTranslator();
        final boolean spokePartner = partnerTurn;
        final int turn = generation;
        translating = true;
        updateButtons();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 65000L);
        if (partnerTurn) partnerOriginal.setText(original); else myOriginal.setText(original);
        status.setText("正在自动识别语言并翻译…");
        translator.translateTurn(original, spokePartner, detectedLanguage, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (destroyed || generation != turn) return;
                translating = false;
                main.removeCallbacks(timeout);
                updateButtons();
                if (spokePartner) partnerTranslated.setText(translated); else myTranslated.setText(translated);
                status.setText(engineName);
                prefs.edit().putString("last_original", original)
                    .putString("last_translation", translated).apply();
                if (autoSpeak.isChecked()) speakResult(translated, spokePartner);
            }

            @Override public void onError(String message) {
                if (destroyed || generation != turn) return;
                translating = false;
                main.removeCallbacks(timeout);
                updateButtons();
                status.setText("翻译失败：" + message);
            }
        });
    }

    private void speakResult(String value, boolean partnerSpoke) {
        if (!ttsReady || tts == null || value == null || value.trim().isEmpty()) return;
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String lang = partnerSpoke ? my.mlKitTag
            : translator.partnerLanguage();
        try {
            int result = tts.setLanguage(Locale.forLanguageTag(lang));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                status.append(" · 当前设备缺少该语言的朗读语音包"); return;
            }
        } catch (Exception e) { status.append(" · 朗读语言初始化失败"); return; }
        try { tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "face-translation"); }
        catch (Exception ignored) {}
    }

    private void saveLanguageSettings() {
        int my = myLanguage.getSelectedItemPosition();
        int partner = partnerFallback.getSelectedItemPosition();
        prefs.edit()
            .putInt("face_target_index", my)
            .putInt("face_source_index", partner)
            .putBoolean("face_auto_speak", autoSpeak.isChecked())
            .putString("face_asr", asrIds.get(asrSpinner.getSelectedItemPosition()))
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
        cancelTurn();
        status.setText("语音识别未完成（" + error + "），可重试或选择已下载的离线模型");
    }

    @Override public void onResults(Bundle results) {
        stopRecognition();
        ArrayList<String> list = results == null ? null
            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
            updateButtons();
            main.removeCallbacks(timeout);
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
        boolean enabled = !listening && !translating;
        if (partnerButton != null) partnerButton.setEnabled(enabled);
        if (myButton != null) myButton.setEnabled(enabled);
        if (myLanguage != null) myLanguage.setEnabled(enabled);
        if (partnerFallback != null) partnerFallback.setEnabled(enabled);
        if (asrSpinner != null) asrSpinner.setEnabled(enabled);
    }

    private void stopRecognition() {
        listening = false;
        if (offlineSession != null) { offlineSession.close(); offlineSession = null; }
        if (recognizer != null) {
            SpeechRecognizer old = recognizer;
            recognizer = null;
            old.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) {} public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {} public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {} public void onError(int e) {}
                public void onResults(Bundle b) {} public void onPartialResults(Bundle b) {}
                public void onEvent(int e, Bundle b) {}
            });
            try { old.cancel(); old.destroy(); } catch (Exception ignored) {}
        }
    }

    private void cancelTurn() {
        generation++;
        main.removeCallbacks(timeout);
        stopRecognition();
        translating = false;
        if (translator != null) { translator.close(); translator = null; }
        if (tts != null) tts.stop();
        updateButtons();
    }

    @Override protected void onStop() {
        cancelTurn();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelTurn();
        if (tts != null) tts.shutdown();
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
