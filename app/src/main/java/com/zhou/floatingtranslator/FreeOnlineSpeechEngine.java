package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free-tier online ASR for realtime PCM.
 *
 * AUTO uses only user-configured free-tier providers:
 * Groq Whisper Large V3 first, then Cloudflare Workers AI Whisper Large V3 Turbo.
 * It never falls through to a paid speech API.
 */
public final class FreeOnlineSpeechEngine implements AutoCloseable {
    public static final String AUTO = "free_online_auto";
    public static final String GROQ_LARGE = "groq_whisper_v3";
    public static final String GROQ_TURBO = "groq_whisper_turbo";
    public static final String CLOUDFLARE = "cloudflare_whisper_turbo";

    public interface Callback {
        void onStatus(String message);
        void onReady(String engineName);
        void onText(String text, String detectedLanguage, String engineName);
        void onError(String message, boolean fatal);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int SPEECH_PEAK = 90;
    private static final long END_SILENCE_MS = 420L;
    private static final long MIN_SEGMENT_MS = 650L;
    // Keeps continuous speech below Groq Free's published 20 RPM ceiling.
    private static final long FORCE_SEGMENT_MS = 4200L;
    private static final int MAX_QUEUE = 5;

    private final Context context;
    private final String mode;
    private final String languageMode;
    private final String sourceLanguage;
    private final Callback callback;
    private final SecureConfig secure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ByteArrayOutputStream speech = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

    private volatile boolean closed;
    private volatile boolean ready;
    private boolean inSpeech;
    private long segmentMs;
    private long silenceMs;
    private boolean requestBusy;
    private int consecutiveFailures;
    private String lastTranscript = "";

    public FreeOnlineSpeechEngine(Context context, String mode, String languageMode,
                                  String sourceLanguage, Callback callback) {
        this.context = context.getApplicationContext();
        this.mode = normalizeMode(mode);
        this.languageMode = languageMode == null ? SherpaSpeechEngine.LANG_AUTO : languageMode;
        this.sourceLanguage = normalizeLanguage(sourceLanguage);
        this.callback = callback;
        this.secure = new SecureConfig(this.context);
    }

    public static boolean hasAnyConfigured(Context context) {
        SecureConfig secure = new SecureConfig(context);
        return secure.has(SecureConfig.GROQ_API_KEY)
            || (secure.has(SecureConfig.CLOUDFLARE_ACCOUNT_ID)
                && secure.has(SecureConfig.CLOUDFLARE_AI_TOKEN));
    }

    public static String configuredSummary(Context context) {
        SecureConfig secure = new SecureConfig(context);
        List<String> out = new ArrayList<>();
        if (secure.has(SecureConfig.GROQ_API_KEY)) out.add("Groq Free");
        if (secure.has(SecureConfig.CLOUDFLARE_ACCOUNT_ID)
            && secure.has(SecureConfig.CLOUDFLARE_AI_TOKEN)) out.add("Cloudflare Workers AI Free");
        return out.isEmpty() ? "未配置" : String.join(" + ", out);
    }

    public boolean isReady() { return ready && !closed; }

    public void prepare() {
        List<String> providers = providers();
        if (providers.isEmpty()) {
            postError("免费在线 ASR 未配置。请填写 Groq Free Key 或 Cloudflare Workers AI Account ID + Token。", true);
            return;
        }
        ready = true;
        String label = providerLabel(providers.get(0));
        if (providers.size() > 1) label += " → " + providerLabel(providers.get(1));
        final String finalLabel = label;
        main.post(() -> {
            if (!closed) callback.onReady("免费在线 ASR · " + finalLabel);
        });
    }

    /** Accepts 16 kHz mono PCM16 LE audio. */
    public synchronized void acceptPcm(byte[] pcm, int length, int peak) {
        if (!isReady() || pcm == null || length <= 0) return;
        int safeLength = Math.min(length, pcm.length);
        long frameMs = Math.max(1L, Math.round((safeLength / 2.0) * 1000.0 / SAMPLE_RATE));
        boolean voiced = peak >= SPEECH_PEAK;

        if (voiced) {
            if (!inSpeech) {
                inSpeech = true;
                speech.reset();
                segmentMs = 0L;
                silenceMs = 0L;
            }
            silenceMs = 0L;
        } else if (inSpeech) {
            silenceMs += frameMs;
        } else {
            return;
        }

        speech.write(pcm, 0, safeLength);
        segmentMs += frameMs;

        boolean natural = silenceMs >= END_SILENCE_MS && segmentMs >= MIN_SEGMENT_MS;
        boolean forced = segmentMs >= FORCE_SEGMENT_MS;
        if (natural || forced) finishSegmentLocked();
    }

