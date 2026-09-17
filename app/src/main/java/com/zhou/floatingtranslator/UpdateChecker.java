package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight updater that checks this project's latest stable GitHub Release. */
public final class UpdateChecker implements AutoCloseable {
    public interface Callback {
        void onResult(String latestVersion, String pageUrl, String apkUrl, boolean newer);
        void onError(String message);
    }

    public static final String LATEST_PAGE = "https://github.com/dulo11/fanyireal-time/releases/latest";
    public static final String LATEST_APK = "https://github.com/dulo11/fanyireal-time/releases/latest/download/FloatingTranslator-latest.apk";
    private static final String API = "https://api.github.com/repos/dulo11/fanyireal-time/releases/latest";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public UpdateChecker(Context context) {}

    public void check(String currentVersion, Callback callback) {
        if (closed) return;
        worker.execute(() -> {
            Exception webError = null;
            Exception apiError = null;

            // Prefer github.com. Some mobile/roaming networks can open GitHub normally but block api.github.com.
            try {
                ReleaseInfo info = fetchFromLatestPage();
                postResult(currentVersion, callback, info);
                return;
            } catch (Exception e) {
                webError = e;
            }

            // Fall back to the REST API when the web release redirect is unavailable.
            try {
                ReleaseInfo info = fetchFromApi();
                postResult(currentVersion, callback, info);
                return;
            } catch (Exception e) {
                apiError = e;
            }

            String message = "GitHub 发布页和 API 都无法连接。发布页：" + safe(webError)
                + "；API：" + safe(apiError)
                + "。请检查当前网络/VPN/漫游后重试。";
            postError(callback, message);
        });
    }

    private ReleaseInfo fetchFromLatestPage() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_PAGE).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("User-Agent", userAgent());
            connection.setUseCaches(false);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) throw new IllegalStateException("GitHub 网页 HTTP " + code);

            String finalUrl = connection.getURL().toString();
            String latest = versionFromReleaseUrl(finalUrl);
            if (latest.isEmpty()) {
                String location = connection.getHeaderField("Location");
                latest = versionFromReleaseUrl(location);
                if (location != null && !location.isBlank()) finalUrl = new URL(new URL(LATEST_PAGE), location).toString();
            }
            if (latest.isEmpty()) throw new IllegalStateException("无法从 GitHub 最新发布页解析版本号");
            if (latest.contains("-")) throw new IllegalStateException("GitHub latest 指向测试版 " + latest);

            return new ReleaseInfo(latest, finalUrl, LATEST_APK);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private ReleaseInfo fetchFromApi() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", userAgent());
            connection.setUseCaches(false);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("GitHub API HTTP " + code);

            JSONObject json = new JSONObject(readUtf8(connection));
            String tag = json.optString("tag_name", "").trim();
            String latest = cleanTag(tag);
            if (latest.isEmpty()) throw new IllegalStateException("GitHub API 未返回版本号");
            if (json.optBoolean("prerelease") || latest.contains("-"))
                throw new IllegalStateException("最新发布被标记为测试版本");

            String page = json.optString("html_url", LATEST_PAGE);
            String assetUrl = "";
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if ("FloatingTranslator-latest.apk".equals(asset.optString("name"))) {
                        assetUrl = asset.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            if (assetUrl.isEmpty()) assetUrl = LATEST_APK;
            return new ReleaseInfo(latest, page, assetUrl);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void postResult(String currentVersion, Callback callback, ReleaseInfo info) {
        boolean newer = compareVersions(info.version, currentVersion) > 0;
        main.post(() -> {
            if (!closed) callback.onResult(info.version, info.pageUrl, info.apkUrl, newer);
        });
    }

    private void postError(Callback callback, String message) {
        main.post(() -> {
            if (!closed) callback.onError(message);
        });
    }

    private static String readUtf8(HttpURLConnection connection) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream())) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) bytes.write(buffer, 0, n);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String versionFromReleaseUrl(String url) {
        if (url == null || url.isBlank()) return "";
        final String marker = "/releases/tag/";
        int start = url.indexOf(marker);
        if (start < 0) return "";
        String tag = url.substring(start + marker.length());
        int end = tag.indexOf('?');
        if (end >= 0) tag = tag.substring(0, end);
        end = tag.indexOf('#');
        if (end >= 0) tag = tag.substring(0, end);
        end = tag.indexOf('/');
        if (end >= 0) tag = tag.substring(0, end);
        return cleanTag(tag);
    }

    private static String cleanTag(String tag) {
        if (tag == null) return "";
        String value = tag.trim();
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    private static String userAgent() {
        return "FloatingTranslator/" + BuildConfig.VERSION_NAME + " Android";
    }

    private static String safe(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }

    static int compareVersions(String a, String b) { return AppVersion.compare(a, b); }

    private static final class ReleaseInfo {
        final String version;
        final String pageUrl;
        final String apkUrl;

        ReleaseInfo(String version, String pageUrl, String apkUrl) {
            this.version = version;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
