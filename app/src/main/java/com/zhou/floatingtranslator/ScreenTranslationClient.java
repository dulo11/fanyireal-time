package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Batch text translator used by accessibility full-screen translation.
 *
 * Azure is preferred when configured because its /translate endpoint accepts a JSON array and can
 * auto-detect the source language when the "from" query parameter is omitted. This keeps a full
 * screen to one network request instead of one request per TextView. If Azure is not configured,
 * the current TranslationRouter engine is used sequentially as a compatibility fallback.
 */
public final class ScreenTranslationClient implements AutoCloseable {
    public static final class Result {
        public final String sourceLanguage;
        public final String translated;

        Result(String sourceLanguage, String translated) {
            this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
            this.translated = translated == null ? "" : translated;
        }
    }

    public interface Callback {
        void onSuccess(List<Result> results, String engineName);
        void onError(String message);
    }

    private static final String PREFS = "floating_translator";
    private static final int MAX_ITEMS = 80;
    private static final int MAX_ITEM_CHARS = 1000;
    private static final int MAX_TOTAL_CHARS = 42000;

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureConfig secure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public ScreenTranslationClient(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.secure = new SecureConfig(this.context);
    }

    public void translate(List<String> input, String targetLanguage, Callback callback) {
        if (closed) {
            callback.onError("全屏翻译引擎已经关闭");
            return;
        }
        List<String> texts = sanitize(input);
        if (texts.isEmpty()) {
            callback.onError("当前屏幕没有可翻译文字");
            return;
        }
        if (secure.hasAnyAzureProfile()) {
            translateAzureBatch(texts, targetLanguage, callback);
        } else {
            translateWithCurrentEngine(texts, targetLanguage, callback);
        }
    }

    private void translateAzureBatch(List<String> texts, String targetLanguage, Callback callback) {
        network.execute(() -> {
            try {
                List<Integer> slots = secure.configuredAzureSlots();
                if (slots.isEmpty()) throw new IllegalStateException("未配置 Azure 账号");

                String endpoint = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                    + "&to=" + url(azureCode(targetLanguage));
                JSONArray body = new JSONArray();
                for (String text : texts) body.put(new JSONObject().put("Text", text));

                int preferred = prefs.getInt(TranslationRouter.PREF_AZURE_ACTIVE_SLOT, slots.get(0));
                int startIndex = slots.indexOf(preferred);
                if (startIndex < 0) startIndex = 0;
                List<String> errors = new ArrayList<>();

                for (int attempt = 0; attempt < slots.size(); attempt++) {
                    int index = (startIndex + attempt) % slots.size();
                    int slot = slots.get(index);
                    String key = secure.get(SecureConfig.azureKeyName(slot));
                    String region = secure.get(SecureConfig.azureRegionName(slot));

                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put("Content-Type", "application/json; charset=UTF-8");
                    headers.put("Ocp-Apim-Subscription-Key", key);
                    if (!region.trim().isEmpty()) {
                        headers.put("Ocp-Apim-Subscription-Region", region.trim());
                    }

                    try {
                        JSONArray response = new JSONArray(postRaw(endpoint, body.toString(), headers));
                        if (response.length() != texts.size()) {
                            throw new IllegalStateException("Azure 返回条目数异常："
                                + response.length() + "/" + texts.size());
                        }

                        List<Result> results = new ArrayList<>(response.length());
                        for (int i = 0; i < response.length(); i++) {
                            JSONObject row = response.getJSONObject(i);
                            JSONObject detected = row.optJSONObject("detectedLanguage");
                            String source = detected == null ? "" : detected.optString("language", "");
                            JSONArray translations = row.optJSONArray("translations");
                            if (translations == null || translations.length() == 0) {
                                results.add(new Result(source, ""));
                            } else {
                                results.add(new Result(source,
                                    translations.getJSONObject(0).optString("text", "")));
                            }
                        }

                        int nextSlot = slot;
                        String mode = prefs.getString(TranslationRouter.PREF_AZURE_ROTATION_MODE,
                            TranslationRouter.AZURE_ROUND_ROBIN);
                        if (TranslationRouter.AZURE_ROUND_ROBIN.equals(mode) && slots.size() > 1) {
                            nextSlot = slots.get((index + 1) % slots.size());
                        }
                        prefs.edit().putInt(TranslationRouter.PREF_AZURE_ACTIVE_SLOT, nextSlot).apply();
                        String engine = "Azure Translator · 账号" + slot + " · 自动识别";
                        main.post(() -> callback.onSuccess(results, engine));
                        return;
                    } catch (Exception e) {
                        String message = safe(e);
                        errors.add("账号" + slot + "：" + message);
                        if (!shouldRotateAzure(message)) throw e;
                        if (slots.size() > 1) {
                            int nextSlot = slots.get((index + 1) % slots.size());
                            prefs.edit().putInt(TranslationRouter.PREF_AZURE_ACTIVE_SLOT, nextSlot).apply();
                        }
                    }
                }
                throw new IllegalStateException("Azure 已配置账号均不可用：" + String.join("；", errors));
            } catch (Exception e) {
                main.post(() -> callback.onError(safe(e)));
            }
        });
    }

