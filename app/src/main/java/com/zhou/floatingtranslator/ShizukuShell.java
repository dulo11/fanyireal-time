package com.zhou.floatingtranslator;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/** Small Shizuku v13 bridge used only by the explicit Shizuku PCM mode. */
public final class ShizukuShell {
    public static final int REQUEST_PERMISSION = 6203;

    private ShizukuShell() {}

    public static boolean isRunning() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable ignored) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return isRunning() && !Shizuku.isPreV11()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requestPermission() {
        if (!isRunning()) throw new IllegalStateException("Shizuku 未运行");
        if (Shizuku.isPreV11()) throw new IllegalStateException("Shizuku 版本过旧");
        Shizuku.requestPermission(REQUEST_PERMISSION);
    }

    public static String describe() {
        try {
            if (!isRunning()) return "Shizuku 未运行";
            int uid = Shizuku.getUid();
            String ctx = Shizuku.getSELinuxContext();
            return "Shizuku v" + Shizuku.getVersion() + " · uid=" + uid
                + (ctx == null || ctx.isEmpty() ? "" : " · " + ctx);
        } catch (Throwable e) {
            return "Shizuku 状态读取失败：" + safe(e);
        }
    }

    /**
     * Shizuku API 13 keeps newProcess as a private transition API. We call it reflectively so
     * this experimental mode can stream tinycap without bundling a second privileged service.
     * If a future API removes it, this method fails visibly and ROOT/microphone modes are unaffected.
     */
    public static ShizukuRemoteProcess startProcess(String command) throws Exception {
        ensureReady();
        try {
            Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            Object result = method.invoke(null,
                new Object[]{new String[]{"sh", "-c", command}, null, null});
            if (!(result instanceof ShizukuRemoteProcess)) {
                throw new IllegalStateException("Shizuku 没有返回远程进程");
            }
            return (ShizukuRemoteProcess) result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException(cause == null ? "Shizuku 进程启动失败" : safe(cause));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("当前 Shizuku API 不支持命令进程；请更新浮译适配", e);
        }
    }

    public static String runText(String command, int timeoutSeconds, int maxChars) throws Exception {
        ShizukuRemoteProcess process = startProcess("(" + command + ") 2>&1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (out.size() < maxChars) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, maxChars - out.size()));
                    if (read < 0) break;
                    if (read > 0) out.write(buffer, 0, read);
                }
            } catch (Exception ignored) {}
        }, "shizuku-text-reader");
        reader.start();
        if (!process.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)) {
            try { process.destroy(); } catch (Throwable ignored) {}
            throw new IllegalStateException("Shizuku 命令超时");
        }
        try { reader.join(1200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void ensureReady() {
        if (!isRunning()) throw new IllegalStateException("Shizuku 未运行；先在 Shizuku App 中启动服务");
        if (Shizuku.isPreV11()) throw new IllegalStateException("Shizuku 版本过旧");
        if (!hasPermission()) throw new IllegalStateException("浮译没有 Shizuku 授权");
    }

    private static String safe(Throwable e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isEmpty()
            ? (e == null ? "未知错误" : e.getClass().getSimpleName()) : message;
    }
}
