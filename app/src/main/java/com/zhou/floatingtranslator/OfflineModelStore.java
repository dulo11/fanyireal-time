package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads, extracts, lists and removes optional sherpa-onnx model packs. */
public final class OfflineModelStore implements AutoCloseable {
    public interface Callback {
        void onStatus(String message);
        void onProgress(int percent, long downloaded, long total);
        void onSuccess(File modelDir);
        void onError(String message);
    }

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public OfflineModelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public File baseDir() {
        return new File(context.getFilesDir(), "sherpa-models");
    }

    public File modelDir(String modelId) {
        return new File(baseDir(), modelId);
    }

    public boolean isInstalled(String modelId) {
        File dir = modelDir(modelId);
        return dir.isDirectory() && new File(dir, ".ready").isFile();
    }

    public long installedBytes(String modelId) {
        return folderSize(modelDir(modelId));
    }

    public long allInstalledBytes() {
        return folderSize(baseDir());
    }

    public long partialDownloadBytes() {
        File[] files = baseDir().listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.getName().endsWith(".part")) total += folderSize(file);
        }
        return total;
    }

    public boolean clearPartialDownloads() {
        File[] files = baseDir().listFiles();
        if (files == null) return true;
        boolean ok = true;
        for (File file : files) {
            if (file.getName().endsWith(".part")) ok &= deleteRecursively(file);
        }
        return ok;
    }

    public void download(OfflineAsrModelCatalog.Model model, Callback callback) {
        if (model == null) {
            callback.onError("未知模型");
            return;
        }
        worker.execute(() -> {
            if (closed) return;
            File base = baseDir();
            if (!base.exists() && !base.mkdirs()) {
                postError(callback, "无法创建模型目录");
                return;
            }
            File target = modelDir(model.id);
            File staging = new File(base, model.id + ".part");
            File archive = new File(base, model.id + ".tar.bz2.part");
            boolean downloadCompleted = false;
            try {
                deleteRecursively(staging);
                if (!staging.mkdirs()) throw new IllegalStateException("无法创建临时目录");
                long existing = archive.isFile() ? archive.length() : 0L;
                postStatus(callback, existing > 0
                    ? "发现未完成下载 " + human(existing) + "，正在断点续传 " + model.name + "……"
                    : "正在下载 " + model.name + "（" + model.approximateSize + "，支持断点续传）……");
                downloadFileWithRetry(model.url, archive, callback);
                downloadCompleted = true;
                if (closed) return;
                postStatus(callback, "下载完成，正在解压 " + model.name + "……");
                extractTarBz2(archive, staging);
                File payload = choosePayloadRoot(staging, model.archiveRootHint);
                if (!containsOnnx(payload)) throw new IllegalStateException("模型包里没有找到 ONNX 文件");
                File marker = new File(staging, ".ready");
                try (OutputStream out = new FileOutputStream(marker)) {
                    out.write((model.id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                deleteRecursively(target);
                if (!staging.renameTo(target)) {
                    copyDirectory(staging, target);
                    deleteRecursively(staging);
                }
                if (!new File(target, ".ready").isFile()) {
                    try (OutputStream out = new FileOutputStream(new File(target, ".ready"))) {
                        out.write((model.id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                if (archive.exists()) archive.delete();
                main.post(() -> callback.onSuccess(target));
            } catch (Exception e) {
                deleteRecursively(staging);
                // 网络中断时保留已下载的压缩包，下次点击可 Range 续传。
                // 如果已经完整下载但解压失败，则删除压缩包，避免反复使用损坏文件。
                if (downloadCompleted && archive.exists()) archive.delete();
                String tail = (!downloadCompleted && archive.isFile() && archive.length() > 0)
                    ? "；已保留 " + human(archive.length()) + "，重新点下载会继续"
                    : "";
                postError(callback, safe(e) + tail);
            }
        });
    }

    public boolean delete(String modelId) {
        return deleteRecursively(modelDir(modelId));
    }

    public boolean deleteAll() {
        return deleteRecursively(baseDir());
    }

    private void downloadFileWithRetry(String address, File output, Callback callback) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS && !closed; attempt++) {
            try {
                downloadFile(address, output, callback);
                return;
            } catch (Exception e) {
                last = e;
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) break;
                int next = attempt + 1;
                postStatus(callback, "网络中断：" + safe(e) + "；2 秒后自动断点重试 " + next + "/" + MAX_DOWNLOAD_ATTEMPTS);
                try { Thread.sleep(2000L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("下载已取消");
                }
            }
        }
        if (closed) throw new IllegalStateException("下载已取消");
        throw last == null ? new IllegalStateException("模型下载失败") : last;
    }

    private void downloadFile(String address, File output, Callback callback) throws Exception {
        long existing = output.isFile() ? output.length() : 0L;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("User-Agent", "FloatingTranslator/0.5.1 Android");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = connection.getResponseCode();
            if (code == 416 && existing > 0) {
                // 服务器认为本地范围已超出文件长度，保险起见从头下载。
                if (!output.delete()) throw new IllegalStateException("无法重置损坏的断点文件");
                downloadFile(address, output, callback);
                return;
            }
            if (code < 200 || code >= 300) throw new IllegalStateException("模型下载 HTTP " + code);

            boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            long done = append ? existing : 0L;
            long responseLength = connection.getContentLengthLong();
            long total = responseLength > 0 ? done + responseLength : -1L;
            if (!append && existing > 0) {
                postStatus(callback, "下载源不支持断点续传，本次从头重新下载");
            }

            int lastPercent = -1;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(output, append), 128 * 1024)) {
                byte[] buffer = new byte[128 * 1024];
                int n;
                while (!closed && (n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) Math.min(100, done * 100L / total) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int p = percent;
                        final long d = done;
                        final long t = total;
                        main.post(() -> callback.onProgress(p, d, t));
                    }
                }
                if (closed) throw new IllegalStateException("下载已取消");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void extractTarBz2(File archive, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive), 128 * 1024);
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(file, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            ArchiveEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                File out = new File(destination, name);
                String canonical = out.getCanonicalPath();
                if (!canonical.equals(destination.getCanonicalPath()) && !canonical.startsWith(root)) {
                    throw new SecurityException("模型压缩包路径异常");
                }
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("无法创建目录 " + name);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("无法创建目录 " + parent.getName());
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                    int n;
                    while ((n = tar.read(buffer)) >= 0) {
                        if (n > 0) os.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static File choosePayloadRoot(File staging, String hint) {
        if (hint != null && !hint.isEmpty()) {
            File exact = findDirectory(staging, hint);
            if (exact != null) return exact;
        }
        File[] children = staging.listFiles(File::isDirectory);
        return children != null && children.length == 1 ? children[0] : staging;
    }

    public static File findPayloadRoot(File modelDir, String hint) {
        if (modelDir == null) return null;
        if (hint != null && !hint.isEmpty()) {
            File exact = findDirectory(modelDir, hint);
            if (exact != null) return exact;
        }
        File[] children = modelDir.listFiles(File::isDirectory);
        if (children != null && children.length == 1) return children[0];
        return modelDir;
    }

    private static File findDirectory(File root, String name) {
        if (root == null || !root.exists()) return null;
        if (root.isDirectory() && root.getName().equals(name)) return root;
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                File found = findDirectory(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsOnnx(File root) {
        return findFirst(root, f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".onnx")) != null;
    }

    public interface FileMatcher { boolean matches(File file); }

    public static File findFirst(File root, FileMatcher matcher) {
        if (root == null || !root.exists()) return null;
        if (matcher.matches(root)) return root;
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    File found = findFirst(child, matcher);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    public static long folderSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += folderSize(child);
        return total;
    }

    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) ok &= deleteRecursively(child);
        }
        return file.delete() && ok;
    }

    private static void copyDirectory(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("无法创建模型目录");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyDirectory(child, new File(target, child.getName()));
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建目录");
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private void postStatus(Callback callback, String message) {
        main.post(() -> callback.onStatus(message));
    }

    private void postError(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    private static String safe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
