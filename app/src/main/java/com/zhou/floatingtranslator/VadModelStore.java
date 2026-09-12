package com.zhou.floatingtranslator;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small official Silero VAD model cache used by high-accuracy ASR segmentation. */
public final class VadModelStore {
    public interface StatusCallback { void onStatus(String message); }

    private static final String URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";
    private static final long MIN_VALID_BYTES = 200_000L;

    private VadModelStore() {}

    public static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), "vad-models"), "silero_vad.onnx");
    }

    public static boolean isReady(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() >= MIN_VALID_BYTES;
    }

    /** Must be called off the main thread. Returns null if download fails. */
    public static File ensure(Context context, StatusCallback callback) {
        File target = modelFile(context);
        if (target.isFile() && target.length() >= MIN_VALID_BYTES) return target;
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return null;
        File part = new File(dir, "silero_vad.onnx.part");
        if (callback != null) callback.onStatus("首次启用高精度切句：正在准备 Silero VAD……");
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(URL).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(45000);
                connection.setRequestProperty("User-Agent", "FloatingTranslator/0.5.8 Android");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                try (InputStream in = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 128 * 1024)) {
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                }
                if (part.length() < MIN_VALID_BYTES) throw new IllegalStateException("VAD 模型文件过小");
                if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧 VAD 模型");
                if (!part.renameTo(target)) {
                    try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(part));
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        byte[] buffer = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                    }
                    part.delete();
                }
                if (callback != null) callback.onStatus("Silero VAD 已就绪");
                return target;
            } catch (Exception e) {
                if (attempt == 3 && callback != null) {
                    callback.onStatus("Silero VAD 暂不可用，将自动回退旧切句：" + safe(e));
                }
                try { Thread.sleep(900L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (part.exists()) part.delete();
        return null;
    }

    public static boolean delete(Context context) {
        File target = modelFile(context);
        File part = new File(target.getParentFile(), "silero_vad.onnx.part");
        boolean ok = !target.exists() || target.delete();
        ok &= !part.exists() || part.delete();
        return ok;
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
