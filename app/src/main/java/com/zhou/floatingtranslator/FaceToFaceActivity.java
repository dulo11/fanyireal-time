package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

/** Face-to-face translation with manual turns and a hands-free continuous two-way mode. */
public final class FaceToFaceActivity extends Activity implements RecognitionListener {
    private static final String PREFS = "floating_translator";
    private static final int REQUEST_MIC = 81;
    private static final long LISTEN_TIMEOUT_MS = 90000L;
    private static final long TRANSLATE_TIMEOUT_MS = 65000L;

    private SharedPreferences prefs;
    private Spinner myLanguage;
    private Spinner partnerFallback;
    private Spinner asrSpinner;
    private CheckBox autoSpeak;
    private TextView partnerOriginal;
    private TextView partnerTranslated;
    private TextView myOriginal;
    private TextView myTranslated;
    private TextView conversationLog;
    private TextView status;
    private Button partnerButton;
    private Button myButton;
    private Button continuousButton;

    private final ArrayList<String> asrIds = new ArrayList<>();
    private final ArrayList<String> conversationLines = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private FaceOfflineSession offlineSession;
    private OfflineFirstTranslationRouter translator;
    private TextToSpeech tts;

    private boolean partnerTurn = true;
    private boolean listening;
    private boolean translating;
    private boolean destroyed;
    private boolean continuousMode;
    private boolean continuousRestartAfterTts;
    private boolean pendingPermissionForContinuous;
    private boolean ttsReady;
    private int generation;
    private String detectedLanguage = "";
    private String translatorPair = "";

    private final Runnable timeout = () -> {
        if (continuousMode) {
            stopRecognition();
            translating = false;
            status.setText("本句等待超时，继续监听…");
            updateButtons();
            scheduleContinuousNext(500L);
        } else {
            cancelTurn();
            status.setText("等待超时，已停止；请检查语音模型或网络后重试");
        }
    };

