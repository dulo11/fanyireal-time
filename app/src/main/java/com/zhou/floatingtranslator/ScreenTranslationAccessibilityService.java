package com.zhou.floatingtranslator;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Global accessibility translation without MediaProjection.
 *
 * Normal path: read visible AccessibilityNodeInfo text from websites, Android UI and third-party
 * apps, then draw translations at the same screen coordinates. If no useful accessibility text is
 * exposed, AccessibilityService.takeScreenshot() + ML Kit OCR is used as a fallback. The service
 * also supports reverse translation of the currently focused editable field for chat apps.
 */
public final class ScreenTranslationAccessibilityService extends AccessibilityService {
    public static final String PREF_SCREEN_CONTINUOUS = "screen_translate_continuous";
    public static final String PREF_SCREEN_OCR_FALLBACK = "screen_translate_ocr_fallback";
    public static final String PREF_SCREEN_SMART_OCR = "screen_translate_smart_ocr";
    public static final String PREF_SCREEN_SKIP_TARGET = "screen_translate_skip_target";
    public static final String PREF_SCREEN_INCREMENTAL_CACHE = "screen_translate_incremental_cache";
    public static final String PREF_SCREEN_INPUT_REVERSE = "screen_translate_input_reverse";
    public static final String PREF_SCREEN_LAST_STATUS = "screen_translate_last_status";