    public synchronized void flush() {
        if (closed) return;
        if (speech.size() >= SAMPLE_RATE) finishSegmentLocked();
    }

    private void finishSegmentLocked() {
        byte[] chunk = speech.toByteArray();
        speech.reset();
        inSpeech = false;
        segmentMs = 0L;
        silenceMs = 0L;
        if (chunk.length < SAMPLE_RATE) return;
        if (pending.size() >= MAX_QUEUE) {
            pending.pollFirst();
            postStatus("在线 ASR 队列拥堵，已丢弃最旧片段");
        }
        pending.offerLast(chunk);
        processNextLocked();
    }

    private void processNextLocked() {
        if (closed || requestBusy) return;
        byte[] chunk = pending.pollFirst();
        if (chunk == null) return;
        requestBusy = true;
        worker.execute(() -> {
            Result result = null;
            StringBuilder errors = new StringBuilder();
            for (String provider : providers()) {
                if (closed) break;
                try {
                    postStatus("在线识别中 · " + providerLabel(provider));
                    result = transcribe(provider, chunk);
                    if (result != null && !result.text.isEmpty()) break;
                } catch (Exception e) {
                    if (errors.length() > 0) errors.append("；");
                    errors.append(providerLabel(provider)).append("：").append(safe(e));
                }
            }

            if (closed) return;
            if (result != null && !result.text.isEmpty()) {
                consecutiveFailures = 0;
                String cleaned = AsrTranscriptGuard.clean(result.text);
                if (!cleaned.isEmpty() && !cleaned.equals(lastTranscript)) {
                    lastTranscript = cleaned;
                    Result finalResult = result;
                    main.post(() -> {
                        if (!closed) callback.onText(cleaned, finalResult.language, finalResult.engine);
                    });
                }
            } else {
                consecutiveFailures++;
                boolean fatal = consecutiveFailures >= 2;
                postError(errors.length() == 0 ? "免费在线 ASR 没有返回文字" : errors.toString(), fatal);
            }

            synchronized (FreeOnlineSpeechEngine.this) {
                requestBusy = false;
                processNextLocked();
            }
        });
    }

    private Result transcribe(String provider, byte[] pcm) throws Exception {
        byte[] wav = wav(pcm, SAMPLE_RATE);
        if ("groq".equals(provider)) return transcribeGroq(wav);
        if ("cloudflare".equals(provider)) return transcribeCloudflare(wav);
        throw new IllegalArgumentException("未知在线 ASR：" + provider);
    }

    private Result transcribeGroq(byte[] wav) throws Exception {
        String key = secure.get(SecureConfig.GROQ_API_KEY);
        if (key.isEmpty()) throw new IllegalStateException("未填写 Groq Key");
        String model = GROQ_TURBO.equals(mode) ? "whisper-large-v3-turbo" : "whisper-large-v3";
        String boundary = "----FloatingTranslator" + System.nanoTime();

        HttpURLConnection c = (HttpURLConnection) new URL(
            "https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(18000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("User-Agent", "FloatingTranslator/" + BuildConfig.VERSION_NAME);

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            writeField(out, boundary, "model", model);
            writeField(out, boundary, "response_format", "verbose_json");
            writeField(out, boundary, "temperature", "0");
            String hint = languageHint();
            if (!hint.isEmpty()) writeField(out, boundary, "language", hint);
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
            out.writeBytes("Content-Type: audio/wav\r\n\r\n");
            out.write(wav);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }

        int code = c.getResponseCode();
        String body = readBody(c, code);
        c.disconnect();
        if (code == 429) throw new IllegalStateException("免费额度/速率已到限制（HTTP 429）");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " · " + compact(body));

        JSONObject json = new JSONObject(body);
        String text = json.optString("text", "").trim();
        String lang = normalizeLanguage(json.optString("language", ""));
        return new Result(text, lang,
            GROQ_TURBO.equals(mode) ? "Groq Free · Whisper Large V3 Turbo"
                : "Groq Free · Whisper Large V3");
    }