    private final Runnable continuousRestart = () -> {
        if (continuousMode && !destroyed && !listening && !translating) startContinuousUtterance();
    };

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
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView tip = text(
            "连续模式只点一次开始：双方直接轮流说话，识别到外语就翻译成“我的语言”，识别到“我的语言”就自动翻回最近的对方语言。无需每句再点“我说话/对方说话”。",
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
        settings.addView(label("语音识别引擎（连续模式优先多语言模型 / 系统识别）"));
        asrSpinner = new Spinner(this);
        ArrayList<String> asrNames = new ArrayList<>();
        asrIds.add("auto"); asrNames.add("自动：已下载多语言模型优先");
        asrIds.add("system"); asrNames.add("Android 系统语音识别");
        asrIds.add("vosk"); asrNames.add("Vosk 离线（仅按备用语言识别）");
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

        LinearLayout continuousCard = card(root);
        continuousCard.addView(section("连续双向对话"));
        TextView continuousTip = text(
            "推荐选择“自动”并下载 Whisper / Qwen3 / Omnilingual 等多语言模型。系统语音识别也会请求语言检测。Vosk、Reazon、Parakeet 等单语言模型不能真正任意切换语言。",
            13, Color.rgb(205, 194, 224));
        continuousTip.setPadding(0, dp(5), 0, dp(8));
        continuousCard.addView(continuousTip);
        continuousButton = primaryButton("▶ 开始连续双向对话｜无需逐句点击");
        continuousButton.setOnClickListener(v -> toggleContinuous());
        continuousCard.addView(continuousButton, params());

        LinearLayout partnerCard = card(root);
        partnerCard.addView(section("对方"));
        partnerOriginal = text("等待对方说话…", 17, Color.WHITE);
        partnerCard.addView(partnerOriginal);
        partnerTranslated = text("", 22, Color.rgb(216, 198, 255));
        partnerTranslated.setPadding(0, dp(7), 0, dp(8));
        partnerCard.addView(partnerTranslated);
        partnerButton = primaryButton("🎤 对方说话｜单句");
        partnerButton.setOnClickListener(v -> startTurn(true));
        partnerCard.addView(partnerButton, params());

        LinearLayout myCard = card(root);
        myCard.addView(section("我"));
        myOriginal = text("等待你说话…", 17, Color.WHITE);
        myCard.addView(myOriginal);
        myTranslated = text("", 22, Color.rgb(216, 198, 255));
        myTranslated.setPadding(0, dp(7), 0, dp(8));
        myCard.addView(myTranslated);
        myButton = primaryButton("🎤 我说话｜单句");
        myButton.setOnClickListener(v -> startTurn(false));
        myCard.addView(myButton, params());

        LinearLayout logCard = card(root);
        logCard.addView(section("本次连续对话"));
        conversationLog = text("暂无", 14, Color.rgb(224, 217, 238));
        conversationLog.setPadding(0, dp(6), 0, dp(4));
        logCard.addView(conversationLog);

        status = text("就绪 · 不需要 ROOT / 无障碍 / 录屏", 13, Color.rgb(182, 171, 205));
        status.setPadding(dp(2), dp(4), dp(2), dp(8));
        root.addView(status);

        Button cancel = secondaryButton("停止当前录音 / 停止连续对话");
        cancel.setOnClickListener(v -> { cancelTurn(); status.setText("已停止"); });
        root.addView(cancel, params());
        Button clear = secondaryButton("清空本次对话");
        clear.setOnClickListener(v -> {
            cancelTurn();
            translatorPair = "";
            conversationLines.clear();
            renderConversationLog();
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
        tts = new TextToSpeech(this, result -> {
            ttsReady = result == TextToSpeech.SUCCESS;
            if (!ttsReady || tts == null) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) { onTtsFinished(); }
                @Override public void onDone(String utteranceId) { onTtsFinished(); }
            });
        });
    }

    private void onTtsFinished() {
        main.post(() -> {
            if (!continuousRestartAfterTts) return;
            continuousRestartAfterTts = false;
            scheduleContinuousNext(250L);
        });
    }

    private void toggleContinuous() {
        if (continuousMode) {
            cancelTurn();
            status.setText("连续双向对话已停止");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionForContinuous = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        startContinuousMode();
    }

    private void startContinuousMode() {
        if (listening || translating) cancelTurn();
        saveLanguageSettings();
        rebuildTranslator();
        if (tts != null) tts.stop();
        continuousMode = true;
        continuousRestartAfterTts = false;
        generation++;
        updateButtons();
        status.setText("连续双向对话已开启 · 正在准备自动识别…");
        scheduleContinuousNext(100L);
    }

    private void scheduleContinuousNext(long delayMs) {
        main.removeCallbacks(continuousRestart);
        if (continuousMode && !destroyed) main.postDelayed(continuousRestart, delayMs);
    }

    private void startContinuousUtterance() {
        if (!continuousMode || destroyed || listening || translating) return;
        saveLanguageSettings();
        rebuildTranslator();
        if (tts != null) tts.stop();
        detectedLanguage = "";
        generation++;
        int turn = generation;
        listening = true;
        updateButtons();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS);

        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String asr = selectAsr(fallback.mlKitTag);
        if (asr == null) {
            stopRecognition();
            status.setText("所选离线模型尚未下载，请到设置→模型中心下载");
            scheduleContinuousNext(1200L);
            return;
        }
        if (!"system".equals(asr)) {
            offlineSession = new FaceOfflineSession(this, asr, fallback.mlKitTag, true,
                new FaceOfflineSession.Callback() {
                    public void status(String value) {
                        if (generation == turn && continuousMode) status.setText("连续监听 · " + value);
                    }
                    public void result(String text, String language) {
                        if (generation != turn || !continuousMode || !listening) return;
                        detectedLanguage = language == null ? "" : language;
                        stopRecognition();
                        translateContinuousTranscript(text);
                    }
                    public void error(String text) {
                        if (generation != turn || !continuousMode) return;
                        stopRecognition();
                        status.setText("识别失败：" + text + " · 将继续监听");
                        updateButtons();
                        scheduleContinuousNext(900L);
                    }
                });
            offlineSession.start();
            return;
        }
        startSystemRecognition(turn, true, fallback.speechTag);
    }

    private void startTurn(boolean partner) {
        if (continuousMode) cancelTurn();
        if (listening || translating) return;
        partnerTurn = partner;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionForContinuous = false;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        saveLanguageSettings();
        rebuildTranslator();
        if (tts != null) tts.stop();
        detectedLanguage = "";
        generation++;
        int turn = generation;
        listening = true;
        updateButtons();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, LISTEN_TIMEOUT_MS);

        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String language = partner ? fallback.mlKitTag : my.mlKitTag;
        String asr = selectAsr(language);
        if (asr == null) {
            cancelTurn();
            status.setText("所选离线模型尚未下载，请到设置→模型中心下载");
            return;
        }
        if (!"system".equals(asr)) {
            offlineSession = new FaceOfflineSession(this, asr, language, partner,
                new FaceOfflineSession.Callback() {
                    public void status(String value) { if (generation == turn) status.setText(value); }
                    public void result(String text, String detected) {
                        if (generation != turn || !listening) return;
                        detectedLanguage = detected == null ? "" : detected;
                        stopRecognition();
                        translateManualTranscript(text, partner);
                    }
                    public void error(String text) {
                        if (generation != turn) return;
                        cancelTurn();
                        status.setText(text);
                    }
                });
            offlineSession.start();
            return;
        }
        startSystemRecognition(turn, partner, partner ? fallback.speechTag : my.speechTag);
    }

    private void startSystemRecognition(int turn, boolean requestAutoDetection, String fallbackSpeechTag) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopRecognition();
            if (continuousMode) {
                status.setText("系统语音服务不可用，请下载多语言离线模型");
                scheduleContinuousNext(1500L);
            } else {
                cancelTurn();
                status.setText("系统语音服务不可用，请下载并选择离线模型");
            }
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) {
                if (generation == turn) status.setText(continuousMode ? "连续监听中 · 请直接说话…"
                    : (partnerTurn ? "请让对方说话…" : "请说话…"));
            }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] v) {}
            public void onEndOfSpeech() { if (generation == turn) status.setText("识别中…"); }
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, fallbackSpeechTag);
        if (requestAutoDetection && Build.VERSION.SDK_INT >= 34) {
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced");
        }
        try {
            recognizer.startListening(intent);
            status.setText(continuousMode ? "连续监听中 · 自动检测双方语言…"
                : (partnerTurn ? "正在听对方说话 · 自动检测语言…" : "正在听你说话…"));
        } catch (Exception e) {
            stopRecognition();
            if (continuousMode) {
                status.setText("启动语音识别失败：" + safe(e) + " · 将重试");
                scheduleContinuousNext(1200L);
            } else {
                cancelTurn();
                status.setText("启动语音识别失败：" + safe(e));
            }
        }
    }

    private String selectAsr(String language) {
        String selected = asrIds.get(asrSpinner.getSelectedItemPosition());
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            if ("system".equals(selected)) return selected;
            if ("vosk".equals(selected)) return OfflineSpeechEngine.isLanguageInstalled(this, language) ? selected : null;
            if (!"auto".equals(selected)) return store.isInstalled(selected) ? selected : null;
            String[] candidates = {TranslationService.ASR_WHISPER_SMALL, TranslationService.ASR_QWEN3,
                TranslationService.ASR_OMNILINGUAL, TranslationService.ASR_WHISPER_MEDIUM,
                TranslationService.ASR_SENSEVOICE};
            for (String id : candidates) {
                if (TranslationService.ASR_SENSEVOICE.equals(id)
                    && !("zh".equals(language) || "en".equals(language) || "ja".equals(language)
                        || "ko".equals(language) || "yue".equals(language))) continue;
                if (store.isInstalled(id)) return id;
            }
            if (continuousMode && SpeechRecognizer.isRecognitionAvailable(this)) return "system";
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

    private void translateManualTranscript(String original, boolean spokePartner) {
        if (translator == null) rebuildTranslator();
        final int turn = generation;
        translating = true;
        updateButtons();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TRANSLATE_TIMEOUT_MS);
        if (spokePartner) partnerOriginal.setText(original); else myOriginal.setText(original);
        status.setText("正在翻译…");
        translator.translateTurn(original, spokePartner, detectedLanguage, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (destroyed || generation != turn) return;
                translating = false;
                main.removeCallbacks(timeout);
                updateButtons();
                if (spokePartner) partnerTranslated.setText(translated); else myTranslated.setText(translated);
                status.setText(engineName);
                saveRecent(original, translated);
                appendConversation(spokePartner ? "对方" : "我", original, translated,
                    translator == null ? "" : translator.lastDetectedLanguage());
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

    private void translateContinuousTranscript(String original) {
        if (!continuousMode || destroyed) return;
        if (translator == null) rebuildTranslator();
        final int turn = generation;
        translating = true;
        updateButtons();
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TRANSLATE_TIMEOUT_MS);
        status.setText("连续模式 · 正在识别语言并翻译…");
        translator.translate(original, detectedLanguage, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (destroyed || generation != turn || !continuousMode) return;
                translating = false;
                main.removeCallbacks(timeout);
                String detected = translator == null ? "" : translator.lastDetectedLanguage();
                LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
                boolean mySpoke = OfflineFirstTranslationRouter.sameLanguage(detected, my.mlKitTag);
                boolean spokePartner = !mySpoke;
                if (mySpoke) {
                    myOriginal.setText(original);
                    myTranslated.setText(translated);
                } else {
                    partnerOriginal.setText(original);
                    partnerTranslated.setText(translated);
                }
                saveRecent(original, translated);
                appendConversation(mySpoke ? "我" : "对方", original, translated, detected);
                status.setText(engineName + " · 连续监听将自动继续");
                updateButtons();

                boolean speaking = autoSpeak.isChecked() && speakResult(translated, spokePartner);
                if (speaking) continuousRestartAfterTts = true;
                else scheduleContinuousNext(350L);
            }

            @Override public void onError(String message) {
                if (destroyed || generation != turn || !continuousMode) return;
                translating = false;
                main.removeCallbacks(timeout);
                updateButtons();
                status.setText("翻译失败：" + message + " · 将继续监听");
                scheduleContinuousNext(900L);
            }
        });
    }

    private boolean speakResult(String value, boolean partnerSpoke) {
        if (!ttsReady || tts == null || value == null || value.trim().isEmpty()) return false;
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String lang = partnerSpoke ? my.mlKitTag
            : (translator == null ? fallback.mlKitTag : translator.partnerLanguage());
        try {
            int result = tts.setLanguage(Locale.forLanguageTag(lang));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                status.append(" · 当前设备缺少该语言的朗读语音包");
                return false;
            }
            int speak = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "face-translation");
            return speak == TextToSpeech.SUCCESS;
        } catch (Exception e) {
            status.append(" · 朗读失败");
            return false;
        }
    }

    private void saveRecent(String original, String translated) {
        prefs.edit().putString("last_original", original)
            .putString("last_translation", translated).apply();
    }

    private void appendConversation(String who, String original, String translated, String language) {
        String lang = language == null || language.trim().isEmpty() ? "" : " [" + language + "]";
        conversationLines.add(who + lang + "：" + original + "\n→ " + translated);
        while (conversationLines.size() > 30) conversationLines.remove(0);
        renderConversationLog();
    }

    private void renderConversationLog() {
        if (conversationLog == null) return;
        if (conversationLines.isEmpty()) {
            conversationLog.setText("暂无");
            return;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < conversationLines.size(); i++) {
            if (i > 0) out.append("\n\n");
            out.append(conversationLines.get(i));
        }
        conversationLog.setText(out.toString());
    }

    private void saveLanguageSettings() {
        int my = myLanguage.getSelectedItemPosition();
        int partner = partnerFallback.getSelectedItemPosition();
        prefs.edit()
            .putInt("face_target_index", my)
            .putInt("face_source_index", partner)
            .putBoolean("face_auto_speak", autoSpeak.isChecked())
            .putString("face_asr", asrIds.get(asrSpinner.getSelectedItemPosition()))
            .putBoolean("auto_language_enabled", true)
            .apply();
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { status.setText("识别中…"); }

    @Override public void onError(int error) {
        stopRecognition();
        main.removeCallbacks(timeout);
        if (continuousMode) {
            status.setText("本句未识别（" + error + "），继续监听…");
            updateButtons();
            scheduleContinuousNext(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200L : 500L);
        } else {
            cancelTurn();
            status.setText("语音识别未完成（" + error + "），可重试或选择已下载的离线模型");
        }
    }

    @Override public void onResults(Bundle results) {
        boolean wasContinuous = continuousMode;
        boolean manualPartner = partnerTurn;
        stopRecognition();
        ArrayList<String> list = results == null ? null
            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
            updateButtons();
            main.removeCallbacks(timeout);
            if (wasContinuous) {
                status.setText("没有识别到语音，继续监听…");
                scheduleContinuousNext(400L);
            } else status.setText("没有识别到语音");
            return;
        }
        if (wasContinuous) translateContinuousTranscript(list.get(0).trim());
        else translateManualTranscript(list.get(0).trim(), manualPartner);
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MIC || grantResults.length == 0
            || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        if (pendingPermissionForContinuous) {
            pendingPermissionForContinuous = false;
            startContinuousMode();
        } else startTurn(partnerTurn);
    }

    private void updateButtons() {
        boolean idle = !listening && !translating;
        if (partnerButton != null) partnerButton.setEnabled(idle && !continuousMode);
        if (myButton != null) myButton.setEnabled(idle && !continuousMode);
        if (myLanguage != null) myLanguage.setEnabled(idle && !continuousMode);
        if (partnerFallback != null) partnerFallback.setEnabled(idle && !continuousMode);
        if (asrSpinner != null) asrSpinner.setEnabled(idle && !continuousMode);
        if (continuousButton != null) {
            continuousButton.setEnabled(!translating || continuousMode);
            continuousButton.setText(continuousMode
                ? "■ 停止连续双向对话"
                : "▶ 开始连续双向对话｜无需逐句点击");
        }
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
        continuousMode = false;
        continuousRestartAfterTts = false;
        pendingPermissionForContinuous = false;
        generation++;
        main.removeCallbacks(timeout);
        main.removeCallbacks(continuousRestart);
        stopRecognition();
        translating = false;
        if (translator != null) { translator.close(); translator = null; }
        translatorPair = "";
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
        t.setTypeface(null, android.graphics.Typeface.BOLD);
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
