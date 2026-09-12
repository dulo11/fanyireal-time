package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Base64;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified translation layer. Cloud credentials are supplied by the user and stored via SecureConfig.
 * AUTO is consumed through OfflineFirstTranslationRouter, so ML Kit runs first; if local translation
 * fails, AUTO cloud fallback is intentionally focused on Baidu, Azure and Alibaba Cloud.
 */
public final class TranslationRouter implements AutoCloseable {
    public static final String AUTO = "auto";
    public static final String MLKIT = "mlkit";
    public static final String BAIDU = "baidu";
    public static final String AZURE = "azure";
    public static final String ALIYUN = "aliyun";
    public static final String YOUDAO = "youdao";
    public static final String DEEPL = "deepl";
    public static final String GOOGLE = "google";
    public static final String LIBRE = "libre";

    public static final String[] ENGINE_IDS = {
        AUTO, MLKIT, BAIDU, AZURE, ALIYUN, YOUDAO, DEEPL, GOOGLE, LIBRE
    };

    public static final String[] ENGINE_LABELS = {
        "自动（ML Kit 离线优先；百度 / Azure / 阿里云兜底）",
        "ML Kit 本地离线",
        "百度翻译",
        "Azure Translator",
        "阿里云机器翻译",
        "有道智云（兼容备用）",
        "DeepL（兼容备用）",
        "Google Cloud Translation（兼容备用）",
        "LibreTranslate（兼容备用）"
    };

    public interface Callback {
        void onSuccess(String translated, String engineName);
        void onError(String message);
    }

    public interface SpeechCallback {
        void onSuccess(String original, String translated, String engineName);
        void onError(String message);
    }

    private static final String PREFS = "floating_translator";
    private static final String PREF_AZURE_ACTIVE_SLOT = "azure_active_slot";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final SecureConfig secure;
    private final SharedPreferences prefs;
    private final String source;
    private final String target;
    private final String selectedEngine;
    private final Translator mlKit;
    private volatile boolean closed;
    private volatile int lastAzureSlot = 1;

