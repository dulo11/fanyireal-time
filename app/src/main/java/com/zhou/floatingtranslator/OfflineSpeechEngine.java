package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline streaming ASR backed by Vosk.
 *
 * A compact language model is downloaded once from the official Vosk model host,
 * extracted into app-private storage, and reused fully offline afterwards.
 */
public final class OfflineSpeechEngine implements AutoCloseable {
    public interface Callback {
        void onStatus(String message);
        void onReady(String engineName);
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final long PARTIAL_INTERVAL_MS = 650L;

    private static final class ModelInfo {
        final String language;
        final String modelName;
        final String url;
        final String sizeLabel;

        ModelInfo(String language, String modelName, String url, String sizeLabel) {
            this.language = language;
            this.modelName = modelName;
            this.url = url;
            this.sizeLabel = sizeLabel;
        }
    }

    private static final Map<String, ModelInfo> MODELS = new LinkedHashMap<>();
    static {
        add("en", "vosk-model-small-en-us-0.15", "40 MB");
        add("zh", "vosk-model-small-cn-0.22", "42 MB");
        add("ja", "vosk-model-small-ja-0.22", "48 MB");
        add("vi", "vosk-model-small-vn-0.4", "32 MB");
        add("ko", "vosk-model-small-ko-0.22", "82 MB");
        add("fr", "vosk-model-small-fr-0.22", "41 MB");
        add("de", "vosk-model-small-de-0.15", "45 MB");
        add("es", "vosk-model-small-es-0.42", "39 MB");
        add("pt", "vosk-model-small-pt-0.3", "31 MB");
        add("it", "vosk-model-small-it-0.22", "48 MB");
        add("nl", "vosk-model-small-nl-0.22", "39 MB");
        add("tr", "vosk-model-small-tr-0.3", "35 MB");
        add("hi", "vosk-model-small-hi-0.22", "42 MB");
        add("ru", "vosk-model-small-ru-0.22", "45 MB");
        add("pl", "vosk-model-small-pl-0.22", "50 MB");
        add("cs", "vosk-model-small-cs-0.4-rhasspy", "44 MB");
    }

    private static void add(String language, String modelName, String sizeLabel) {
        MODELS.put(language, new ModelInfo(
            language,
            modelName,
            "https://alphacephei.com/vosk/models/" + modelName + ".zip",
            sizeLabel
        ));
    }

    public static boolean supports(String language) {
        return MODELS.containsKey(normalize(language));
    }

    public static String supportedSummary() {
        return "中文、英语、日语、越南语、韩语、法语、德语、西语、葡语、意大利语、荷兰语、土耳其语、印地语、俄语、波兰语、捷克语";
    }

    private final Context context;
    private final String language;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Object recognizerLock = new Object();

    private Model model;
    private Recognizer recognizer;
    private volatile boolean ready;
    private volatile boolean closed;
    private String lastPartial = "";
    private long lastPartialAt;

    public OfflineSpeechEngine(Context context, String language, Callback callback) {
        this.context = context.getApplicationContext();
        this.language = normalize(language);
        this.callback = callback;
        try { LibVosk.setLogLevel(LogLevel.WARNINGS); } catch (Throwable ignored) {}
    }

    public void prepare() {
        ModelInfo info = MODELS.get(language);
        if (info == null) {
            postError("离线语音暂不支持语言：" + language);
            return;
        }

        loader.execute(() -> {
            try {
                File base = new File(context.getFilesDir(), "vosk-models");
                if (!base.exists() && !base.mkdirs()) {
                    throw new IllegalStateException("无法创建离线模型目录");
                }
                File modelDir = new File(base, info.modelName);
                if (!isInstalled(modelDir)) {
                    postStatus("首次使用正在下载离线语音模型（" + info.sizeLabel + "），以后无需联网……");
                    downloadAndExtract(info, base, modelDir);
                } else {
                    postStatus("正在加载离线语音模型……");
                }

                if (closed) return;
                Model loadedModel = new Model(modelDir.getAbsolutePath());
                Recognizer loadedRecognizer = new Recognizer(loadedModel, (float) SAMPLE_RATE);
                synchronized (recognizerLock) {
                    if (closed) {
                        try { loadedRecognizer.close(); } catch (Exception ignored) {}
                        try { loadedModel.close(); } catch (Exception ignored) {}
                        return;
                    }
                    model = loadedModel;
                    recognizer = loadedRecognizer;
                    ready = true;
                }
                main.post(() -> callback.onReady("Vosk 离线 ASR"));
            } catch (Exception e) {
                postError("离线语音模型准备失败：" + safe(e));
            }
        });
    }

