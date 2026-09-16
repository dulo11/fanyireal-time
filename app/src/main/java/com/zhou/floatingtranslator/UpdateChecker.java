package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;

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
        if (closed) return;
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(API).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "FloatingTranslator/" + BuildConfig.VERSION_NAME + " Android");
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
                if (json.optBoolean("prerelease") || latest.contains("-"))
                    throw new IllegalStateException("最新发布被标记为测试版本，请到发布页选择正式版");
                String assetUrl = "";
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if ("FloatingTranslator-latest.apk".equals(asset.optString("name"))) {
                        assetUrl = asset.optString("browser_download_url"); break;
                    }
                }
                if (assetUrl.isEmpty()) throw new IllegalStateException("正式版安装包尚未就绪，请稍后重试");
                final String apkUrl = assetUrl;
                boolean newer = compareVersions(latest, currentVersion) > 0;
                main.post(() -> { if (!closed) callback.onResult(latest, page, apkUrl, newer); });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> { if (!closed) callback.onError(message); });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    static int compareVersions(String a, String b) { return AppVersion.compare(a, b); }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
