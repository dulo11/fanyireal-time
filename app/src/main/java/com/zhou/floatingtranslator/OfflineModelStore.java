package com.zhou.floatingtranslator;

import android.content.Context;
import android.net.Uri;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
        if (model.requiresIndividualFiles()) {
            downloadIndividualFiles(model, callback);
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

    /**
     * User-selected SAF document (.tar.bz2 or .zip) -> private model directory.
     * No upload or background network request. A known sherpa-onnx architecture
     * must be selected beforehand. Invalid or incomplete archives are rejected.
     */
    public void importLocalArchive(Uri document, String importedId, String templateId,
                                   String displayFilename, Callback callback) {
        if (document == null || !ImportedAsrModels.isImported(importedId)) {
            callback.onError("只允许导入新建的本地模型 ID");
            return;
        }
        OfflineAsrModelCatalog.Model template = OfflineAsrModelCatalog.find(templateId);
        if (template == null) {
            callback.onError("无法识别的模型结构；请先选择已支持的 sherpa-onnx 模型类型");
            return;
        }
        final String lower = displayFilename == null ? "" : displayFilename.toLowerCase(Locale.ROOT);
        final boolean tar = lower.endsWith(".tar.bz2");
        final boolean zip = lower.endsWith(".zip");
        if (!tar && !zip) {
            callback.onError("仅支持 .tar.bz2 或 .zip 格式的 sherpa-onnx 模型包");
            return;
        }
        worker.execute(() -> {
            File base = baseDir();
            File target = modelDir(importedId);
            File staging = new File(base, importedId + ".importing");
            File archive = new File(base, importedId + (tar ? ".incoming.tar.bz2" : ".incoming.zip"));
            try {
                if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("无法创建私有模型目录");
                if (target.exists()) throw new IllegalStateException("该自定义模型已经存在");
                if (staging.exists() && !deleteRecursively(staging)) {
                    throw new IllegalStateException("无法清理残留的导入临时目录");
                }
                if (!staging.mkdirs()) throw new IllegalStateException("无法创建临时文件目录");
                postStatus(callback, "正在从 Android 文件选择器读取本地模型，仅保存在本机，不上传。");
                long bytes = 0;
                try (InputStream input = new BufferedInputStream(
                        context.getContentResolver().openInputStream(document), 128 * 1024);
                     OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(archive), 128 * 1024)) {
                    byte[] block = new byte[128 * 1024];
                    int n;
                    while ((n = input.read(block)) != -1) {
                        if (closed) throw new IllegalStateException("本地导入已取消");
                        output.write(block, 0, n);
                        bytes += n;
                        if (bytes > MAX_LOCAL_IMPORT_BYTES) throw new IllegalStateException("文件超过 16 GiB 安全上限");
                    }
                }
                if (bytes == 0) throw new IllegalStateException("所选文件为空");
                long free = availableBytes();
                if (free > 0 && free < Math.min(bytes, 256L * 1024 * 1024)) {
                    throw new IllegalStateException("解压空间不足，请清理存储后重试");
                }
                postStatus(callback, "正在安全解压 " + human(bytes)
                    + "，检查模型文件格式及目录结构……");
                if (tar) extractTarBz2(archive, staging, true);
                else extractZip(archive, staging);
                File payload = findPayloadRoot(staging, "");
                validateImportedPackage(payload, templateId);
                if (!staging.renameTo(target)) throw new IllegalStateException("无法将模型保存到私有目录");
                try (OutputStream mark = new FileOutputStream(new File(target, ".ready"))) {
                    mark.write((importedId + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                postStatus(callback, "✅ 模型已安全导入本机，未调用网络 API。"
                    + "若 ONNX 格式不兼容原生库，将在使用时明确报错，不会自动切换模型。");
                main.post(() -> { if (!closed) callback.onSuccess(target); });
            } catch (Exception ex) {
                // Only delete the NEW model. Existing installed packs are untouched.
                deleteRecursively(staging);
                if (target.isDirectory() && !new File(target, ".ready").isFile()) deleteRecursively(target);
                postError(callback, "本地模型导入失败：" + safe(ex));
            } finally {
                if (archive.exists()) archive.delete();
            }
        });
    }

    private static final long MAX_LOCAL_IMPORT_BYTES = 16L * 1024 * 1024 * 1024;
    private static final int MAX_LOCAL_IMPORT_FILES = 15000;

    private void extractZip(File archive, File destination) throws Exception {
        String base = destination.getCanonicalPath();
        String prefix = base + File.separator;
        long written = 0;
        int files = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(archive), 128 * 1024))) {
            ZipEntry entry;
            byte[] block = new byte[128 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                if (closed) throw new IllegalStateException("已取消");
                if (++files > MAX_LOCAL_IMPORT_FILES) throw new SecurityException("压缩包文件数超出上限");
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) continue;
                File outputFile = new File(destination, name);
                String canonical = outputFile.getCanonicalPath();
                if (!canonical.startsWith(prefix)) throw new SecurityException("ZIP 路径越界");
                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw new IllegalStateException("无法创建模型子目录");
                    }
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IllegalStateException("无法创建模型文件目录");
                    }
                    try (OutputStream output = new BufferedOutputStream(
                            new FileOutputStream(outputFile), 128 * 1024)) {
                        int n;
                        while ((n = zin.read(block)) != -1) {
                            if (closed) throw new IllegalStateException("已取消");
                            written += n;
                            if (written > MAX_LOCAL_IMPORT_BYTES) {
                                throw new SecurityException("解压数据超出 16 GiB 限额");
                            }
                            output.write(block, 0, n);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    /** Signature check for known sherpa-onnx file layouts, not a claim that
     * arbitrary imported weights are trustworthy or compatible with the ABI. */
    static void validateImportedPackage(File root, String templateId) {
        if (root == null || !root.isDirectory()) throw new IllegalStateException("模型目录为空");
        if (!containsOnnx(root)) throw new IllegalStateException("缺少 ONNX/ORT 模型文件");
        switch (templateId) {
            case OfflineAsrModelCatalog.SENSEVOICE:
            case OfflineAsrModelCatalog.PARAKEET_JA:
            case OfflineAsrModelCatalog.OMNILINGUAL:
                requireName(root, "tokens.txt");
                requireOne(root, "model.onnx", "model.int8.onnx");
                break;
            case OfflineAsrModelCatalog.WHISPER_SMALL:
            case OfflineAsrModelCatalog.WHISPER_MEDIUM:
            case OfflineAsrModelCatalog.WHISPER_TURBO:
            case OfflineAsrModelCatalog.WHISPER_LARGE_V3:
                requireName(root, "tokens.txt");
                requireOne(root, "encoder.onnx", "encoder.int8.onnx");
                requireOne(root, "decoder.onnx", "decoder.int8.onnx");
                break;
            case OfflineAsrModelCatalog.QWEN3_ASR:
            case OfflineAsrModelCatalog.QWEN3_17B:
                requireName(root, "conv_frontend.onnx");
                requireOne(root, "encoder.int8.onnx", "encoder.onnx");
                requireOne(root, "decoder.int8.onnx", "decoder.onnx");
                requireName(root, "tokenizer");
                break;
            case OfflineAsrModelCatalog.REAZON_JA:
            case OfflineAsrModelCatalog.PARAKEET_EN_V2:
            case OfflineAsrModelCatalog.ZIPFORMER_STREAM_EN:
                requireContains(root, "encoder");
                requireContains(root, "decoder");
                requireContains(root, "joiner");
                requireName(root, "tokens.txt");
                break;
            case OfflineAsrModelCatalog.FUNASR_NANO:
                requireContains(root, "encoder_adaptor");
                requireContains(root, "embedding");
                requireContains(root, "llm");
                requireName(root, "Qwen3-0.6B");
                break;
            case OfflineAsrModelCatalog.MOONSHINE_TINY_EN:
            case OfflineAsrModelCatalog.MOONSHINE_BASE_EN:
                requireName(root, "preprocess.onnx");
                requireContains(root, "uncached_decode");
                requireContains(root, "cached_decode");
                requireContains(root, "encode");
                requireName(root, "tokens.txt");
                break;
            case OfflineAsrModelCatalog.MOONSHINE_V2_EN:
            case OfflineAsrModelCatalog.MOONSHINE_V2_JA:
            case OfflineAsrModelCatalog.MOONSHINE_V2_VI:
                requireName(root, "encoder_model.ort");
                requireName(root, "decoder_model_merged.ort");
                requireName(root, "tokens.txt");
                break;
            default:
                throw new IllegalArgumentException("该模型类型暂不允许导入");
        }
    }

    private static void requireName(File root, String name) {
        if (findFirst(root, f -> f.getName().equalsIgnoreCase(name)) == null) {
            throw new IllegalStateException("模型包缺少文件或目录：" + name);
        }
    }

    private static void requireOne(File root, String a, String b) {
        if (findFirst(root, f -> f.isFile()
                && (f.getName().equalsIgnoreCase(a) || f.getName().equalsIgnoreCase(b))) == null) {
            throw new IllegalStateException("模型包缺少 " + a + " / " + b);
        }
    }

    private static void requireContains(File root, String term) {
        if (findFirst(root, f -> f.isFile()
                && f.getName().toLowerCase(Locale.ROOT).contains(term)) == null) {
            throw new IllegalStateException("模型包缺少 " + term + " 模块");
        }
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

    /**
     * Qwen3 1.7B's verified community conversion is published as individual
     * Hugging Face LFS files, not a tar archive. Download each file into a
     * resumable .part directory, verify nonempty payloads, then atomically
     * publish .ready. No partial pack will ever be treated as installed.
     */
    private void downloadIndividualFiles(OfflineAsrModelCatalog.Model model, Callback callback) {
        worker.execute(() -> {
            final File base = baseDir();
            if (!base.exists() && !base.mkdirs()) {
                postError(callback, "无法创建模型目录");
                return;
            }
            final File target = modelDir(model.id);
            final File staging = new File(base, model.id + ".part");
            final String[] filenames = {
                "conv_frontend.onnx",
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "tokenizer/merges.txt",
                "tokenizer/vocab.json",
                "tokenizer/tokenizer_config.json"
            };
            try {
                if (!staging.exists() && !staging.mkdirs()) {
                    throw new IllegalStateException("无法创建多文件下载缓存目录");
                }
                postStatus(callback, "Qwen3-ASR 1.7B 使用第三方 INT8 ONNX 转换，"
                    + "需下载 2 GB 以上模型；下载后会以自有标记完整安装。"
                    + "模型来自 Hugging Face，请先确认可接受第三方来源。");
                for (int i = 0; i < filenames.length; i++) {
                    if (closed) throw new IllegalStateException("下载已取消");
                    String name = filenames[i];
                    File ready = new File(staging, name);
                    File partial = new File(staging, name + ".part");
                    File parent = ready.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IllegalStateException("无法创建文件目录：" + name);
                    }
                    if (ready.isFile() && ready.length() > 0) {
                        postStatus(callback, "复用已下载文件 " + (i + 1) + "/"
                            + filenames.length + "：" + name);
                        continue;
                    }
                    postStatus(callback, "下载 Qwen3 1.7B 文件 " + (i + 1) + "/"
                        + filenames.length + "：" + name + "（支持断点续传）");
                    downloadFileWithRetry(model.url + name, partial, callback);
                    if (closed) throw new IllegalStateException("下载已取消");
                    if (!partial.isFile() || partial.length() <= 0) {
                        throw new IllegalStateException("下载到的模型文件为空：" + name);
                    }
                    if (!partial.renameTo(ready)) {
                        throw new IllegalStateException("下载完成但无法保存：" + name);
                    }
                }
                if (!containsOnnx(staging)) {
                    throw new IllegalStateException("完整包没有 ONNX 模型文件");
                }
                File backup = new File(base, model.id + ".previous");
                if (backup.exists() && !deleteRecursively(backup)) {
                    throw new IllegalStateException("无法清除旧模型备份");
                }
                boolean hadOld = target.exists();
                if (hadOld && !target.renameTo(backup)) {
                    throw new IllegalStateException("无法备份旧模型");
                }
                try {
                    if (!staging.renameTo(target)) throw new IllegalStateException("模型目录安装失败");
                    try (OutputStream marker = new FileOutputStream(new File(target, ".ready"))) {
                        marker.write((model.id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    deleteRecursively(backup);
                    postStatus(callback, "✅ Qwen3 1.7B 第三方 INT8 模型完整安装。"
                        + "若模型初始化失败，请检查内存和第三方导出兼容性。");
                    main.post(() -> { if (!closed) callback.onSuccess(target); });
                } catch (Exception publishing) {
                    deleteRecursively(target);
                    if (hadOld) backup.renameTo(target);
                    throw publishing;
                }
            } catch (Exception e) {
                postError(callback, "Qwen3 1.7B 下载/安装失败：" + safe(e)
                    + "；分文件缓存保留，可重试。");
            }
        });
    }

    private void extractTarBz2(File archive, File destination) throws Exception {
        extractTarBz2(archive, destination, false);
    }

    private void extractTarBz2(File archive, File destination, boolean restricted) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        int count = 0;
        long extracted = 0;
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive), 128 * 1024);
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(file, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            ArchiveEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = tar.getNextEntry()) != null) {
                if (restricted && ++count > MAX_LOCAL_IMPORT_FILES)
                    throw new SecurityException("模型包文件数超出安全限制");
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
                        if (n > 0) {
                            if (restricted) {
                                extracted += n;
                                if (extracted > MAX_LOCAL_IMPORT_BYTES)
                                    throw new SecurityException("模型解压数据超过安全限制");
                            }
                            os.write(buffer, 0, n);
                        }
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
        // Moonshine v2 native ONNX Runtime models ship in optimized .ort files.
        return findFirst(root, f -> {
            if (!f.isFile()) return false;
            String filename = f.getName().toLowerCase(Locale.ROOT);
            return filename.endsWith(".onnx") || filename.endsWith(".ort");
        }) != null;
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