    private Result transcribeCloudflare(byte[] wav) throws Exception {
        String account = secure.get(SecureConfig.CLOUDFLARE_ACCOUNT_ID);
        String token = secure.get(SecureConfig.CLOUDFLARE_AI_TOKEN);
        if (account.isEmpty() || token.isEmpty()) throw new IllegalStateException("Cloudflare Account ID / Token 未填写");

        String endpoint = "https://api.cloudflare.com/client/v4/accounts/" + account
            + "/ai/run/@cf/openai/whisper-large-v3-turbo";
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(18000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "FloatingTranslator/" + BuildConfig.VERSION_NAME);

        JSONObject request = new JSONObject();
        request.put("audio", Base64.encodeToString(wav, Base64.NO_WRAP));
        request.put("task", "transcribe");
        request.put("vad_filter", true);
        request.put("beam_size", 5);
        request.put("condition_on_previous_text", true);
        String hint = languageHint();
        if (!hint.isEmpty()) request.put("language", hint);
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        c.getOutputStream().write(payload);

        int code = c.getResponseCode();
        String body = readBody(c, code);
        c.disconnect();
        if (code == 429) throw new IllegalStateException("免费额度/速率已到限制（HTTP 429）");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " · " + compact(body));

        JSONObject outer = new JSONObject(body);
        JSONObject result = outer.optJSONObject("result");
        String text = result == null ? outer.optString("text", "") : result.optString("text", "");
        return new Result(text.trim(), "", "Cloudflare Workers AI Free · Whisper Large V3 Turbo");
    }

    private List<String> providers() {
        ArrayList<String> list = new ArrayList<>();
        boolean groq = secure.has(SecureConfig.GROQ_API_KEY);
        boolean cf = secure.has(SecureConfig.CLOUDFLARE_ACCOUNT_ID)
            && secure.has(SecureConfig.CLOUDFLARE_AI_TOKEN);
        if (GROQ_LARGE.equals(mode) || GROQ_TURBO.equals(mode)) {
            if (groq) list.add("groq");
        } else if (CLOUDFLARE.equals(mode)) {
            if (cf) list.add("cloudflare");
        } else {
            // Accuracy first: Groq Large V3; Cloudflare is the free online fallback.
            if (groq) list.add("groq");
            if (cf) list.add("cloudflare");
        }
        return list;
    }

    private String languageHint() {
        if (!SherpaSpeechEngine.LANG_SINGLE.equals(languageMode)) return "";
        String code = normalizeLanguage(sourceLanguage);
        return code.length() == 2 ? code : "";
    }

    private static void writeField(DataOutputStream out, String boundary,
                                   String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static String readBody(HttpURLConnection c, int code) throws Exception {
        InputStream raw = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (raw == null) return "";
        try (BufferedInputStream in = new BufferedInputStream(raw);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static byte[] wav(byte[] pcm, int sampleRate) {
        int dataSize = pcm == null ? 0 : pcm.length;
        int total = 36 + dataSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeBytes("RIFF");
            writeLeInt(d, total);
            d.writeBytes("WAVEfmt ");
            writeLeInt(d, 16);
            writeLeShort(d, 1);
            writeLeShort(d, 1);
            writeLeInt(d, sampleRate);
            writeLeInt(d, sampleRate * 2);
            writeLeShort(d, 2);
            writeLeShort(d, 16);
            d.writeBytes("data");
            writeLeInt(d, dataSize);
            if (pcm != null) d.write(pcm);
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private static void writeLeInt(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
        out.writeByte((value >>> 16) & 0xff);
        out.writeByte((value >>> 24) & 0xff);
    }

    private static void writeLeShort(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private void postStatus(String message) {
        main.post(() -> { if (!closed) callback.onStatus(message); });
    }

    private void postError(String message, boolean fatal) {
        main.post(() -> { if (!closed) callback.onError(message, fatal); });
    }

    private static String providerLabel(String provider) {
        return "groq".equals(provider) ? "Groq Free · Whisper Large V3"
            : "Cloudflare Workers AI Free · Whisper Large V3 Turbo";
    }

    private static String normalizeMode(String value) {
        if (GROQ_LARGE.equals(value) || GROQ_TURBO.equals(value) || CLOUDFLARE.equals(value)) return value;
        return AUTO;
    }

    private static String normalizeLanguage(String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = v.indexOf('-');
        if (dash > 0) v = v.substring(0, dash);
        if ("fil".equals(v)) return "tl";
        if ("iw".equals(v)) return "he";
        if ("in".equals(v)) return "id";
        return v;
    }

    private static String compact(String value) {
        if (value == null) return "";
        String v = value.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > 180 ? v.substring(0, 180) + "…" : v;
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class Result {
        final String text;
        final String language;
        final String engine;
        Result(String text, String language, String engine) {
            this.text = text == null ? "" : text;
            this.language = language == null ? "" : language;
            this.engine = engine == null ? "" : engine;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        ready = false;
        pending.clear();
        speech.reset();
        worker.shutdownNow();
    }
}