    private void translateWithCurrentEngine(List<String> texts, String targetLanguage, Callback callback) {
        int sourceIndex = clampIndex(prefs.getInt("source_index", 2));
        String source = LanguageOption.ALL[sourceIndex].mlKitTag;
        String target = targetLanguage;
        String engine = prefs.getString("engine_id", TranslationRouter.AUTO);
        if (engine == null) engine = TranslationRouter.AUTO;

        if (source.equals(target)) {
            List<Result> same = new ArrayList<>(texts.size());
            for (String text : texts) same.add(new Result(source, text));
            callback.onSuccess(same, "无需翻译");
            return;
        }

        TranslationRouter router = new TranslationRouter(context, source, target, engine);
        List<Result> out = new ArrayList<>(texts.size());
        translateFallbackNext(router, texts, source, 0, out, callback);
    }

    private void translateFallbackNext(TranslationRouter router, List<String> texts, String source,
                                       int index, List<Result> out, Callback callback) {
        if (closed) {
            router.close();
            callback.onError("全屏翻译已停止");
            return;
        }
        if (index >= texts.size()) {
            String name = router.selectedEngineName();
            router.close();
            callback.onSuccess(out, name);
            return;
        }
        router.translate(texts.get(index), new TranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                out.add(new Result(source, translated));
                translateFallbackNext(router, texts, source, index + 1, out, callback);
            }

            @Override public void onError(String message) {
                router.close();
                callback.onError("第 " + (index + 1) + " 段翻译失败：" + message);
            }
        });
    }

    private static List<String> sanitize(List<String> input) {
        List<String> out = new ArrayList<>();
        if (input == null) return out;
        int total = 0;
        for (String raw : input) {
            if (out.size() >= MAX_ITEMS) break;
            String text = raw == null ? "" : raw.replace('\u0000', ' ')
                .replaceAll("[\\t ]+", " ").trim();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_ITEM_CHARS) text = text.substring(0, MAX_ITEM_CHARS);
            if (total + text.length() > MAX_TOTAL_CHARS) break;
            out.add(text);
            total += text.length();
        }
        return out;
    }

    private int clampIndex(int index) {
        return Math.max(0, Math.min(index, LanguageOption.ALL.length - 1));
    }

    private static boolean shouldRotateAzure(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("http 401") || m.contains("http 403") || m.contains("http 429")
            || m.contains("quota") || m.contains("exceed") || m.contains("limit")
            || m.contains("subscription") || m.contains("rate") || m.contains("too many requests");
    }

    private static String azureCode(String code) {
        return "tl".equals(code) ? "fil" : code;
    }

    private static String url(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String postRaw(String endpoint, String body, Map<String, String> headers)
        throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code
                + (response.isEmpty() ? "" : " " + trimError(response)));
        }
        return response;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String trimError(String value) {
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 180 ? one.substring(0, 180) + "…" : one;
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
            ? e.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        network.shutdownNow();
    }
}
