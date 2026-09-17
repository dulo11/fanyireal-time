package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
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

    private File partialArchive(String modelId) {
        return new File(baseDir(), modelId + ".tar.bz2.part");
    }

    private File readyArchive(String modelId) {
        return new File(baseDir(), modelId + ".tar.bz2.ready");
    }

    public boolean isInstalled(String modelId) {
        File dir = modelDir(modelId);
        return dir.isDirectory() && new File(dir, ".ready").isFile();
    }

    public long installedBytes(String modelId) {
        return folderSize(modelDir(modelId));
    }

    /** Complete archive retained only when extraction/installation has not yet succeeded. */
    public long cachedArchiveBytes(String modelId) {
        File file = readyArchive(modelId);
        return file.isFile() ? file.length() : 0L;
    }

    /** Incomplete resumable network download for one model. */
    public long partialArchiveBytes(String modelId) {
        File file = partialArchive(modelId);
        return file.isFile() ? file.length() : 0L;
    }

    public long availableBytes() {
        try {
            File dir = baseDir();
            if (!dir.exists()) dir = context.getFilesDir();
            return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    /** Counts only completed model folders containing .ready; caches are excluded. */
    public long allInstalledBytes() {
        File[] files = baseDir().listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isDirectory() && new File(file, ".ready").isFile()) total += folderSize(file);
        }
        return total;
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

    public long completedDownloadCacheBytes() {
        File[] files = baseDir().listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".tar.bz2.ready")) total += file.length();
        }
        return total;
    }

    public long allDownloadCacheBytes() {
        return partialDownloadBytes() + completedDownloadCacheBytes();
    }

    /** Backward-compatible: removes incomplete network/extraction .part entries only. */
    public boolean clearPartialDownloads() {
        File[] files = baseDir().listFiles();
        if (files == null) return true;
        boolean ok = true;
        for (File file : files) {
            if (file.getName().endsWith(".part")) ok &= deleteRecursively(file);
        }
        return ok;
    }

    /** Explicit cache cleanup: removes resumable partial downloads and complete-but-uninstalled archives. */
    public boolean clearDownloadCaches() {
        File[] files = baseDir().listFiles();
        if (files == null) return true;
        boolean ok = true;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".part") || name.endsWith(".tar.bz2.ready")) {
                ok &= deleteRecursively(file);
            }
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
            File partial = partialArchive(model.id);
            File ready = readyArchive(model.id);

            try {
                // A stale extraction directory is disposable. The complete archive is not.
                deleteRecursively(staging);
                if (!staging.mkdirs()) throw new IllegalStateException("无法创建临时目录");

                if (!ready.isFile() || ready.length() <= 0L) {
                    if (ready.exists()) deleteRecursively(ready);
                    long existing = partial.isFile() ? partial.length() : 0L;
                    postStatus(callback, existing > 0
                        ? "发现未完成下载 " + human(existing) + "，正在断点续传 " + model.name + "……"
                        : "正在下载 " + model.name + "（" + model.approximateSize + "，支持断点续传）……");

                    downloadFileWithRetry(model.url, partial, callback);
                    if (closed) return;
                    if (!partial.isFile() || partial.length() <= 0L) {
                        throw new IllegalStateException("下载完成但缓存文件为空");
                    }
                    if (ready.exists() && !deleteRecursively(ready)) {
                        throw new IllegalStateException("无法替换旧的完整下载缓存");
                    }
                    if (!partial.renameTo(ready)) {
                        throw new IllegalStateException("下载完成，但无法保存完整下载缓存");
                    }
                    postStatus(callback, "✅ 下载完成，完整安装包已缓存 " + human(ready.length()));
                } else {
                    postStatus(callback, "发现已完整下载的 " + model.name + " 安装包 " + human(ready.length())
                        + "，本次直接重新解压，不重复下载");
                }

                if (closed) return;
                long free = availableBytes();
                postStatus(callback, "正在解压 " + model.name + "……完整下载包会保留到安装成功"
                    + (free > 0 ? "；当前可用空间 " + human(free) : ""));

                extractTarBz2(ready, staging);
                File payload = choosePayloadRoot(staging, model.archiveRootHint);
                if (!containsOnnx(payload)) throw new IllegalStateException("模型包里没有找到 ONNX 文件");
                if (closed) throw new IllegalStateException("安装已取消");

                // Publish .ready only after the complete extracted directory has been moved.
                File previous = new File(base, model.id + ".previous");
                if (previous.exists() && !deleteRecursively(previous)) {
                    throw new IllegalStateException("无法清理旧备份");
                }
                boolean hadPrevious = target.exists();
                if (hadPrevious && !target.renameTo(previous)) {
                    throw new IllegalStateException("无法备份已有模型");
                }
                try {
                    if (!staging.renameTo(target)) throw new IllegalStateException("无法完成模型安装");
                    try (OutputStream out = new FileOutputStream(new File(target, ".ready"))) {
                        out.write((model.id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    deleteRecursively(previous);
                } catch (Exception installError) {
                    deleteRecursively(target);
                    if (hadPrevious && !previous.renameTo(target)) {
                        throw new IllegalStateException("安装失败，旧模型保留在备份目录", installError);
                    }
                    throw installError;
                }

                // Only a verified successful installation is allowed to remove the downloaded archive.
                if (ready.exists() && !ready.delete()) {
                    postStatus(callback, "模型已安装；完整下载缓存未能自动删除，可在模型中心手动清理");
                }
                if (partial.exists()) partial.delete();
                main.post(() -> { if (!closed) callback.onSuccess(target); });
            } catch (Exception e) {
                deleteRecursively(staging);
                String tail;
                if (ready.isFile() && ready.length() > 0L) {
                    tail = "；完整下载包已保留 " + human(ready.length())
                        + "，再次点下载会直接重新解压，不会重复下载";
                } else if (partial.isFile() && partial.length() > 0L) {
                    tail = "；未完成下载已保留 " + human(partial.length()) + "，重新点下载会断点续传";
                } else {
                    tail = "";
                }
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
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
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
            connection.setRequestProperty("User-Agent", "FloatingTranslator/" + BuildConfig.VERSION_NAME + " Android");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

            int code = connection.getResponseCode();
            if (code == 416 && existing > 0) {
                if (!output.delete()) throw new IllegalStateException("无法重置损坏的断点文件");
                downloadFile(address, output, callback);
                return;
            }
            if (code < 200 || code >= 300) throw new IllegalStateException("模型下载 HTTP " + code);

            boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + existing + "-")) {
                    throw new IllegalStateException("下载源返回了错误的断点位置，请清理下载缓存后重试");
                }
            }
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
                        main.post(() -> { if (!closed) callback.onProgress(p, d, t); });
                    }
                }
                if (closed) throw new IllegalStateException("下载已取消");
                if (total > 0 && done != total) {
                    throw new java.io.EOFException("下载未完成，正在保留断点");
                }
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
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("解压已取消");
                }
                if (entry instanceof TarArchiveEntry) {
                    TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
                    if (!tarEntry.isFile() && !tarEntry.isDirectory()) {
                        throw new SecurityException("模型包包含不支持的链接或特殊文件");
                    }
                }
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
                        if (closed) throw new IllegalStateException("解压已取消");
                        if (n > 0) os.write(buffer, 0, n);
                    }
                }
            }
        } catch (java.io.IOException e) {
            String message = safe(e);
            if (message.toLowerCase(Locale.ROOT).contains("no space left")) {
                throw new IllegalStateException("手机存储空间不足，完整下载包已保留；清理空间后再次点击即可重新解压", e);
            }
            throw e;
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

    public static String human(long bytes) {
        if (bytes < 0L) return "未知";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private void postStatus(Callback callback, String message) {
        main.post(() -> { if (!closed) callback.onStatus(message); });
    }

    private void postError(Callback callback, String message) {
        main.post(() -> { if (!closed) callback.onError(message); });
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
