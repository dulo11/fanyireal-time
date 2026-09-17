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
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/** Face-to-face translation with manual turns and a true open-mic continuous mode. */
public final class FaceToFaceActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private static final int REQUEST_MIC = 81;
    private static final long MANUAL_TIMEOUT_MS = 90000L;
    private static final long TTS_ECHO_WINDOW_MS = 12000L;
    private static final int MAX_PENDING_UTTERANCES = 24;

    private SharedPreferences prefs;
    private Spinner myLanguage;
    private Spinner partnerFallback;
    private Spinner asrSpinner;
    private Spinner recognitionMode;
    private Spinner fixedInputLanguage;
    private TextView fixedInputLabel;
    private TextView modeHint;
    private TextView languageStatus;
    private CheckBox continuousSpeak;
    private int recognitionGeneration;
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
    private final ArrayDeque<ContinuousUtterance> pendingUtterances = new ArrayDeque<>();
    private final ArrayDeque<EchoSample> recentTtsEchoes = new ArrayDeque<>();
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
    private boolean persistentOfflineCapture;
    private boolean pendingPermissionForContinuous;
    private boolean ttsReady;
    private int generation;
    private long utteranceCounter;
    private String translatorPair = "";

    private final Runnable manualTimeout = () -> {
        if (continuousMode) return;
        cancelTurn();
        status.setText("等待超时，已停止；请检查语音模型后重试");
    };

    private final Runnable continuousRestart = () -> {
        if (!continuousMode || destroyed || persistentOfflineCapture || listening) return;
        startContinuousCapture();
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
            "一次开启可连续说话，不要求双方轮流。自动模式会复核语音标签与文字语言；若英语被听成别的语言，可选“固定输入语言”，将输入设为英语、我的语言设为中文。",
            13, Color.rgb(195, 185, 215));
        tip.setPadding(0, dp(4), 0, dp(12));
        root.addView(tip);

        LinearLayout settings = card(root);
        settings.addView(section("语言"));
        settings.addView(label("我的语言"));
        myLanguage = languageSpinner();
        myLanguage.setSelection(clamp(prefs.getInt("face_target_index", prefs.getInt("target_index", 0))));
        settings.addView(myLanguage, params());
        settings.addView(label("对方语言（限定双向使用；自动模式首次回复使用）"));
        partnerFallback = languageSpinner();
        partnerFallback.setSelection(clamp(prefs.getInt("face_source_index", prefs.getInt("source_index", 2))));
        settings.addView(partnerFallback, params());
        settings.addView(label("连续对话的语言模式"));
        recognitionMode = new Spinner(this);
        recognitionMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"自动识别 · 双向对话", "限定双方语言 · 自动双向", "固定输入语言 → 我的语言"}));
        String savedMode = ConversationLanguagePolicy.mode(prefs.getString("face_language_mode", "auto"));
        recognitionMode.setSelection(java.util.Arrays.asList(ConversationLanguagePolicy.MODES).indexOf(savedMode));
        settings.addView(recognitionMode, params());
        fixedInputLabel = label("固定输入语言（例如：英语）");
        settings.addView(fixedInputLabel);
        fixedInputLanguage = languageSpinner();
        fixedInputLanguage.setSelection(clamp(prefs.getInt("face_fixed_input_index", prefs.getInt("face_source_index", 2))));
        settings.addView(fixedInputLanguage, params());
        modeHint = text("", 13, Color.rgb(195, 185, 215));
        settings.addView(modeHint, params());
        recognitionMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refreshLanguageMode(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        settings.addView(label("语音识别引擎（连续模式推荐 Whisper / Qwen3 / Omnilingual）"));
        asrSpinner = new Spinner(this);
        ArrayList<String> asrNames = new ArrayList<>();
        asrIds.add("auto"); asrNames.add("自动：已下载多语言模型优先");
        asrIds.add("system"); asrNames.add("Android 系统语音识别");
        asrIds.add("vosk"); asrNames.add("Vosk 离线（连续模式需固定输入语言）");
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
        autoSpeak.setText("单句模式自动朗读译文");
        autoSpeak.setTextColor(Color.WHITE);
        autoSpeak.setChecked(prefs.getBoolean("face_auto_speak", true));
        settings.addView(autoSpeak);
        continuousSpeak = new CheckBox(this);
        continuousSpeak.setText("连续模式也朗读（外放可能录回，默认关闭）");
        continuousSpeak.setTextColor(Color.WHITE);
        continuousSpeak.setChecked(prefs.getBoolean("face_continuous_speak", false));
        settings.addView(continuousSpeak);
        languageStatus = text("本句语言与翻译方向将在这里显示", 14, Color.rgb(216, 198, 255));
        settings.addView(languageStatus, params());
        refreshLanguageMode();

        LinearLayout continuousCard = card(root);
        continuousCard.addView(section("连续开放麦克风"));
        TextView continuousTip = text(
            "本地多语言模型只加载一次并持续监听，VAD 每次停顿自动切句。翻译和下一句录音并行；同一方可连续说多句，也可在日语/英语等语言间切换。系统 SpeechRecognizer 会逐句自动重启，但也不要求双方交替。",
            13, Color.rgb(205, 194, 224));
        continuousTip.setPadding(0, dp(5), 0, dp(8));
        continuousCard.addView(continuousTip);
        continuousButton = primaryButton("▶ 开始连续开放麦克风｜无需轮流");
        continuousButton.setOnClickListener(v -> toggleContinuous());
        continuousCard.addView(continuousButton, params());

        LinearLayout partnerCard = card(root);
        partnerCard.addView(section("对方"));
        partnerOriginal = text("等待对方说话…", 17, Color.WHITE);
        partnerCard.addView(partnerOriginal);
        partnerTranslated = text("", 22, Color.rgb(216, 198, 255));
        partnerTranslated.setPadding(0, dp(7), 0, dp(8));
        partnerCard.addView(partnerTranslated);
        partnerButton = primaryButton("🎤 对方说话｜单句备用");
        partnerButton.setOnClickListener(v -> startManualTurn(true));
        partnerCard.addView(partnerButton, params());

        LinearLayout myCard = card(root);
        myCard.addView(section("我"));
        myOriginal = text("等待你说话…", 17, Color.WHITE);
        myCard.addView(myOriginal);
        myTranslated = text("", 22, Color.rgb(216, 198, 255));
        myTranslated.setPadding(0, dp(7), 0, dp(8));
        myCard.addView(myTranslated);
        myButton = primaryButton("🎤 我说话｜单句备用");
        myButton.setOnClickListener(v -> startManualTurn(false));
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
        tts = new TextToSpeech(this, result -> ttsReady = result == TextToSpeech.SUCCESS);
    }

    private void toggleContinuous() {
        if (continuousMode) {
            cancelTurn();
            status.setText("连续开放麦克风已停止");
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
        cancelTurn();
        LanguageOption mine = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption other = (LanguageOption) partnerFallback.getSelectedItem();
        if (!ConversationLanguagePolicy.FIXED.equals(languageMode()) && mine.mlKitTag.equals(other.mlKitTag)) {
            status.setText("双向对话的双方语言不能相同；单向翻译请选固定输入语言"); return;
        }
        saveLanguageSettings();
        rebuildTranslator();
        continuousMode = true;
        pendingUtterances.clear();
        recentTtsEchoes.clear();
        persistentOfflineCapture = false;
        generation++;
        updateButtons();
        status.setText("连续开放麦克风已开启 · 正在准备自动识别…");
        startContinuousCapture();
    }

    private void startContinuousCapture() {
        if (!continuousMode || destroyed || listening) return;
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        boolean fixed = ConversationLanguagePolicy.FIXED.equals(languageMode());
        LanguageOption input = fixed ? (LanguageOption) fixedInputLanguage.getSelectedItem() : fallback;
        String asr = selectConversationAsr(input.mlKitTag);
        if (asr == null) {
            cancelTurn();
            status.setText("没有适合当前模式的已下载模型：自动双向需多语言模型；固定输入请用 Whisper、SenseVoice、Vosk 或系统识别");
            languageStatus.setText("请核对语言模式、输入语言和语音识别引擎");
            return;
        }
        final int session = generation;
        if (!"system".equals(asr)) {
            persistentOfflineCapture = true;
            listening = true;
            updateButtons();
            offlineSession = new FaceOfflineSession(this, asr, input.mlKitTag, !fixed,
                new FaceOfflineSession.Callback() {
                    @Override public void status(String value) {
                        if (generation == session && continuousMode && !translating) {
                            status.setText("持续监听 · " + value);
                        }
                    }
                    @Override public void result(String text, String language) {
                        if (generation != session || !continuousMode || !listening) return;
                        enqueueContinuous(text, language, session);
                    }
                    @Override public void error(String text) {
                        if (generation != session || !continuousMode) return;
                        stopRecognition();
                        persistentOfflineCapture = false;
                        status.setText("连续识别失败：" + text + " · 将重启");
                        updateButtons();
                        scheduleContinuousNext(900L);
                    }
                });
            offlineSession.start();
            return;
        }
        persistentOfflineCapture = false;
        startSystemRecognition(session, true, true, input.speechTag);
    }

    private void scheduleContinuousNext(long delayMs) {
        main.removeCallbacks(continuousRestart);
        if (continuousMode && !destroyed) main.postDelayed(continuousRestart, delayMs);
    }

    private void startManualTurn(boolean partner) {
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
        generation++;
        final int session = generation;
        listening = true;
        updateButtons();
        main.removeCallbacks(manualTimeout);
        main.postDelayed(manualTimeout, MANUAL_TIMEOUT_MS);

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
                    @Override public void status(String value) {
                        if (generation == session) status.setText(value);
                    }
                    @Override public void result(String text, String detected) {
                        if (generation != session || !listening) return;
                        stopRecognition();
                        main.removeCallbacks(manualTimeout);
                        translateManualTranscript(text, partner, detected, session);
                    }
                    @Override public void error(String text) {
                        if (generation != session) return;
                        cancelTurn();
                        status.setText(text);
                    }
                });
            offlineSession.start();
            return;
        }
        startSystemRecognition(session, false, partner, partner ? fallback.speechTag : my.speechTag);
    }

    private void startSystemRecognition(int session, boolean continuous, boolean partnerManual,
                                        String fallbackSpeechTag) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listening = false;
            if (continuous) {
                status.setText("系统语音服务不可用，请下载多语言离线模型");
                scheduleContinuousNext(1500L);
            } else {
                cancelTurn();
                status.setText("系统语音服务不可用，请下载并选择离线模型");
            }
            return;
        }
        destroySystemRecognizer();
        final int captureId = recognitionGeneration;
        final String[] detected = {""};
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                if (generation == session && recognitionGeneration == captureId) status.setText(continuous
                    ? "持续监听中 · 同一方可以连续说多句…"
                    : (partnerManual ? "请让对方说话…" : "请说话…"));
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] v) {}
            @Override public void onEndOfSpeech() { if (generation == session && recognitionGeneration == captureId) status.setText("识别中…"); }
            @Override public void onError(int error) {
                if (generation != session || recognitionGeneration != captureId) return;
                listening = false;
                destroySystemRecognizer();
                if (continuousMode && continuous) {
                    status.setText("本句未识别（" + error + "），继续监听…");
                    updateButtons();
                    scheduleContinuousNext(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000L : 220L);
                } else {
                    cancelTurn();
                    status.setText("语音识别未完成（" + error + "），可重试或换离线模型");
                }
            }
            @Override public void onResults(Bundle b) {
                if (generation != session || recognitionGeneration != captureId) return;
                listening = false;
                destroySystemRecognizer();
                ArrayList<String> values = b == null ? null
                    : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String value = values == null || values.isEmpty() ? "" : values.get(0).trim();
                if (continuousMode && continuous) {
                    if (!value.isEmpty()) enqueueContinuous(value, detected[0], session);
                    status.setText(value.isEmpty() ? "没有识别到语音，继续监听…" : "已接收一句 · 麦克风继续");
                    updateButtons();
                    scheduleContinuousNext(120L);
                } else {
                    main.removeCallbacks(manualTimeout);
                    if (value.isEmpty()) {
                        updateButtons();
                        status.setText("没有识别到语音");
                    } else {
                        translateManualTranscript(value, partnerManual, detected[0], session);
                    }
                }
            }
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int e, Bundle b) {}
            public void onLanguageDetection(Bundle b) {
                if (generation == session && recognitionGeneration == captureId && b != null) {
                    detected[0] = b.getString("detected_language", "");
                }
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.getBoolean("prefer_offline", false));
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, fallbackSpeechTag);
        if (continuous && !ConversationLanguagePolicy.FIXED.equals(languageMode()) && Build.VERSION.SDK_INT >= 34) {
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true);
            intent.putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced");
            if (ConversationLanguagePolicy.PAIR.equals(languageMode())) {
                ArrayList<String> allowed = new ArrayList<>();
                allowed.add(((LanguageOption) myLanguage.getSelectedItem()).speechTag);
                allowed.add(((LanguageOption) partnerFallback.getSelectedItem()).speechTag);
                intent.putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", allowed);
                intent.putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", allowed);
            }
        }
        try {
            listening = true;
            updateButtons();
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            destroySystemRecognizer();
            if (continuousMode && continuous) {
                status.setText("启动语音识别失败：" + safe(e) + " · 将重试");
                scheduleContinuousNext(900L);
            } else {
                cancelTurn();
                status.setText("启动语音识别失败：" + safe(e));
            }
        }
    }

    private void enqueueContinuous(String text, String language, int session) {
        if (!continuousMode || generation != session || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        if (looksLikeRecentTtsEcho(cleaned)) {
            status.setText("持续监听 · 已忽略手机自身朗读回声");
            return;
        }
        while (pendingUtterances.size() >= MAX_PENDING_UTTERANCES) pendingUtterances.pollFirst();
        pendingUtterances.addLast(new ContinuousUtterance(cleaned, language == null ? "" : language));
        status.setText("持续监听 · 待处理 " + pendingUtterances.size() + " 句");
        drainContinuousQueue(session);
    }

    private void drainContinuousQueue(int session) {
        if (!continuousMode || destroyed || generation != session || translating) return;
        ContinuousUtterance item = pendingUtterances.pollFirst();
        if (item == null) {
            if (persistentOfflineCapture && listening) status.setText("持续监听中 · 可继续说，不要求双方轮流");
            return;
        }
        if (translator == null) rebuildTranslator();
        translating = true;
        updateButtons();
        translator.translateConversation(item.text, item.language, languageMode(),
            ((LanguageOption) fixedInputLanguage.getSelectedItem()).mlKitTag, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (destroyed || !continuousMode || generation != session) return;
                translating = false;
                String detected = translator == null ? "" : translator.lastDetectedLanguage();
                LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
                boolean mySpoke = !ConversationLanguagePolicy.FIXED.equals(languageMode())
                    && OfflineFirstTranslationRouter.sameLanguage(detected, my.mlKitTag);
                languageStatus.setText("识别原文：" + item.text + "\nASR："
                    + (item.language.isEmpty() ? "未提供标签" : item.language)
                    + " · 确认方向：" + detected + " → " + translator.lastTargetLanguage());
                boolean spokePartner = !mySpoke;
                if (mySpoke) {
                    myOriginal.setText(item.text);
                    myTranslated.setText(translated);
                } else {
                    partnerOriginal.setText(item.text);
                    partnerTranslated.setText(translated);
                }
                saveRecent(item.text, translated);
                appendConversation(mySpoke ? "我" : "对方", item.text, translated, detected);
                status.setText(engineName + " · 麦克风仍在持续监听");
                updateButtons();
                if (continuousSpeak.isChecked()) speakResult(translated, spokePartner, true);
                drainContinuousQueue(session);
            }

            @Override public void onError(String message) {
                if (destroyed || !continuousMode || generation != session) return;
                translating = false;
                languageStatus.setText("未翻译原文：" + item.text + "\nASR：" + item.language + "\n" + message);
                appendConversation("待确认", item.text, "未翻译：" + message, item.language);
                status.setText("翻译失败：" + message + " · 麦克风仍在监听");
                updateButtons();
                drainContinuousQueue(session);
            }
        });
    }

    private void translateManualTranscript(String original, boolean spokePartner, String detected, int session) {
        if (translator == null) rebuildTranslator();
        translating = true;
        updateButtons();
        if (spokePartner) partnerOriginal.setText(original); else myOriginal.setText(original);
        status.setText("正在翻译…");
        translator.translateTurn(original, spokePartner, detected, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                if (destroyed || generation != session) return;
                translating = false;
                updateButtons();
                if (spokePartner) partnerTranslated.setText(translated); else myTranslated.setText(translated);
                status.setText(engineName);
                saveRecent(original, translated);
                appendConversation(spokePartner ? "对方" : "我", original, translated,
                    translator == null ? "" : translator.lastDetectedLanguage());
                if (autoSpeak.isChecked()) speakResult(translated, spokePartner, false);
            }
            @Override public void onError(String message) {
                if (destroyed || generation != session) return;
                translating = false;
                updateButtons();
                status.setText("翻译失败：" + message);
            }
        });
    }

    private boolean speakResult(String value, boolean partnerSpoke, boolean continuous) {
        if (!ttsReady || tts == null || value == null || value.trim().isEmpty()) return false;
        LanguageOption my = (LanguageOption) myLanguage.getSelectedItem();
        LanguageOption fallback = (LanguageOption) partnerFallback.getSelectedItem();
        String lang = partnerSpoke ? my.mlKitTag
            : (translator == null ? fallback.mlKitTag : translator.partnerLanguage());
        try {
            int result = tts.setLanguage(Locale.forLanguageTag(lang));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                status.append(" · 当前设备缺少该语言朗读语音包");
                return false;
            }
            int queueMode = continuous ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
            String utteranceId = "face-translation-" + (++utteranceCounter);
            int speak = tts.speak(value, queueMode, null, utteranceId);
            if (speak == TextToSpeech.SUCCESS && continuous) rememberTtsEcho(value);
            return speak == TextToSpeech.SUCCESS;
        } catch (Exception e) {
            status.append(" · 朗读失败");
            return false;
        }
    }

    private void rememberTtsEcho(String value) {
        purgeOldEchoes();
        recentTtsEchoes.addLast(new EchoSample(compact(value), android.os.SystemClock.elapsedRealtime()));
        while (recentTtsEchoes.size() > 8) recentTtsEchoes.pollFirst();
    }

    private boolean looksLikeRecentTtsEcho(String value) {
        purgeOldEchoes();
        String candidate = compact(value);
        if (candidate.length() < 2) return false;
        for (EchoSample sample : recentTtsEchoes) {
            if (sample.text.equals(candidate)) return true;
            if (candidate.length() >= 4 && sample.text.length() >= 4
                && (sample.text.contains(candidate) || candidate.contains(sample.text))) return true;
        }
        return false;
    }

    private void purgeOldEchoes() {
        long now = android.os.SystemClock.elapsedRealtime();
        while (!recentTtsEchoes.isEmpty()
            && now - recentTtsEchoes.peekFirst().at > TTS_ECHO_WINDOW_MS) {
            recentTtsEchoes.pollFirst();
        }
    }

    private static String compact(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String languageMode() {
        return ConversationLanguagePolicy.MODES[Math.max(0, recognitionMode.getSelectedItemPosition())];
    }

    private void refreshLanguageMode() {
        boolean fixed = ConversationLanguagePolicy.FIXED.equals(languageMode());
        fixedInputLabel.setVisibility(fixed ? View.VISIBLE : View.GONE);
        fixedInputLanguage.setVisibility(fixed ? View.VISIBLE : View.GONE);
        modeHint.setText(fixed
            ? "输入语言同时用于录音识别和翻译。英语→中文：输入选英语，我的语言选中文。Qwen3/Omnilingual 当前接口不能强制输入语言，请改选 Whisper、SenseVoice、Vosk 或系统。"
            : ConversationLanguagePolicy.PAIR.equals(languageMode())
                ? "只接受双方设置的两种语言；其他语言提示待确认。离线模型仍自动识别，系统服务支持时会限制语言候选。"
                : "外语→我的语言；我的语言→本次最近确认的外语。首次回复使用对方语言。短句识别可能出错，可切换固定输入。系统自动切换能力取决于语音服务。");
    }

    private String selectConversationAsr(String input) {
        String selected = asrIds.get(asrSpinner.getSelectedItemPosition());
        String mine = ((LanguageOption) myLanguage.getSelectedItem()).mlKitTag;
        boolean fixed = ConversationLanguagePolicy.FIXED.equals(languageMode());
        if (!"auto".equals(selected)) {
            boolean compatible = fixed ? ConversationLanguagePolicy.supportsFixed(selected, input)
                : ConversationLanguagePolicy.supportsAutomatic(selected, mine, input);
            if (!compatible) return null;
            return selectAsr(input);
        }
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            String[] ids = fixed
                ? new String[]{TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_WHISPER_SMALL, TranslationService.ASR_SENSEVOICE}
                : new String[]{TranslationService.ASR_QWEN3, TranslationService.ASR_WHISPER_MEDIUM,
                    TranslationService.ASR_WHISPER_SMALL, TranslationService.ASR_SENSEVOICE, TranslationService.ASR_OMNILINGUAL};
            for (String id : ids) {
                boolean compatible = fixed ? ConversationLanguagePolicy.supportsFixed(id, input)
                    : ConversationLanguagePolicy.supportsAutomatic(id, mine, input);
                if (compatible && store.isInstalled(id)) return id;
            }
            if (fixed && OfflineSpeechEngine.isLanguageInstalled(this, input)) return "vosk";
            return SpeechRecognizer.isRecognitionAvailable(this) ? "system" : null;
        } finally { store.close(); }
    }

    private String selectAsr(String language) {
        String selected = asrIds.get(asrSpinner.getSelectedItemPosition());
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            if ("system".equals(selected)) return selected;
            if ("vosk".equals(selected)) return OfflineSpeechEngine.isLanguageInstalled(this, language) ? selected : null;
            if (!"auto".equals(selected)) return store.isInstalled(selected) ? selected : null;
            String[] candidates = {TranslationService.ASR_WHISPER_SMALL, TranslationService.ASR_QWEN3,
                TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_OMNILINGUAL,
                TranslationService.ASR_SENSEVOICE};
            for (String id : candidates) {
                if (TranslationService.ASR_SENSEVOICE.equals(id)
                    && !("zh".equals(language) || "en".equals(language) || "ja".equals(language)
                        || "ko".equals(language) || "yue".equals(language))) continue;
                if (store.isInstalled(id)) return id;
            }
            if (SpeechRecognizer.isRecognitionAvailable(this)) return "system";
            if (OfflineSpeechEngine.isLanguageInstalled(this, language)) return "vosk";
            return null;
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

    private void saveRecent(String original, String translated) {
        prefs.edit().putString("last_original", original)
            .putString("last_translation", translated).apply();
    }

    private void appendConversation(String who, String original, String translated, String language) {
        String lang = language == null || language.trim().isEmpty() ? "" : " [" + language + "]";
        conversationLines.add(who + lang + "：" + original + "\n→ " + translated);
        while (conversationLines.size() > 50) conversationLines.remove(0);
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
            .putString("face_language_mode", languageMode())
            .putInt("face_fixed_input_index", fixedInputLanguage.getSelectedItemPosition())
            .putBoolean("face_continuous_speak", continuousSpeak.isChecked())
            .apply();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MIC || grantResults.length == 0
            || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        if (pendingPermissionForContinuous) {
            pendingPermissionForContinuous = false;
            startContinuousMode();
        } else startManualTurn(partnerTurn);
    }

    private void updateButtons() {
        boolean idle = !listening && !translating;
        if (partnerButton != null) partnerButton.setEnabled(idle && !continuousMode);
        if (myButton != null) myButton.setEnabled(idle && !continuousMode);
        if (myLanguage != null) myLanguage.setEnabled(!continuousMode && !listening && !translating);
        if (partnerFallback != null) partnerFallback.setEnabled(!continuousMode && !listening && !translating);
        if (asrSpinner != null) asrSpinner.setEnabled(!continuousMode && !listening && !translating);
        if (recognitionMode != null) recognitionMode.setEnabled(!continuousMode && idle);
        if (fixedInputLanguage != null) fixedInputLanguage.setEnabled(!continuousMode && idle);
        if (continuousButton != null) {
            continuousButton.setEnabled(true);
            continuousButton.setText(continuousMode
                ? "■ 停止连续开放麦克风"
                : "▶ 开始连续开放麦克风｜无需轮流");
        }
    }

    private void destroySystemRecognizer() {
        recognitionGeneration++;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (Exception ignored) {}
            try { old.destroy(); } catch (Exception ignored) {}
        }
    }

    private void stopRecognition() {
        listening = false;
        persistentOfflineCapture = false;
        if (offlineSession != null) { offlineSession.close(); offlineSession = null; }
        destroySystemRecognizer();
    }

    private void cancelTurn() {
        continuousMode = false;
        pendingPermissionForContinuous = false;
        generation++;
        main.removeCallbacks(manualTimeout);
        main.removeCallbacks(continuousRestart);
        stopRecognition();
        translating = false;
        pendingUtterances.clear();
        recentTtsEchoes.clear();
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

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ContinuousUtterance {
        final String text;
        final String language;
        ContinuousUtterance(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    private static final class EchoSample {
        final String text;
        final long at;
        EchoSample(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }
}
