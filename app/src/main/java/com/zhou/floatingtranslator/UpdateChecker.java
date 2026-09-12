package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight updater that checks this project's latest GitHub Release. */
public final class UpdateChecker implements AutoCloseable {
    public interface Callback {
        void onResult(String latestVersion, String pageUrl, String apkUrl, boolean newer);
        void onError(String message);
    }

    public static final String LATEST_PAGE = "https://github.com/dulo11/fanyireal-time/releases/latest";
    public static final String LATEST_APK = "https://github.com/dulo11/fanyireal-time/releases/latest/download/FloatingTranslator-latest.apk";
    private static final String API = "https://api.github.com/repos/dulo11/fanyireal-time/releases/latest";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public UpdateChecker(Context context) {}

    public void check(String currentVersion, Callback callback) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(API).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "FloatingTranslator/0.5.1 Android");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream())) {
                    byte[] buffer = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) if (n > 0) bytes.write(buffer, 0, n);
                }
                JSONObject json = new JSONObject(bytes.toString(StandardCharsets.UTF_8));
                String tag = json.optString("tag_name", "").trim();
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                String page = json.optString("html_url", LATEST_PAGE);
                boolean newer = compareVersions(latest, currentVersion) > 0;
                if (!closed) main.post(() -> callback.onResult(latest, page, LATEST_APK, newer));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (!closed) main.post(() -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    static int compareVersions(String a, String b) {
        int[] left = parse(a);
        int[] right = parse(b);
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parse(String version) {
        if (version == null) return new int[]{0};
        String cleaned = version.trim().replaceFirst("^[vV]", "");
        String[] parts = cleaned.split("[.-]");
        int[] out = new int[Math.max(1, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            try { out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits); }
            catch (Exception ignored) { out[i] = 0; }
        }
        return out;
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
