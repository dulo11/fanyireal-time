package com.zhou.floatingtranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Foreground translation pipeline dedicated to fixed ROOT/Shizuku ALSA/tinycap VoIP audio.
 * It deliberately does not modify audio_policy, SELinux or vendor audio files.
 */
public final class RootCallTranslationService extends Service {
    private static final String PREFS = "floating_translator";
    private static final int NOTIFICATION_ID = 3410;
    private static final String CHANNEL_ID = "root_call_translation";
    private static final int AUDIO_PRESENT_PEAK = 80;
    private static final long LEVEL_UPDATE_MS = 700L;
    private static final long STREAM_TRANSLATE_INTERVAL_MS = 1200L;
    private static final long CLOUD_CHUNK_MS = 3600L;
    private static final int SAMPLE_RATE = 16000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> failedAutoModels = new HashSet<>();
    private final ByteArrayOutputStream cloudPcm = new ByteArrayOutputStream();

    private RootPcmSource rootSource;
    private OfflineSpeechEngine vosk;
    private SherpaSpeechEngine sherpa;
    private FreeOnlineSpeechEngine onlineSpeech;
    private OfflineFirstTranslationRouter translator;

    private volatile boolean running;
    private volatile boolean paused;
    private boolean cloudSpeechMode;
    private boolean cloudRequestBusy;
    private boolean allowYoudaoSpeech;
    private String asrMode = TranslationService.ASR_AUTO;
    private String activeAsrMode = TranslationService.ASR_AUTO;
    private String languageMode = SherpaSpeechEngine.LANG_SINGLE;
    private String sourceMlTag = "en";
    private String targetMlTag = "zh";
    private String engineId = TranslationRouter.AUTO;
    private boolean showOriginal = true;
    private boolean showDiagnostics = true;
    private long lastLevelUiAt;
    private long lastStreamTranslateAt;
    private long cloudChunkStartedAt;
    private String lastStreamTranslated = "";
    private boolean translateBusy;
    private final SpeechQueue speechQueue = new SpeechQueue();

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView pauseControl;
    private TextView diag;
    private TextView original;
    private TextView translated;
    private volatile String diagAudio = "内部 PCM 未启动";
    private volatile String diagAsr = "ASR 未启动";
    private volatile String diagTranslation = "翻译未启动";

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "内部通话翻译", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (TranslationService.ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (TranslationService.ACTION_UI_VISIBLE.equals(action)) {
            if (running) setUiVisible(true); else stopSelf();
            return START_NOT_STICKY;
        }
        if (TranslationService.ACTION_UI_HIDDEN.equals(action)) {
            if (running) setUiVisible(false); else stopSelf();
            return START_NOT_STICKY;
        }
        if (!TranslationService.ACTION_START.equals(action)) return START_NOT_STICKY;

        stopPipeline();
        RootCallProfileStore.Profile profile = RootCallProfileStore.loadSelected(this);
        if (profile == null || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        asrMode = normalizeAsr(intent.getStringExtra(TranslationService.EXTRA_ASR_MODE));
        activeAsrMode = asrMode;
        languageMode = normalizeLanguageMode(intent.getStringExtra(TranslationService.EXTRA_LANGUAGE_MODE));
        sourceMlTag = value(intent.getStringExtra(TranslationService.EXTRA_SOURCE_MLKIT), "en");
        targetMlTag = value(intent.getStringExtra(TranslationService.EXTRA_TARGET_MLKIT), "zh");
        engineId = value(intent.getStringExtra(TranslationService.EXTRA_ENGINE_ID), TranslationRouter.AUTO);
        showOriginal = intent.getBooleanExtra(TranslationService.EXTRA_SHOW_ORIGINAL, true);
        showDiagnostics = intent.getBooleanExtra(TranslationService.EXTRA_SHOW_DIAGNOSTICS, true);
        allowYoudaoSpeech = intent.getBooleanExtra(TranslationService.EXTRA_YOUDAO_SPEECH_FALLBACK, false);
        int fontSize = intent.getIntExtra(TranslationService.EXTRA_FONT_SIZE, 24);

        startForeground(NOTIFICATION_ID, notification("准备 " + profile.sourceLabel() + " 通话翻译"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        running = true;
        paused = false;
        translateBusy = false;
        speechQueue.clear();
        cloudSpeechMode = false;
        cloudRequestBusy = false;
        failedAutoModels.clear();
        synchronized (cloudPcm) { cloudPcm.reset(); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", true).apply();

        createOverlay(fontSize);
        translator = new OfflineFirstTranslationRouter(this, sourceMlTag, targetMlTag, engineId);
        diagTranslation = translator.selectedEngineName();
        diagAudio = profile.sourceLabel() + " · " + profile.packageName + " · 等待 PCM";
        diagAsr = "ASR：" + asrLabel(asrMode) + " · 准备中";
        renderDiag();
        showStatus(profile.sourceLabel() + " 通话来源：" + profile.packageName + "\n正在准备 " + asrLabel(asrMode));
        prepareAsr(profile);
        return START_NOT_STICKY;
    }

    private void prepareAsr(RootCallProfileStore.Profile profile) {
        if (!running) return;
        if (TranslationService.ASR_SYSTEM.equals(asrMode)) {
            diagAsr = "ASR：系统 SpeechRecognizer 不能直接接内部 PCM";
            renderDiag();
            showStatus("ROOT/Shizuku 内部 PCM 不能使用系统 SpeechRecognizer，请选 Vosk / sherpa / 有道 ASR。");
            return;
        }
        if (TranslationService.ASR_FREE_ONLINE.equals(asrMode)
            || TranslationService.ASR_GROQ_LARGE.equals(asrMode)
            || TranslationService.ASR_GROQ_TURBO.equals(asrMode)
            || TranslationService.ASR_CLOUDFLARE.equals(asrMode)) {
            startFreeOnline(asrMode, profile, false);
            return;
        }
        if (TranslationService.ASR_YOUDAO.equals(asrMode)) {
            if (!canUseYoudaoSpeech()) {
                showStatus("有道云 ASR 未配置，请到 API 安全中心填写 AppKey/AppSecret");
                return;
            }
            activeAsrMode = TranslationService.ASR_YOUDAO;
            cloudSpeechMode = true;
            diagAsr = "ASR：有道云语音 · 内部 PCM";
            startRootSource(profile);
            return;
        }
        if (TranslationService.ASR_AUTO.equals(asrMode)) {
            if (FreeOnlineSpeechEngine.hasAnyConfigured(this)) {
                startFreeOnline(TranslationService.ASR_FREE_ONLINE, profile, true);
                return;
            }
            prepareAutoLocal(profile);
            return;
        }
        if (TranslationService.ASR_VOSK.equals(asrMode)) {
            startVosk(profile, false);
            return;
        }
        if (OfflineAsrModelCatalog.find(asrMode) != null) {
            startSherpa(asrMode, profile, false);
            return;
        }
        startVosk(profile, false);
    }

    private void prepareAutoLocal(RootCallProfileStore.Profile profile) {
        String selected = bestInstalledSherpa();
        if (selected != null) startSherpa(selected, profile, true);
        else startVosk(profile, true);
    }

    private void startFreeOnline(String mode, RootCallProfileStore.Profile profile, boolean autoFallback) {
        closeOnline();
        closeOnline();
        closeVosk();
        closeSherpa();
        diagAsr = "ASR：正在连接 " + asrLabel(mode);
        renderDiag();
        onlineSpeech = new FreeOnlineSpeechEngine(this, mode, languageMode, sourceMlTag,
            new FreeOnlineSpeechEngine.Callback() {
                @Override public void onStatus(String message) {
                    diagAsr = "ASR：" + message;
                    renderDiag();
                    updateNotification(message);
                }
                @Override public void onReady(String engineName) {
                    if (!running) return;
                    activeAsrMode = mode;
                    diagAsr = "ASR：" + engineName + " · 内部 PCM";
                    renderDiag();
                    startRootSource(profile);
                }
                @Override public void onText(String text, String detectedLanguage, String engineName) {
                    if (!running || paused || text == null || text.trim().isEmpty()) return;
                    String cleaned = text.trim();
                    diagAsr = "ASR：" + engineName + " · " + preview(cleaned);
                    renderDiag();
                    if (showOriginal && original != null) original.setText("原文：" + cleaned);
                    queueTranslate(cleaned, true, detectedLanguage);
                }
                @Override public void onError(String message, boolean fatal) {
                    diagAsr = "ASR：免费在线失败 · " + message;
                    renderDiag();
                    if (fatal && autoFallback && TranslationService.ASR_AUTO.equals(asrMode)) {
                        closeOnline();
                        showStatus("免费在线 ASR 暂不可用，自动切回本地");
                        prepareAutoLocal(profile);
                    } else if (fatal) showStatus("免费在线 ASR 失败：" + message);
                }
            });
        onlineSpeech.prepare();
    }

    private String bestInstalledSherpa() {
        OfflineModelStore store = new OfflineModelStore(this);
        try {
            String[] order;
            boolean mixed = !SherpaSpeechEngine.LANG_SINGLE.equals(languageMode);
            if (mixed) order = new String[]{TranslationService.ASR_QWEN3, TranslationService.ASR_WHISPER_SMALL,
                TranslationService.ASR_SENSEVOICE, TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_OMNILINGUAL};
            else if ("ja".equals(sourceMlTag)) order = new String[]{TranslationService.ASR_REAZON,
                TranslationService.ASR_PARAKEET, TranslationService.ASR_SENSEVOICE, TranslationService.ASR_WHISPER_SMALL,
                TranslationService.ASR_QWEN3, TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_OMNILINGUAL};
            else order = new String[]{TranslationService.ASR_SENSEVOICE, TranslationService.ASR_WHISPER_SMALL,
                TranslationService.ASR_QWEN3, TranslationService.ASR_WHISPER_MEDIUM, TranslationService.ASR_OMNILINGUAL};
            for (String id : order) if (!failedAutoModels.contains(id) && store.isInstalled(id)) return id;
            return null;
        } finally {
            store.close();
        }
    }

    private void startSherpa(String modelId, RootCallProfileStore.Profile profile, boolean autoFallback) {
        OfflineAsrModelCatalog.Model meta = OfflineAsrModelCatalog.find(modelId);
        if (meta == null) return;
        closeVosk();
        closeSherpa();
        diagAsr = "ASR：正在加载 " + meta.name;
        renderDiag();
        sherpa = new SherpaSpeechEngine(this, modelId, languageMode, sourceMlTag,
            new SherpaSpeechEngine.Callback() {
                @Override public void onStatus(String message) {
                    diagAsr = "ASR：" + message;
                    renderDiag();
                    updateNotification(message);
                }
                @Override public void onReady(String engineName) {
                    if (!running) return;
                    activeAsrMode = modelId;
                    diagAsr = "ASR：" + engineName + " · 内部 PCM";
                    renderDiag();
                    startRootSource(profile);
                }
                @Override public void onText(String text, String detectedLanguage) {
                    if (!running || paused || text == null || text.trim().isEmpty()) return;
                    String cleaned = text.trim();
                    diagAsr = "ASR：" + meta.name + " · " + preview(cleaned);
                    renderDiag();
                    if (showOriginal && original != null) original.setText("原文：" + cleaned);
                    queueTranslate(cleaned, true, detectedLanguage);
                }
                @Override public void onError(String message) {
                    diagAsr = "ASR：" + meta.name + " 失败 · " + message;
                    renderDiag();
                    if (autoFallback && TranslationService.ASR_AUTO.equals(asrMode)) {
                        failedAutoModels.add(modelId);
                        closeSherpa();
                        String next = bestInstalledSherpa();
                        if (next != null) startSherpa(next, profile, true); else startVosk(profile, true);
                    } else showStatus(meta.name + " 失败：" + message);
                }
            });
        sherpa.prepare();
    }

    private void startVosk(RootCallProfileStore.Profile profile, boolean autoFallback) {
        closeSherpa();
        closeVosk();
        if (!OfflineSpeechEngine.supports(sourceMlTag)) {
            if (autoFallback && canUseYoudaoSpeech()) {
                activeAsrMode = TranslationService.ASR_YOUDAO;
                cloudSpeechMode = true;
                startRootSource(profile);
            } else showStatus("Vosk 不支持当前语言，请下载高精度模型或配置有道 ASR");
            return;
        }
        diagAsr = "ASR：Vosk 模型准备中";
        renderDiag();
        vosk = new OfflineSpeechEngine(this, sourceMlTag, new OfflineSpeechEngine.Callback() {
            @Override public void onStatus(String message) {
                diagAsr = "ASR：" + message;
                renderDiag();
            }
            @Override public void onReady(String engineName) {
                if (!running) return;
                activeAsrMode = TranslationService.ASR_VOSK;
                diagAsr = "ASR：" + engineName + " · 内部 PCM";
                renderDiag();
                startRootSource(profile);
            }
            @Override public void onPartial(String text) { onVoskText(text, false); }
            @Override public void onFinal(String text) { onVoskText(text, true); }
            @Override public void onError(String message) {
                diagAsr = "ASR：Vosk 失败 · " + message;
                renderDiag();
                if (autoFallback && canUseYoudaoSpeech()) {
                    closeVosk();
                    activeAsrMode = TranslationService.ASR_YOUDAO;
                    cloudSpeechMode = true;
                    startRootSource(profile);
                } else showStatus("Vosk 失败：" + message);
            }
        });
        vosk.prepare();
    }

    private void startRootSource(RootCallProfileStore.Profile profile) {
        if (!running || rootSource != null) return;
        rootSource = new RootPcmSource(this, profile.transport, profile.card, profile.device, profile.rate, profile.channels,
            new RootPcmSource.Callback() {
                @Override public void onStatus(String message) {
                    diagAudio = message + " · " + profile.packageName;
                    renderDiag();
                    updateNotification(message);
                }
                @Override public void onPcm(byte[] pcm, int length, int peak) {
                    if (!running || paused) return;
                    consumePcm(pcm, length, peak);
                }
                @Override public void onError(String message) {
                    diagAudio = profile.sourceLabel() + " PCM 失败：" + message;
                    renderDiag();
                    showStatus(profile.sourceLabel() + " 内部声音捕获失败：" + message
                        + "\n回通话兼容中心调整同一固定模式的 PCM/采样率/声道后再试；不会自动切换来源。");
                }
            });
        rootSource.start();
    }

    private void consumePcm(byte[] pcm, int length, int peak) {
        long now = SystemClock.elapsedRealtime();
        updateLevel(peak, now);
        FreeOnlineSpeechEngine online = onlineSpeech;
        OfflineSpeechEngine currentVosk = vosk;
        SherpaSpeechEngine currentSherpa = sherpa;
        if (online != null && online.isReady()) online.acceptPcm(pcm, length, peak);
        else if (currentVosk != null && currentVosk.isReady()) currentVosk.acceptPcm(pcm, length);
        else if (currentSherpa != null && currentSherpa.isReady()) currentSherpa.acceptPcm(pcm, length, peak);
        else if (cloudSpeechMode) appendCloudAudio(pcm, length, peak, now);
    }

    private void onVoskText(String text, boolean committed) {
        if (!running || paused || text == null) return;
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        diagAsr = "ASR：Vosk " + (committed ? "最终" : "流式") + " · " + preview(cleaned);
        renderDiag();
        if (showOriginal && original != null) original.setText("原文：" + cleaned);
        if (committed) {
            lastStreamTranslated = "";
            queueTranslate(cleaned, true);
        } else if (cleaned.length() >= 3 && !cleaned.equals(lastStreamTranslated)
            && now - lastStreamTranslateAt >= STREAM_TRANSLATE_INTERVAL_MS) {
            lastStreamTranslateAt = now;
            lastStreamTranslated = cleaned;
            queueTranslate(cleaned, false);
        }
    }

    private void queueTranslate(String text, boolean finalResult) {
        queueTranslate(text, finalResult, "");
    }

    private void queueTranslate(String text, boolean finalResult, String language) {
        if (!running || text == null || text.trim().isEmpty()) return;
        speechQueue.offer(text.trim(), finalResult, language);
        drainTranslateQueue();
    }

    private void drainTranslateQueue() {
        if (!running || translateBusy) return;
        SpeechQueue.Item next = speechQueue.poll();
        if (next == null) return;
        String cleaned = next.text;
        OfflineFirstTranslationRouter current = translator;
        if (current == null) return;
        translateBusy = true;
        diagTranslation = "翻译中";
        renderDiag();
        current.translate(cleaned, next.language, new OfflineFirstTranslationRouter.Callback() {
            @Override public void onSuccess(String out, String engineName) {
                if (!running || translator != current) return;
                diagTranslation = engineName;
                renderDiag();
                if (translated != null) translated.setText("译文：" + out);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_original", cleaned).putString("last_translation", out).apply();
                finishTranslate();
            }
            @Override public void onError(String message) {
                if (!running || translator != current) return;
                diagTranslation = "翻译失败：" + message;
                renderDiag();
                if (translated != null) translated.setText("翻译失败：" + message);
                finishTranslate();
            }
        });
    }

    private void finishTranslate() {
        translateBusy = false;
        drainTranslateQueue();
    }

    private void appendCloudAudio(byte[] bytes, int length, int peak, long now) {
        if (peak < AUDIO_PRESENT_PEAK && cloudPcm.size() == 0) return;
        synchronized (cloudPcm) {
            if (cloudPcm.size() == 0) cloudChunkStartedAt = now;
            cloudPcm.write(bytes, 0, length);
            if (now - cloudChunkStartedAt < CLOUD_CHUNK_MS || cloudRequestBusy) return;
            byte[] chunk = cloudPcm.toByteArray();
            cloudPcm.reset();
            cloudChunkStartedAt = now;
            if (chunk.length < SAMPLE_RATE * 2) return;
            sendCloudSpeech(chunk);
        }
    }

    private void sendCloudSpeech(byte[] pcm) {
        OfflineFirstTranslationRouter current = translator;
        if (current == null || !canUseYoudaoSpeech()) return;
        cloudRequestBusy = true;
        diagAsr = "ASR：有道云识别中……";
        renderDiag();
        current.translateYoudaoSpeech(pcm, new TranslationRouter.SpeechCallback() {
            @Override public void onSuccess(String originalText, String ignoredTranslation, String engineName) {
                cloudRequestBusy = false;
                String text = originalText == null ? "" : originalText.trim();
                if (text.isEmpty()) return;
                diagAsr = "ASR：有道云 · " + preview(text);
                renderDiag();
                if (showOriginal && original != null) original.setText("原文：" + text);
                queueTranslate(text, true);
            }
            @Override public void onError(String message) {
                cloudRequestBusy = false;
                diagAsr = "ASR：有道云失败 · " + message;
                renderDiag();
            }
        });
    }

    private boolean canUseYoudaoSpeech() {
        return allowYoudaoSpeech && translator != null && translator.hasYoudaoCredentials();
    }

    private void updateLevel(int peak, long now) {
        if (now - lastLevelUiAt < LEVEL_UPDATE_MS) return;
        lastLevelUiAt = now;
        String level;
        if (peak < AUDIO_PRESENT_PEAK) level = "▁ 近乎静音";
        else if (peak < 500) level = "▂ 有信号";
        else if (peak < 2500) level = "▃▅ 有声音";
        else if (peak < 9000) level = "▃▅▇ 正常";
        else level = "▃▅▇█ 较强";
        diagAudio = "ROOT PCM：" + level;
        renderDiag();
    }

    private void createOverlay(int fontSize) {
        removeOverlay();
        windowManager = getSystemService(WindowManager.class);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(10));
        box.setBackgroundResource(R.drawable.panel);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        pauseControl = control("暂停");
        TextView close = control("关闭");
        pauseControl.setOnClickListener(v -> togglePause());
        close.setOnClickListener(v -> stopEverything());
        controls.addView(pauseControl, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        diag = overlayText(11, Color.rgb(200, 180, 255));
        diag.setGravity(Gravity.START);
        diag.setVisibility(showDiagnostics ? View.VISIBLE : View.GONE);
        box.addView(diag, new LinearLayout.LayoutParams(-1, -2));

        original = overlayText(Math.max(13, fontSize - 5), Color.rgb(220, 215, 235));
        original.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        translated = overlayText(fontSize, Color.WHITE);
        translated.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(original, new LinearLayout.LayoutParams(-1, -2));
        box.addView(translated, new LinearLayout.LayoutParams(-1, -2));

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        overlayParams = new WindowManager.LayoutParams(
            getResources().getDisplayMetrics().widthPixels - dp(24), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayParams.y = dp(110);
        overlay = box;
        makeDraggable(box);
        windowManager.addView(overlay, overlayParams);
        renderDiag();
    }

    private TextView overlayText(int size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(6);
        t.setText(" ");
        return t;
    }

    private TextView control(String value) {
        TextView t = overlayText(13, Color.rgb(205, 185, 255));
        t.setText(value);
        t.setPadding(dp(8), dp(3), dp(8), dp(5));
        return t;
    }

    private void makeDraggable(View view) {
        final float[] start = new float[2];
        final int[] originalY = new int[1];
        view.setOnTouchListener((v, event) -> {
            if (overlayParams == null || windowManager == null || overlay == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX(); start[1] = event.getRawY(); originalY[0] = overlayParams.y; return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.y = Math.max(0, originalY[0] + Math.round(start[1] - event.getRawY()));
                    try { windowManager.updateViewLayout(overlay, overlayParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP: return true;
                default: return false;
            }
        });
    }

    private void togglePause() {
        paused = !paused;
        if (pauseControl != null) pauseControl.setText(paused ? "继续" : "暂停");
        if (paused) {
            FreeOnlineSpeechEngine online = onlineSpeech;
            if (online != null) online.flush();
            SherpaSpeechEngine s = sherpa;
            if (s != null) s.flush();
            showStatus("ROOT 通话翻译已暂停");
        } else showStatus("ROOT 通话翻译已继续");
    }

    private void setUiVisible(boolean visible) {
        main.post(() -> { if (overlay != null) overlay.setVisibility(visible ? View.GONE : View.VISIBLE); });
    }

    private void renderDiag() {
        main.post(() -> {
            if (diag == null) return;
            diag.setVisibility(showDiagnostics ? View.VISIBLE : View.GONE);
            if (showDiagnostics) diag.setText("声音：" + diagAudio + "\n" + diagAsr + "\n翻译：" + diagTranslation);
        });
    }

    private void showStatus(String message) {
        main.post(() -> { if (translated != null) translated.setText(message); });
    }

    private Notification notification(String message) {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
            new Intent(this, RootCallActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("浮译 " + BuildConfig.VERSION_NAME + " · ROOT 通话")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    private String normalizeAsr(String value) {
        if (TranslationService.ASR_VOSK.equals(value) || TranslationService.ASR_SYSTEM.equals(value)
            || TranslationService.ASR_YOUDAO.equals(value)
            || TranslationService.ASR_FREE_ONLINE.equals(value)
            || TranslationService.ASR_GROQ_LARGE.equals(value)
            || TranslationService.ASR_GROQ_TURBO.equals(value)
            || TranslationService.ASR_CLOUDFLARE.equals(value)
            || OfflineAsrModelCatalog.find(value) != null) return value;
        return TranslationService.ASR_AUTO;
    }

    private String normalizeLanguageMode(String value) {
        if (SherpaSpeechEngine.LANG_JA_EN.equals(value) || SherpaSpeechEngine.LANG_ZH_EN.equals(value)
            || SherpaSpeechEngine.LANG_KO_EN.equals(value) || SherpaSpeechEngine.LANG_AUTO.equals(value)) return value;
        return SherpaSpeechEngine.LANG_SINGLE;
    }

    private String asrLabel(String value) {
        if (TranslationService.ASR_VOSK.equals(value)) return "Vosk";
        if (TranslationService.ASR_SYSTEM.equals(value)) return "系统 SpeechRecognizer";
        if (TranslationService.ASR_YOUDAO.equals(value)) return "有道云 ASR";
        if (TranslationService.ASR_FREE_ONLINE.equals(value)) return "免费在线自动 ASR";
        if (TranslationService.ASR_GROQ_LARGE.equals(value)) return "Groq Free · Whisper Large V3";
        if (TranslationService.ASR_GROQ_TURBO.equals(value)) return "Groq Free · Whisper Turbo";
        if (TranslationService.ASR_CLOUDFLARE.equals(value)) return "Cloudflare Workers AI Free";
        OfflineAsrModelCatalog.Model m = OfflineAsrModelCatalog.find(value);
        return m == null ? "自动推荐 ASR" : m.name;
    }

    private void closeOnline() {
        FreeOnlineSpeechEngine s = onlineSpeech;
        onlineSpeech = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void closeVosk() {
        OfflineSpeechEngine v = vosk;
        vosk = null;
        if (v != null) try { v.close(); } catch (Exception ignored) {}
    }

    private void closeSherpa() {
        SherpaSpeechEngine s = sherpa;
        sherpa = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    private void stopPipeline() {
        running = false;
        RootPcmSource r = rootSource;
        rootSource = null;
        if (r != null) try { r.close(); } catch (Exception ignored) {}
        closeVosk();
        closeSherpa();
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
            translator = null;
        }
        synchronized (cloudPcm) { cloudPcm.reset(); }
        cloudSpeechMode = false;
        cloudRequestBusy = false;
        translateBusy = false;
        speechQueue.clear();
    }

    private void stopEverything() {
        stopPipeline();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        removeOverlay();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        overlayParams = null;
        pauseControl = null;
        diag = null;
        original = null;
        translated = null;
    }

    @Override public void onDestroy() {
        stopPipeline();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("service_running", false).apply();
        removeOverlay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private String preview(String value) {
        String s = value.replace('\n', ' ').trim();
        return s.length() > 38 ? s.substring(0, 38) + "…" : s;
    }

    private String value(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