    public TranslationRouter(Context context, String source, String target, String selectedEngine) {
        this.secure = new SecureConfig(context);
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.source = source;
        this.target = target;
        this.selectedEngine = normalizeEngine(selectedEngine);
        this.mlKit = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build());
    }

    public String selectedEngineName() {
        return engineLabel(selectedEngine);
    }

    public boolean hasYoudaoCredentials() {
        return secure.has(SecureConfig.YOUDAO_APP_KEY) && secure.has(SecureConfig.YOUDAO_SECRET);
    }

    public boolean isConfigured(String engine) {
        switch (engine) {
            case MLKIT:
                return true;
            case BAIDU:
                return secure.has(SecureConfig.BAIDU_APP_ID) && secure.has(SecureConfig.BAIDU_SECRET);
            case AZURE:
                return secure.hasAnyAzureProfile();
            case ALIYUN:
                return secure.has(SecureConfig.ALIYUN_ACCESS_KEY_ID)
                    && secure.has(SecureConfig.ALIYUN_ACCESS_KEY_SECRET);
            case YOUDAO:
                return hasYoudaoCredentials();
            case DEEPL:
                return secure.has(SecureConfig.DEEPL_KEY);
            case GOOGLE:
                return secure.has(SecureConfig.GOOGLE_KEY);
            case LIBRE:
                return !secure.get(SecureConfig.LIBRE_ENDPOINT).isEmpty();
            case AUTO:
            default:
                return true;
        }
    }

    public void translate(String text, Callback callback) {
        if (closed) {
            callback.onError("翻译引擎已经关闭");
            return;
        }
        String cleaned = cleanInput(text);
        if (cleaned.isEmpty()) {
            callback.onError("没有可翻译文字");
            return;
        }
        if (AUTO.equals(selectedEngine)) {
            translateAuto(cleaned, autoOrder(), 0, callback, new ArrayList<>());
        } else {
            translateSingle(selectedEngine, cleaned, callback);
        }
    }

    private void translateAuto(String text, List<String> engines, int index,
                               Callback callback, List<String> errors) {
        if (index >= engines.size()) {
            callback.onError(errors.isEmpty() ? "没有可用翻译引擎" : String.join("；", errors));
            return;
        }
        String engine = engines.get(index);
        if (!isConfigured(engine)) {
            translateAuto(text, engines, index + 1, callback, errors);
            return;
        }
        translateSingle(engine, text, new Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                callback.onSuccess(translated, engineName);
            }

            @Override public void onError(String message) {
                errors.add(engineLabel(engine) + ": " + message);
                translateAuto(text, engines, index + 1, callback, errors);
            }
        });
    }

    private List<String> autoOrder() {
        List<String> list = new ArrayList<>();
        if (isEastAsianPair(source, target)) {
            list.add(BAIDU);
            list.add(AZURE);
            list.add(ALIYUN);
        } else {
            list.add(AZURE);
            list.add(ALIYUN);
            list.add(BAIDU);
        }
        // This last ML Kit matters only if TranslationRouter AUTO is called directly.
        list.add(MLKIT);
        return list;
    }

    private static boolean isEastAsianPair(String a, String b) {
        return "zh".equals(a) || "ja".equals(a) || "ko".equals(a)
            || "zh".equals(b) || "ja".equals(b) || "ko".equals(b);
    }

    private void translateSingle(String engine, String text, Callback callback) {
        if (!isConfigured(engine)) {
            callback.onError("尚未填写该引擎的 API 配置");
            return;
        }
        if (MLKIT.equals(engine)) {
            translateMlKit(text, callback);
            return;
        }
        network.execute(() -> {
            try {
                String result;
                switch (engine) {
                    case BAIDU: result = translateBaidu(text); break;
                    case AZURE: result = translateAzure(text); break;
                    case ALIYUN: result = translateAliyun(text); break;
                    case YOUDAO: result = translateYoudao(text); break;
                    case DEEPL: result = translateDeepL(text); break;
                    case GOOGLE: result = translateGoogle(text); break;
                    case LIBRE: result = translateLibre(text); break;
                    default: throw new IllegalArgumentException("未知翻译引擎");
                }
                String finalResult = result == null ? "" : result.trim();
                if (finalResult.isEmpty()) throw new IllegalStateException("返回了空译文");
                String successName = AZURE.equals(engine)
                    ? engineLabel(engine) + " · 账号" + lastAzureSlot
                    : engineLabel(engine);
                main.post(() -> callback.onSuccess(finalResult, successName));
            } catch (Exception e) {
                main.post(() -> callback.onError(safe(e)));
            }
        });
    }

    private void translateMlKit(String text, Callback callback) {
        mlKit.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .continueWithTask(task -> {
                if (!task.isSuccessful()) throw task.getException();
                return mlKit.translate(text);
            })
            .addOnSuccessListener(value -> callback.onSuccess(value, engineLabel(MLKIT)))
            .addOnFailureListener(e -> callback.onError(safe(e)));
    }

    private String translateBaidu(String text) throws Exception {
        String appId = secure.get(SecureConfig.BAIDU_APP_ID);
        String secret = secure.get(SecureConfig.BAIDU_SECRET);
        String salt = Long.toString(System.nanoTime());
        String sign = md5(appId + text + salt + secret);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", text);
        form.put("from", baiduCode(source));
        form.put("to", baiduCode(target));
        form.put("appid", appId);
        form.put("salt", salt);
        form.put("sign", sign);
        JSONObject json = new JSONObject(postForm(
            "https://fanyi-api.baidu.com/api/trans/vip/translate", form, null));
        if (json.has("error_code")) {
            throw new IllegalStateException("错误 " + json.optString("error_code") + " "
                + json.optString("error_msg"));
        }
        JSONArray arr = json.optJSONArray("trans_result");
        if (arr == null || arr.length() == 0) throw new IllegalStateException("没有翻译结果");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) out.append('\n');
            out.append(arr.getJSONObject(i).optString("dst"));
        }
        return out.toString();
    }

    /**
     * Azure supports up to four locally encrypted profiles. The last successful slot is tried first.
     * If Azure reports quota/rate/auth subscription failures (typically HTTP 401/403/429), the next
     * configured slot is tried automatically. Normal connectivity errors are surfaced immediately so
     * a temporary network outage does not incorrectly mark/cycle through every account.
     */
    private String translateAzure(String text) throws Exception {
        String endpoint = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
            + "&from=" + url(source) + "&to=" + url(azureCode(target));
        JSONArray body = new JSONArray();
        body.put(new JSONObject().put("Text", text));

        int startSlot = Math.max(1, Math.min(4, prefs.getInt(PREF_AZURE_ACTIVE_SLOT, 1)));
        List<String> errors = new ArrayList<>();
        boolean foundConfigured = false;

        for (int offset = 0; offset < 4; offset++) {
            int slot = ((startSlot - 1 + offset) % 4) + 1;
            String key = secure.get(SecureConfig.azureKeyName(slot));
            if (key.isEmpty()) continue;
            foundConfigured = true;
            String region = secure.get(SecureConfig.azureRegionName(slot));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            headers.put("Ocp-Apim-Subscription-Key", key);
            if (!region.trim().isEmpty()) {
                headers.put("Ocp-Apim-Subscription-Region", region.trim());
            }

            try {
                String response = postRaw(endpoint, body.toString(), headers);
                JSONArray arr = new JSONArray(response);
                String translated = arr.getJSONObject(0)
                    .getJSONArray("translations").getJSONObject(0).getString("text");
                lastAzureSlot = slot;
                prefs.edit().putInt(PREF_AZURE_ACTIVE_SLOT, slot).apply();
                return translated;
            } catch (Exception e) {
                String message = safe(e);
                errors.add("账号" + slot + "：" + message);
                if (!shouldRotateAzure(message)) throw e;
                int next = nextConfiguredAzureSlot(slot);
                if (next > 0) prefs.edit().putInt(PREF_AZURE_ACTIVE_SLOT, next).apply();
            }
        }

        if (!foundConfigured) throw new IllegalStateException("未配置 Azure 账号1-4");
        throw new IllegalStateException("Azure 账号1-4 均不可用：" + String.join("；", errors));
    }

    private int nextConfiguredAzureSlot(int current) {
        for (int step = 1; step <= 4; step++) {
            int slot = ((current - 1 + step) % 4) + 1;
            if (secure.has(SecureConfig.azureKeyName(slot))) return slot;
        }
        return -1;
    }

    private static boolean shouldRotateAzure(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("http 401") || m.contains("http 403") || m.contains("http 429")
            || m.contains("quota") || m.contains("exceed") || m.contains("limit")
            || m.contains("subscription") || m.contains("rate");
    }

    private String translateAliyun(String text) throws Exception {
        return AliyunTranslationClient.translate(
            secure.get(SecureConfig.ALIYUN_ACCESS_KEY_ID),
            secure.get(SecureConfig.ALIYUN_ACCESS_KEY_SECRET),
            source, target, text);
    }

    private String translateYoudao(String text) throws Exception {
        String appKey = secure.get(SecureConfig.YOUDAO_APP_KEY);
        String appSecret = secure.get(SecureConfig.YOUDAO_SECRET);
        String salt = UUID.randomUUID().toString();
        String curtime = Long.toString(System.currentTimeMillis() / 1000L);
        String sign = sha256(appKey + truncateForYoudao(text) + salt + curtime + appSecret);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", text);
        form.put("from", youdaoCode(source));
        form.put("to", youdaoCode(target));
        form.put("appKey", appKey);
        form.put("salt", salt);
        form.put("sign", sign);
        form.put("signType", "v3");
        form.put("curtime", curtime);
        JSONObject json = new JSONObject(postForm("https://openapi.youdao.com/v2/api", form, null));
        String code = json.optString("errorCode", "0");
        if (!"0".equals(code)) throw new IllegalStateException("错误 " + code);
        JSONArray results = json.optJSONArray("translateResults");
        if (results != null && results.length() > 0) {
            return results.getJSONObject(0).optString("translation");
        }
        JSONArray translation = json.optJSONArray("translation");
        if (translation != null && translation.length() > 0) return translation.optString(0);
        throw new IllegalStateException("没有翻译结果");
    }

    private String translateDeepL(String text) throws Exception {
        String key = secure.get(SecureConfig.DEEPL_KEY);
        String endpoint = key.endsWith(":fx")
            ? "https://api-free.deepl.com/v2/translate"
            : "https://api.deepl.com/v2/translate";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "DeepL-Auth-Key " + key);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("text", text);
        form.put("source_lang", deepLSource(source));
        form.put("target_lang", deepLTarget(target));
        JSONObject json = new JSONObject(postForm(endpoint, form, headers));
        JSONArray arr = json.getJSONArray("translations");
        return arr.getJSONObject(0).getString("text");
    }

    private String translateGoogle(String text) throws Exception {
        String key = secure.get(SecureConfig.GOOGLE_KEY);
        String endpoint = "https://translation.googleapis.com/language/translate/v2?key=" + url(key);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", text);
        form.put("source", googleCode(source));
        form.put("target", googleCode(target));
        form.put("format", "text");
        JSONObject json = new JSONObject(postForm(endpoint, form, null));
        String translated = json.getJSONObject("data")
            .getJSONArray("translations").getJSONObject(0).getString("translatedText");
        return Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private String translateLibre(String text) throws Exception {
        String endpoint = secure.get(SecureConfig.LIBRE_ENDPOINT);
        if (endpoint.isEmpty()) throw new IllegalStateException("未填写 LibreTranslate Endpoint");
        if (!endpoint.endsWith("/translate")) {
            endpoint = endpoint.replaceAll("/+$", "") + "/translate";
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("q", text);
        form.put("source", source);
        form.put("target", target);
        form.put("format", "text");
        String apiKey = secure.get(SecureConfig.LIBRE_KEY);
        if (!apiKey.isEmpty()) form.put("api_key", apiKey);
        JSONObject json = new JSONObject(postForm(endpoint, form, null));
        return json.getString("translatedText");
    }

    /**
     * Optional cloud speech fallback for phones without a working Android RecognitionService.
     * Accepts 16 kHz, 16-bit, mono little-endian PCM and uses Youdao speech translation.
     */
    public void translateYoudaoSpeech(byte[] pcm16le, SpeechCallback callback) {
        if (!hasYoudaoCredentials()) {
            callback.onError("未配置有道 AppKey/AppSecret");
            return;
        }
        if (pcm16le == null || pcm16le.length < 3200) {
            callback.onError("音频太短");
            return;
        }
        network.execute(() -> {
            try {
                byte[] wav = pcmToWav(pcm16le, 16000, 1, 16);
                String q = Base64.encodeToString(wav, Base64.NO_WRAP);
                String appKey = secure.get(SecureConfig.YOUDAO_APP_KEY);
                String appSecret = secure.get(SecureConfig.YOUDAO_SECRET);
                String salt = UUID.randomUUID().toString();
                String curtime = Long.toString(System.currentTimeMillis() / 1000L);
                String sign = sha256(appKey + truncateForYoudao(q) + salt + curtime + appSecret);
                Map<String, String> form = new LinkedHashMap<>();
                form.put("q", q);
                form.put("from", youdaoCode(source));
                form.put("to", youdaoCode(target));
                form.put("appKey", appKey);
                form.put("salt", salt);
                form.put("sign", sign);
                form.put("signType", "v3");
                form.put("curtime", curtime);
                form.put("format", "wav");
                form.put("rate", "16000");
                form.put("channel", "1");
                form.put("type", "1");
                form.put("version", "v1");
                JSONObject json = new JSONObject(postForm(
                    "https://openapi.youdao.com/speechtransapi", form, null));
                String code = json.optString("errorCode", "0");
                if (!"0".equals(code)) throw new IllegalStateException("有道语音错误 " + code);
                String original = json.optString("query", "");
                JSONArray tr = json.optJSONArray("translation");
                String translated = tr != null && tr.length() > 0 ? tr.optString(0) : "";
                if (translated.isEmpty()) throw new IllegalStateException("有道语音未返回译文");
                main.post(() -> callback.onSuccess(original, translated, "有道语音翻译"));
            } catch (Exception e) {
                main.post(() -> callback.onError(safe(e)));
            }
        });
    }

    private static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bits) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        DataOutputStream d = new DataOutputStream(out);
        int byteRate = sampleRate * channels * bits / 8;
        writeAscii(d, "RIFF");
        writeLeInt(d, 36 + pcm.length);
        writeAscii(d, "WAVE");
        writeAscii(d, "fmt ");
        writeLeInt(d, 16);
        writeLeShort(d, 1);
        writeLeShort(d, channels);
        writeLeInt(d, sampleRate);
        writeLeInt(d, byteRate);
        writeLeShort(d, channels * bits / 8);
        writeLeShort(d, bits);
        writeAscii(d, "data");
        writeLeInt(d, pcm.length);
        d.write(pcm);
        d.flush();
        return out.toByteArray();
    }

    private static void writeAscii(DataOutputStream d, String s) throws Exception {
        d.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLeInt(DataOutputStream d, int v) throws Exception {
        d.writeByte(v & 0xff);
        d.writeByte((v >> 8) & 0xff);
        d.writeByte((v >> 16) & 0xff);
        d.writeByte((v >> 24) & 0xff);
    }

    private static void writeLeShort(DataOutputStream d, int v) throws Exception {
        d.writeByte(v & 0xff);
        d.writeByte((v >> 8) & 0xff);
    }

    private static String postForm(String endpoint, Map<String, String> form,
                                   Map<String, String> extraHeaders) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(url(entry.getKey())).append('=').append(url(entry.getValue()));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return postRaw(endpoint, body.toString(), headers);
    }

    private static String postRaw(String endpoint, String body,
                                  Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
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

    private static String cleanInput(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 1800) text = text.substring(0, 1800);
        return text;
    }

    private static String truncateForYoudao(String q) {
        int len = q.length();
        return len <= 20 ? q : q.substring(0, 10) + len + q.substring(len - 10);
    }

    private static String md5(String value) throws Exception {
        return digest("MD5", value);
    }

    private static String sha256(String value) throws Exception {
        return digest("SHA-256", value);
    }

    private static String digest(String algorithm, String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance(algorithm)
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String url(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String baiduCode(String code) {
        switch (code) {
            case "ja": return "jp";
            case "ko": return "kor";
            case "fr": return "fra";
            case "es": return "spa";
            case "ar": return "ara";
            case "vi": return "vie";
            case "tl": return "fil";
            case "bg": return "bul";
            case "et": return "est";
            case "da": return "dan";
            case "fi": return "fin";
            case "ro": return "rom";
            case "sl": return "slo";
            case "sv": return "swe";
            case "ka": return "geo";
            case "gu": return "guj";
            case "kn": return "kan";
            case "ca": return "cat";
            default: return code;
        }
    }

    private static String youdaoCode(String code) {
        if ("zh".equals(code)) return "zh-CHS";
        return code;
    }

    private static String googleCode(String code) {
        return "tl".equals(code) ? "fil" : code;
    }

    private static String azureCode(String code) {
        return "tl".equals(code) ? "fil" : code;
    }

    private static String deepLSource(String code) {
        if ("zh".equals(code)) return "ZH";
        if ("tl".equals(code)) return "EN";
        return code.toUpperCase(Locale.US);
    }

    private static String deepLTarget(String code) {
        if ("zh".equals(code)) return "ZH-HANS";
        if ("en".equals(code)) return "EN-US";
        if ("pt".equals(code)) return "PT-BR";
        if ("tl".equals(code)) throw new IllegalArgumentException("DeepL 暂不支持菲律宾语目标语言");
        return code.toUpperCase(Locale.US);
    }

    private static String normalizeEngine(String engine) {
        if (engine == null) return AUTO;
        for (String id : ENGINE_IDS) if (id.equals(engine)) return id;
        return AUTO;
    }

    public static String engineLabel(String id) {
        for (int i = 0; i < ENGINE_IDS.length; i++) {
            if (ENGINE_IDS[i].equals(id)) return ENGINE_LABELS[i];
        }
        return id;
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
            ? e.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        try { mlKit.close(); } catch (Exception ignored) {}
        network.shutdownNow();
    }
}
