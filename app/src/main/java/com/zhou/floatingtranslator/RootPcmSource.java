package com.zhou.floatingtranslator;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Fixed privileged PCM bridge. The caller explicitly chooses ROOT or Shizuku; this class never
 * switches permission source, card/device, sample rate or channel count automatically.
 */
public final class RootPcmSource implements AutoCloseable {
    public interface Callback {
        void onStatus(String message);
        void onPcm(byte[] pcm16Mono16k, int length, int peak);
        void onError(String message);
    }

    private static final int TARGET_RATE = 16000;
    private static final long STALL_MS = 5000L;
    private final Context context;
    private final String transport;
    private final int card;
    private final int device;
    private final int sourceRate;
    private final int channels;
    private final Callback callback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private volatile Process captureProcess;
    private volatile File streamFile;
    private double resampleCarry;

    public RootPcmSource(Context context, int card, int device, int sourceRate, int channels,
                         Callback callback) {
        this(context, RootCallProfileStore.TRANSPORT_ROOT, card, device, sourceRate, channels, callback);
    }

    public RootPcmSource(Context context, String transport, int card, int device,
                         int sourceRate, int channels, Callback callback) {
        this.context = context.getApplicationContext();
        this.transport = RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)
            ? RootCallProfileStore.TRANSPORT_SHIZUKU : RootCallProfileStore.TRANSPORT_ROOT;
        this.card = Math.max(0, card);
        this.device = Math.max(0, device);
        this.sourceRate = sourceRate <= 0 ? 48000 : sourceRate;
        this.channels = channels <= 1 ? 1 : 2;
        this.callback = callback;
    }

    public void start() {
        if (running) return;
        running = true;
        worker.execute(() -> {
            if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) runShizukuLoop();
            else runRootLoop();
        });
    }

    private void runRootLoop() {
        File dir = new File(context.getCacheDir(), "root-pcm");
        if (!dir.exists() && !dir.mkdirs()) {
            fail("无法创建 ROOT 音频缓存目录");
            return;
        }
        File wav = new File(dir, "stream-" + System.currentTimeMillis() + ".wav");
        streamFile = wav;
        try {
            String rootId = runRoot("id", 5);
            if (!rootId.contains("uid=0")) throw new IllegalStateException("没有获得 ROOT(uid=0)");
            String tinycap = findTinycapRoot();
            if (tinycap.isEmpty()) throw new IllegalStateException("系统没有找到 tinycap");

            postStatus("ROOT PCM：启动 card " + card + " / device " + device
                + " · " + sourceRate + "Hz · " + channels + "ch");
            String command = "rm -f " + q(wav.getAbsolutePath()) + "; exec " + q(tinycap) + " "
                + q(wav.getAbsolutePath()) + " -D " + card + " -d " + device
                + " -c " + channels + " -r " + sourceRate + " -b 16 -p 1024 -n 4"
                + " >/dev/null 2>&1";
            Process process = new ProcessBuilder("su", "-c", command).start();
            captureProcess = process;

            long deadline = System.currentTimeMillis() + 4000L;
            while (running && (!wav.isFile() || wav.length() < 44) && System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) break;
                sleep(80);
            }
            if (!running) return;
            if (!process.isAlive() && wav.length() < 44) {
                throw new IllegalStateException("tinycap 启动失败；当前 ALSA 参数可能不支持");
            }
            if (wav.length() < 44) throw new IllegalStateException("tinycap 没有产生 WAV 数据");

            try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
                long position = findWaveDataOffset(raf);
                int frameBytes = channels * 2;
                byte[] source = new byte[96 * 1024];
                resampleCarry = 0d;
                long lastProgress = System.currentTimeMillis();
                long lastLength = raf.length();
                postStatus("ROOT PCM：正在接收内部音频");

                while (running) {
                    long currentLength = raf.length();
                    if (currentLength > lastLength) {
                        lastLength = currentLength;
                        lastProgress = System.currentTimeMillis();
                    }
                    long available = currentLength - position;
                    int readable = (int) Math.min(source.length, available);
                    readable -= readable % frameBytes;
                    if (readable < frameBytes) {
                        if (!process.isAlive()) {
                            throw new IllegalStateException("tinycap 已退出，exit=" + process.exitValue()
                                + "；请换 PCM/采样率/声道");
                        }
                        if (System.currentTimeMillis() - lastProgress > STALL_MS) {
                            throw new IllegalStateException("tinycap 仍在运行，但 5 秒没有新的 PCM 数据；请换 PCM 参数");
                        }
                        sleep(70);
                        continue;
                    }
                    raf.seek(position);
                    int read = raf.read(source, 0, readable);
                    if (read <= 0) { sleep(50); continue; }
                    read -= read % frameBytes;
                    position += read;
                    lastProgress = System.currentTimeMillis();
                    byte[] converted = convertTo16kMono(source, read);
                    if (converted.length > 0) callback.onPcm(converted, converted.length, peak(converted));
                }
            }
        } catch (Exception e) {
            if (running) fail(safe(e));
        } finally {
            cleanup();
        }
    }

    private void runShizukuLoop() {
        try {
            if (!ShizukuShell.isRunning()) throw new IllegalStateException("Shizuku 未运行");
            if (!ShizukuShell.hasPermission()) throw new IllegalStateException("浮译没有 Shizuku 授权");
            String tinycap = findTinycapShizuku();
            if (tinycap.isEmpty()) throw new IllegalStateException("Shizuku 环境没有找到 tinycap");

            postStatus("Shizuku PCM：启动 card " + card + " / device " + device
                + " · " + sourceRate + "Hz · " + channels + "ch");
            String script =
                "f=/data/local/tmp/floatingtranslator_pcm_$$.wav; e=$f.err; rm -f \"$f\" \"$e\"; "
                + q(tinycap) + " \"$f\" -D " + card + " -d " + device + " -c " + channels
                + " -r " + sourceRate + " -b 16 -p 1024 -n 4 >/dev/null 2>\"$e\" & cap=$!; "
                + "trap 'kill $cap 2>/dev/null; kill $tailpid 2>/dev/null; rm -f \"$f\" \"$e\"' EXIT INT TERM; "
                + "i=0; while [ ! -s \"$f\" ] && kill -0 $cap 2>/dev/null && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done; "
                + "if [ ! -s \"$f\" ]; then cat \"$e\" >&2; exit 41; fi; "
                + "tail -c +45 -f \"$f\" & tailpid=$!; wait $cap; rc=$?; kill $tailpid 2>/dev/null; "
                + "cat \"$e\" >&2; exit $rc";

            ShizukuRemoteProcess process = ShizukuShell.startProcess(script);
            captureProcess = process;
            InputStream in = process.getInputStream();
            int frameBytes = channels * 2;
            byte[] source = new byte[96 * 1024 + 8];
            int carry = 0;
            resampleCarry = 0d;
            long started = System.currentTimeMillis();
            long lastData = started;
            boolean announced = false;

            while (running) {
                int available = in.available();
                if (available <= 0) {
                    if (!process.alive()) {
                        String err = readError(process);
                        throw new IllegalStateException("Shizuku tinycap 已退出，exit=" + process.exitValue()
                            + (err.isEmpty() ? "" : " · " + err));
                    }
                    if (System.currentTimeMillis() - lastData > STALL_MS
                        && System.currentTimeMillis() - started > STALL_MS) {
                        throw new IllegalStateException("Shizuku tinycap 5 秒没有 PCM 数据；shell/SELinux 可能无权读取这个设备");
                    }
                    sleep(40);
                    continue;
                }
                int max = Math.min(source.length - carry, available);
                int read = in.read(source, carry, max);
                if (read < 0) break;
                if (read == 0) { sleep(20); continue; }
                int total = carry + read;
                int usable = total - (total % frameBytes);
                carry = total - usable;
                if (usable > 0) {
                    if (!announced) {
                        announced = true;
                        postStatus("Shizuku PCM：正在接收内部音频");
                    }
                    lastData = System.currentTimeMillis();
                    byte[] converted = convertTo16kMono(source, usable);
                    if (converted.length > 0) callback.onPcm(converted, converted.length, peak(converted));
                }
                if (carry > 0) System.arraycopy(source, usable, source, 0, carry);
            }
        } catch (Exception e) {
            if (running) fail(safe(e));
        } finally {
            cleanup();
        }
    }

    private byte[] convertTo16kMono(byte[] input, int length) {
        int frameBytes = channels * 2;
        int frames = length / frameBytes;
        if (frames <= 0) return new byte[0];
        double step = sourceRate / (double) TARGET_RATE;
        if (step <= 0d) step = 1d;
        int estimated = Math.max(1, (int) Math.ceil((frames + 1) / step));
        byte[] out = new byte[estimated * 2];
        int outSamples = 0;
        double pos = resampleCarry;
        while (pos < frames) {
            int frame = Math.min(frames - 1, (int) pos);
            int base = frame * frameBytes;
            int mixed = 0;
            for (int c = 0; c < channels; c++) {
                int i = base + c * 2;
                int sample = (short) ((input[i] & 0xff) | (input[i + 1] << 8));
                mixed += sample;
            }
            short mono = (short) (mixed / channels);
            if (outSamples * 2 + 1 >= out.length) out = Arrays.copyOf(out, out.length + 8192);
            out[outSamples * 2] = (byte) (mono & 0xff);
            out[outSamples * 2 + 1] = (byte) ((mono >> 8) & 0xff);
            outSamples++;
            pos += step;
        }
        resampleCarry = pos - frames;
        return Arrays.copyOf(out, outSamples * 2);
    }

    private int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int abs = Math.abs(value);
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    private long findWaveDataOffset(RandomAccessFile raf) throws Exception {
        raf.seek(0);
        byte[] header = new byte[12];
        raf.readFully(header);
        if (!ascii(header, 0, "RIFF") || !ascii(header, 8, "WAVE")) {
            throw new IllegalStateException("tinycap 输出不是标准 WAV");
        }
        long p = 12;
        while (p + 8 <= Math.min(raf.length(), 4096)) {
            raf.seek(p);
            byte[] chunk = new byte[8];
            raf.readFully(chunk);
            long size = (chunk[4] & 0xffL) | ((chunk[5] & 0xffL) << 8)
                | ((chunk[6] & 0xffL) << 16) | ((chunk[7] & 0xffL) << 24);
            if (ascii(chunk, 0, "data")) return p + 8;
            p += 8 + size + (size & 1L);
        }
        if (raf.length() >= 44) return 44;
        throw new IllegalStateException("找不到 WAV data 区");
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private String findTinycapRoot() throws Exception {
        return firstLine(runRoot(
            "for p in /vendor/bin/tinycap /system/bin/tinycap /system/xbin/tinycap; do "
                + "[ -x \"$p\" ] && { echo \"$p\"; exit 0; }; done; command -v tinycap 2>/dev/null || true", 5));
    }

    private String findTinycapShizuku() throws Exception {
        return firstLine(ShizukuShell.runText(
            "for p in /vendor/bin/tinycap /system/bin/tinycap /system/xbin/tinycap; do "
                + "[ -x \"$p\" ] && { echo \"$p\"; exit 0; }; done; command -v tinycap 2>/dev/null || true",
            5, 4096));
    }

    private String firstLine(String value) {
        String out = value == null ? "" : value.trim();
        int nl = out.indexOf('\n');
        return nl >= 0 ? out.substring(0, nl).trim() : out;
    }

    private String runRoot(String command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ROOT 命令超时");
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String readError(Process process) {
        try {
            InputStream err = process.getErrorStream();
            int available = err.available();
            if (available <= 0) return "";
            byte[] data = err.readNBytes(Math.min(available, 4096));
            return new String(data, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) { return ""; }
    }

    private void postStatus(String message) {
        if (running) callback.onStatus(message);
    }

    private void fail(String message) {
        running = false;
        callback.onError(message);
    }

    private void cleanup() {
        running = false;
        stopCaptureProcess();
        File file = streamFile;
        streamFile = null;
        if (file != null) try { file.delete(); } catch (Exception ignored) {}
    }

    private void stopCaptureProcess() {
        Process process = captureProcess;
        captureProcess = null;
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            try {
                if (!process.waitFor(700, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Exception ignored) {}
        }
        if (RootCallProfileStore.TRANSPORT_ROOT.equals(transport)) {
            File f = streamFile;
            if (f != null) {
                try {
                    String name = f.getName().replace("'", "");
                    new ProcessBuilder("su", "-c", "pkill -f 'tinycap.*" + name + "' 2>/dev/null || true")
                        .start().waitFor(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
        }
    }

    private String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String safe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override public void close() {
        running = false;
        stopCaptureProcess();
        worker.shutdownNow();
    }
}