    private static final String PREFS = "floating_translator";
    private static final int MAX_NODE_BLOCKS = 80;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final long EVENT_DEBOUNCE_MS = 650L;
    private static final long MIN_AUTO_SCAN_GAP_MS = 950L;
    private static final long DOUBLE_TAP_MS = 330L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, CachedTranslation> translationCache =
        new LinkedHashMap<String, CachedTranslation>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CachedTranslation> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };

    private SharedPreferences prefs;
    private ScreenTranslationClient translator;
    private WindowManager windowManager;
    private ScreenTranslationOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;

    private boolean translating;
    private boolean screenshotBusy;
    private long lastAutoScanAt;
    private long lastTapAt;
    private String lastWindowPackage = "";
    private String lastScreenSignature = "";
    private Runnable pendingSingleTap;
    private final Runnable eventScan = () -> translateCurrentScreen(false);

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        translator = new ScreenTranslationClient(this);
        windowManager = getSystemService(WindowManager.class);
        addOverlayWindow();
        addBubbleWindow();
        updateBubbleLabel();
        saveStatus("全局无障碍翻译已启动");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || prefs == null) return;
        CharSequence packageName = event.getPackageName();
        String pkg = packageName == null ? "" : packageName.toString();
        if (getPackageName().equals(pkg)) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!TextUtils.equals(lastWindowPackage, pkg)) {
                lastWindowPackage = pkg;
                lastScreenSignature = "";
                clearOverlay();
            }
        }

        boolean continuous = prefs.getBoolean(PREF_SCREEN_CONTINUOUS, false);
        if (!continuous) return;
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            && type != AccessibilityEvent.TYPE_VIEW_SCROLLED
            && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return;
        }

        // Typing into a chat input box should not cause the whole screen to retranslate on every key.
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && source.isEditable()) return;
        }

        main.removeCallbacks(eventScan);
        main.postDelayed(eventScan, EVENT_DEBOUNCE_MS);
    }

    @Override public void onInterrupt() {
        saveStatus("无障碍服务被中断");
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        removeWindows();
        translationCache.clear();
        if (translator != null) translator.close();
        translator = null;
        super.onDestroy();
    }

    private void translateCurrentScreen(boolean userInitiated) {
        if (prefs == null || translator == null || translating || screenshotBusy) return;
        if (!userInitiated) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastAutoScanAt < MIN_AUTO_SCAN_GAP_MS) return;
            lastAutoScanAt = now;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<Block> blocks = new ArrayList<>();
        if (root != null) {
            try {
                collectTextNodes(root, blocks, new HashSet<>(), 0);
            } catch (Exception ignored) {
            }
        }

        if (!blocks.isEmpty()) {
            boolean smartOcr = prefs.getBoolean(PREF_SCREEN_SMART_OCR, false);
            if (smartOcr && blocks.size() <= 1
                && prefs.getBoolean(PREF_SCREEN_OCR_FALLBACK, true)) {
                takeAccessibilityScreenshot();
                return;
            }

            String signature = screenSignature(blocks, targetLanguage());
            if (!userInitiated && signature.equals(lastScreenSignature)) return;
            lastScreenSignature = signature;
            translateBlocks(blocks, false);
            return;
        }

        lastScreenSignature = "";
        if (prefs.getBoolean(PREF_SCREEN_OCR_FALLBACK, true)) {
            takeAccessibilityScreenshot();
        } else if (userInitiated) {
            toast("当前页面没有可读取的无障碍文字；可开启 OCR 兜底");
        }
    }

    private void collectTextNodes(AccessibilityNodeInfo node, List<Block> out,
                                  Set<String> dedupe, int depth) {
        if (node == null || out.size() >= MAX_NODE_BLOCKS || depth > 34) return;
        if (!node.isVisibleToUser()) return;

        CharSequence packageName = node.getPackageName();
        if ((packageName == null || !getPackageName().contentEquals(packageName)) && !node.isEditable()) {
            CharSequence raw = node.getText();
            if (raw == null || raw.toString().trim().isEmpty()) raw = node.getContentDescription();
            String text = normalize(raw == null ? "" : raw.toString());
            if (isTranslatableText(text)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (isUsefulBounds(bounds)) {
                    String key = text + "@" + bounds.flattenToString();
                    if (dedupe.add(key)) out.add(new Block(text, bounds));
                }
            }
        }

        for (int i = 0; i < node.getChildCount() && out.size() < MAX_NODE_BLOCKS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTextNodes(child, out, dedupe, depth + 1);
        }
    }

    private void translateBlocks(List<Block> blocks, boolean fromOcr) {
        if (blocks.isEmpty()) return;
        translating = true;
        setBubbleText("…");
        clearOverlay();

        String target = targetLanguage();
        boolean cacheEnabled = prefs.getBoolean(PREF_SCREEN_INCREMENTAL_CACHE, true);
        LinkedHashMap<String, CachedTranslation> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Block block : blocks) {
            if (!seen.add(block.text)) continue;
            CachedTranslation cached = cacheEnabled ? translationCache.get(cacheKey(target, block.text)) : null;
            if (cached != null) resolved.put(block.text, cached);
            else missing.add(block.text);
        }

        if (missing.isEmpty()) {
            translating = false;
            updateBubbleLabel();
            applyTranslations(blocks, resolved, fromOcr, "缓存");
            return;
        }

        translator.translate(missing, target, new ScreenTranslationClient.Callback() {
            @Override public void onSuccess(List<ScreenTranslationClient.Result> results, String engineName) {
                translating = false;
                updateBubbleLabel();
                int count = Math.min(missing.size(), results.size());
                for (int i = 0; i < count; i++) {
                    ScreenTranslationClient.Result result = results.get(i);
                    CachedTranslation cached = new CachedTranslation(result.sourceLanguage, normalize(result.translated));
                    resolved.put(missing.get(i), cached);
                    if (cacheEnabled) translationCache.put(cacheKey(target, missing.get(i)), cached);
                }
                applyTranslations(blocks, resolved, fromOcr, engineName);
            }

            @Override public void onError(String message) {
                translating = false;
                updateBubbleLabel();
                saveStatus("翻译失败：" + message);
                toast("全局翻译失败：" + message);
            }
        });
    }

    private void applyTranslations(List<Block> blocks, Map<String, CachedTranslation> resolved,
                                   boolean fromOcr, String engineName) {
        boolean skipTarget = prefs.getBoolean(PREF_SCREEN_SKIP_TARGET, true);
        String target = targetLanguage();
        List<ScreenTranslationOverlayView.Entry> entries = new ArrayList<>();
        for (Block block : blocks) {
            CachedTranslation result = resolved.get(block.text);
            if (result == null) continue;
            if (skipTarget && sameLanguage(result.sourceLanguage, target)) continue;
            String translated = normalize(result.translated);
            if (translated.isEmpty()) continue;
            if (translated.equals(block.text) && skipTarget) continue;
            entries.add(new ScreenTranslationOverlayView.Entry(block.bounds, block.text, translated));
        }
        if (overlayView != null) overlayView.setEntries(entries);
        String path = fromOcr ? "无障碍截图 OCR" : "全局无障碍文字";
        saveStatus(path + " · " + engineName + " · " + entries.size() + " 段");
    }

    private void translateFocusedInput() {
        if (prefs == null || translator == null || translating || screenshotBusy) return;
        if (!prefs.getBoolean(PREF_SCREEN_INPUT_REVERSE, true)) {
            toast("发送前反向翻译已关闭");
            return;
        }

        AccessibilityNodeInfo input = findFocusedEditableNode();
        if (input == null) {
            toast("没有找到正在输入的文字框");
            return;
        }
        String original = normalize(input.getText() == null ? "" : input.getText().toString());
        if (!isTranslatableText(original)) {
            toast("输入框里没有可翻译文字");
            return;
        }

        String outgoingLanguage = sourceLanguageForOutgoing();
        translating = true;
        setBubbleText("输");
        translator.translate(Collections.singletonList(original), outgoingLanguage,
            new ScreenTranslationClient.Callback() {
                @Override public void onSuccess(List<ScreenTranslationClient.Result> results, String engineName) {
                    translating = false;
                    updateBubbleLabel();
                    if (results.isEmpty()) {
                        toast("输入框翻译没有返回结果");
                        return;
                    }
                    String translated = normalize(results.get(0).translated);
                    if (translated.isEmpty()) {
                        toast("输入框翻译结果为空");
                        return;
                    }
                    AccessibilityNodeInfo current = findFocusedEditableNode();
                    if (current == null) {
                        toast("输入框已失去焦点");
                        return;
                    }
                    String now = normalize(current.getText() == null ? "" : current.getText().toString());
                    if (!now.equals(original)) {
                        toast("输入内容已变化，未自动替换");
                        return;
                    }
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, translated);
                    boolean ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    if (ok) {
                        saveStatus("发送前翻译 · " + engineName + " · 已替换输入框");
                        toast("已翻译为 " + sourceLanguageLabel() + "，发送前可检查");
                    } else {
                        toast("这个 App 的输入框不允许无障碍替换文字");
                    }
                }

                @Override public void onError(String message) {
                    translating = false;
                    updateBubbleLabel();
                    toast("输入框翻译失败：" + message);
                }
            });
    }

    private AccessibilityNodeInfo findFocusedEditableNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) return focused;
            return findFocusedEditableRecursive(root, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private AccessibilityNodeInfo findFocusedEditableRecursive(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 32) return null;
        if (node.isVisibleToUser() && node.isEditable() && node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findFocusedEditableRecursive(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private void takeAccessibilityScreenshot() {
        if (screenshotBusy || translating) return;
        screenshotBusy = true;
        clearOverlay();
        setBubbleTemporarilyHidden(true);

        main.postDelayed(() -> takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult screenshot) {
                    Bitmap bitmap = null;
                    HardwareBuffer buffer = null;
                    try {
                        buffer = screenshot.getHardwareBuffer();
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                        if (hardware == null) throw new IllegalStateException("截图位图创建失败");
                        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (bitmap == null) throw new IllegalStateException("截图位图复制失败");
                    } catch (Exception e) {
                        screenshotBusy = false;
                        setBubbleTemporarilyHidden(false);
                        toast("无障碍截图失败：" + safe(e));
                        if (buffer != null) try { buffer.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (buffer != null) try { buffer.close(); } catch (Exception ignored) {}
                    setBubbleTemporarilyHidden(false);
                    runOcr(bitmap);
                }

                @Override public void onFailure(int errorCode) {
                    screenshotBusy = false;
                    setBubbleTemporarilyHidden(false);
                    saveStatus("无障碍截图失败，错误码 " + errorCode);
                    toast("当前页面无法截图，错误码 " + errorCode);
                }
            }), 90L);
    }

    private void runOcr(Bitmap bitmap) {
        TextRecognizer recognizer = createRecognizer(sourceLanguageForOcr());
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
            .addOnSuccessListener(result -> {
                List<Block> blocks = new ArrayList<>();
                Set<String> dedupe = new HashSet<>();
                for (Text.TextBlock textBlock : result.getTextBlocks()) {
                    for (Text.Line line : textBlock.getLines()) {
                        String value = normalize(line.getText());
                        Rect bounds = line.getBoundingBox();
                        if (!isTranslatableText(value) || !isUsefulBounds(bounds)) continue;
                        String key = value + "@" + bounds.flattenToString();
                        if (dedupe.add(key)) blocks.add(new Block(value, bounds));
                        if (blocks.size() >= MAX_NODE_BLOCKS) break;
                    }
                    if (blocks.size() >= MAX_NODE_BLOCKS) break;
                }
                screenshotBusy = false;
                updateBubbleLabel();
                if (blocks.isEmpty()) {
                    saveStatus("OCR 未识别到可翻译文字");
                    toast("OCR 没有识别到文字");
                } else {
                    lastScreenSignature = screenSignature(blocks, targetLanguage());
                    translateBlocks(blocks, true);
                }
            })
            .addOnFailureListener(e -> {
                screenshotBusy = false;
                updateBubbleLabel();
                saveStatus("OCR 失败：" + safe(e));
                toast("OCR 失败：" + safe(e));
            })
            .addOnCompleteListener(task -> {
                try { recognizer.close(); } catch (Exception ignored) {}
                try { bitmap.recycle(); } catch (Exception ignored) {}
            });
    }

    private TextRecognizer createRecognizer(String sourceMlTag) {
        if ("zh".equals(sourceMlTag)) {
            return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        if ("ja".equals(sourceMlTag)) {
            return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        }
        if ("ko".equals(sourceMlTag)) {
            return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        }
        if ("hi".equals(sourceMlTag) || "mr".equals(sourceMlTag)) {
            return TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
        }
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    private String sourceLanguageForOcr() {
        int index = clampLanguageIndex(prefs.getInt("source_index", 2));
        return LanguageOption.ALL[index].mlKitTag;
    }

    private String sourceLanguageForOutgoing() {
        int index = clampLanguageIndex(prefs.getInt("source_index", 2));
        return LanguageOption.ALL[index].mlKitTag;
    }

    private String sourceLanguageLabel() {
        int index = clampLanguageIndex(prefs.getInt("source_index", 2));
        return LanguageOption.ALL[index].label;
    }

    private String targetLanguage() {
        int index = clampLanguageIndex(prefs.getInt("target_index", 0));
        return LanguageOption.ALL[index].mlKitTag;
    }

    private int clampLanguageIndex(int value) {
        return Math.max(0, Math.min(value, LanguageOption.ALL.length - 1));
    }

    private boolean sameLanguage(String detected, String target) {
        if (detected == null || detected.trim().isEmpty()) return false;
        String a = detected.toLowerCase(Locale.ROOT);
        String b = target.toLowerCase(Locale.ROOT);
        if ("fil".equals(a)) a = "tl";
        if ("fil".equals(b)) b = "tl";
        if (a.startsWith("zh")) a = "zh";
        if (b.startsWith("zh")) b = "zh";
        return a.equals(b);
    }

    private String screenSignature(List<Block> blocks, String target) {
        StringBuilder out = new StringBuilder(lastWindowPackage).append('|').append(target);
        for (Block block : blocks) {
            out.append('|').append(block.text).append('@')
                .append(block.bounds.left).append(',').append(block.bounds.top).append(',')
                .append(block.bounds.right).append(',').append(block.bounds.bottom);
        }
        return Integer.toHexString(out.toString().hashCode()) + ':' + blocks.size();
    }

    private static String cacheKey(String target, String text) {
        return target + '\u0001' + text;
    }

    private boolean isUsefulBounds(Rect bounds) {
        if (bounds == null || bounds.width() < dp(8) || bounds.height() < dp(8)) return false;
        Rect screen = windowManager == null ? null : windowManager.getMaximumWindowMetrics().getBounds();
        if (screen == null) return true;
        return Rect.intersects(screen, bounds);
    }

    private static boolean isTranslatableText(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() < 2 || text.length() > 1000) return false;
        int meaningful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || isCjk(c)) meaningful++;
        }
        return meaningful >= 2;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u3040' && c <= '\u30ff')
            || (c >= '\u3400' && c <= '\u9fff')
            || (c >= '\uac00' && c <= '\ud7af');
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u0000', ' ')
            .replaceAll("[\\t ]+", " ")
            .replaceAll("\\n{3,}", "\\n\\n")
            .trim();
    }

    private void addOverlayWindow() {
        if (windowManager == null || overlayView != null) return;
        overlayView = new ScreenTranslationOverlayView(this);
        overlayView.setVisibility(View.INVISIBLE);
        overlayParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception e) {
            overlayView = null;
            saveStatus("覆盖层创建失败：" + safe(e));
        }
    }

    private void addBubbleWindow() {
        if (windowManager == null || bubble != null) return;
        bubble = new TextView(this);
        bubble.setText("译");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(18f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(dp(8));
        bubble.setContentDescription("浮译全局翻译：轻点翻译屏幕，双击反向翻译当前输入框，长按开关自动翻译，拖动可移动");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(235, 111, 72, 210));
        bg.setStroke(dp(1), Color.argb(180, 225, 214, 255));
        bubble.setBackground(bg);

        int size = dp(54);
        bubbleParams = new WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        Rect screen = windowManager.getMaximumWindowMetrics().getBounds();
        bubbleParams.x = Math.max(dp(8), screen.width() - size - dp(14));
        bubbleParams.y = dp(180);
        bubble.setOnTouchListener(new BubbleTouchListener());
        try {
            windowManager.addView(bubble, bubbleParams);
        } catch (Exception e) {
            bubble = null;
            saveStatus("悬浮球创建失败：" + safe(e));
        }
    }

    private final class BubbleTouchListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float downX;
        private float downY;
        private long downAt;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent event) {
            if (bubbleParams == null || windowManager == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = SystemClock.elapsedRealtime();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        Rect screen = windowManager.getMaximumWindowMetrics().getBounds();
                        int maxX = Math.max(0, screen.width() - bubbleParams.width);
                        int maxY = Math.max(0, screen.height() - bubbleParams.height);
                        bubbleParams.x = Math.max(0, Math.min(maxX, startX + dx));
                        bubbleParams.y = Math.max(0, Math.min(maxY, startY + dy));
                        try { windowManager.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        long held = SystemClock.elapsedRealtime() - downAt;
                        if (held >= 650L) toggleContinuous();
                        else handleBubbleTap();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void handleBubbleTap() {
        long now = SystemClock.elapsedRealtime();
        if (lastTapAt > 0 && now - lastTapAt <= DOUBLE_TAP_MS && pendingSingleTap != null) {
            main.removeCallbacks(pendingSingleTap);
            pendingSingleTap = null;
            lastTapAt = 0L;
            translateFocusedInput();
            return;
        }
        lastTapAt = now;
        pendingSingleTap = () -> {
            pendingSingleTap = null;
            lastTapAt = 0L;
            translateCurrentScreen(true);
        };
        main.postDelayed(pendingSingleTap, DOUBLE_TAP_MS);
    }

    private void toggleContinuous() {
        if (pendingSingleTap != null) {
            main.removeCallbacks(pendingSingleTap);
            pendingSingleTap = null;
            lastTapAt = 0L;
        }
        boolean next = !prefs.getBoolean(PREF_SCREEN_CONTINUOUS, false);
        prefs.edit().putBoolean(PREF_SCREEN_CONTINUOUS, next).apply();
        updateBubbleLabel();
        toast(next ? "全局自动翻译：已开启" : "全局自动翻译：已关闭");
        if (next) translateCurrentScreen(true);
    }

    private void updateBubbleLabel() {
        if (bubble == null || prefs == null) return;
        if (translating || screenshotBusy) {
            bubble.setText("…");
        } else {
            bubble.setText(prefs.getBoolean(PREF_SCREEN_CONTINUOUS, false) ? "自" : "译");
        }
    }

    private void setBubbleText(String value) {
        if (bubble != null) bubble.setText(value);
    }

    private void setBubbleTemporarilyHidden(boolean hidden) {
        if (bubble != null) bubble.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void clearOverlay() {
        if (overlayView != null) overlayView.clear();
    }

    private void removeWindows() {
        if (windowManager == null) return;
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
        if (bubble != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
    }

    private void saveStatus(String value) {
        if (prefs != null) prefs.edit().putString(PREF_SCREEN_LAST_STATUS, value).apply();
    }

    private void toast(String value) {
        main.post(() -> Toast.makeText(this, value, Toast.LENGTH_SHORT).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
            ? e.getClass().getSimpleName() : message;
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        ComponentName component = new ComponentName(context, ScreenTranslationAccessibilityService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();
        for (String item : enabled.split(":")) {
            if (full.equalsIgnoreCase(item) || shortName.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private static final class Block {
        final String text;
        final Rect bounds;

        Block(String text, Rect bounds) {
            this.text = text;
            this.bounds = new Rect(bounds);
        }
    }

    private static final class CachedTranslation {
        final String sourceLanguage;
        final String translated;

        CachedTranslation(String sourceLanguage, String translated) {
            this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
            this.translated = translated == null ? "" : translated;
        }
    }
}