    public boolean isReady() {
        return ready && !closed;
    }

    /** Feed 16 kHz, mono, signed PCM16 little-endian audio. */
    public void acceptPcm(byte[] pcm, int length) {
        if (!isReady() || pcm == null || length <= 0) return;
        try {
            String finalJson = null;
            String partialJson = null;
            synchronized (recognizerLock) {
                if (recognizer == null) return;
                if (recognizer.acceptWaveForm(pcm, length)) {
                    finalJson = recognizer.getResult();
                } else {
                    partialJson = recognizer.getPartialResult();
                }
            }

            if (finalJson != null) {
                String text = extract(finalJson, "text");
                if (!text.isEmpty()) {
                    lastPartial = "";
                    main.post(() -> callback.onFinal(text));
                }
                return;
            }

            if (partialJson != null) {
                String text = extract(partialJson, "partial");
                long now = android.os.SystemClock.elapsedRealtime();
                if (!text.isEmpty() && !text.equals(lastPartial) && now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                    lastPartial = text;
                    lastPartialAt = now;
                    main.post(() -> callback.onPartial(text));
                }
            }
        } catch (Exception e) {
            postError("离线语音识别失败：" + safe(e));
        }
    }

    public void finishCurrentUtterance() {
        if (!isReady()) return;
        try {
            String json;
            synchronized (recognizerLock) {
                if (recognizer == null) return;
                json = recognizer.getFinalResult();
            }
            String text = extract(json, "text");
            if (!text.isEmpty()) main.post(() -> callback.onFinal(text));
        } catch (Exception ignored) {}
    }

    private void downloadAndExtract(ModelInfo info, File base, File finalDir) throws Exception {
        File zip = new File(context.getCacheDir(), info.modelName + ".zip");
        File staging = new File(base, info.modelName + ".part");
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IllegalStateException("无法创建模型临时目录");

        HttpURLConnection conn = (HttpURLConnection) new URL(info.url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "FloatingTranslator/0.4.1");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("模型下载 HTTP " + code);
        }

        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buffer)) != -1) {
                if (closed) throw new IllegalStateException("已停止");
                out.write(buffer, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) Math.min(100, done * 100L / total);
                    if (pct >= lastPct + 10) {
                        lastPct = pct;
                        postStatus("正在下载离线语音模型 " + pct + "%（" + info.sizeLabel + "）");
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        postStatus("正在解压离线语音模型……");
        unzip(zip, staging);

        File extractedRoot = new File(staging, info.modelName);
        File actual = isInstalled(extractedRoot) ? extractedRoot : findModelRoot(staging);
        if (actual == null || !isInstalled(actual)) {
            throw new IllegalStateException("模型压缩包结构无效");
        }

        deleteRecursively(finalDir);
        if (!actual.renameTo(finalDir)) {
            copyDirectory(actual, finalDir);
        }
        deleteRecursively(staging);
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
    }

    private void unzip(File zip, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("非法模型压缩路径");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("无法创建目录");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IllegalStateException("无法创建模型目录");
                    }
                    try (BufferedOutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zin.read(buffer)) != -1) fout.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static File findModelRoot(File root) {
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (isInstalled(child)) return child;
        }
        return null;
    }

    private static boolean isInstalled(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return new File(dir, "am").isDirectory()
            && new File(dir, "conf").isDirectory()
            && (new File(dir, "graph").isDirectory() || new File(dir, "graph/HCLr.fst").exists());
    }

    private static void copyDirectory(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("无法创建模型目录");
            File[] files = source.listFiles();
            if (files != null) for (File file : files) copyDirectory(file, new File(target, file.getName()));
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建目录");
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String extract(String json, String key) {
        if (json == null || json.isEmpty()) return "";
        try { return new JSONObject(json).optString(key, "").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private void postStatus(String message) {
        main.post(() -> {
            if (!closed) callback.onStatus(message);
        });
    }

    private void postError(String message) {
        main.post(() -> {
            if (!closed) callback.onError(message);
        });
    }

    private static String normalize(String language) {
        if (language == null) return "";
        String value = language.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash > 0) value = value.substring(0, dash);
        if ("cn".equals(value)) return "zh";
        return value;
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        ready = false;
        loader.shutdownNow();
        synchronized (recognizerLock) {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) {}
                recognizer = null;
            }
            if (model != null) {
                try { model.close(); } catch (Exception ignored) {}
                model = null;
            }
        }
    }
}
