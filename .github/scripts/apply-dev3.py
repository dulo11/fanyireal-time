from pathlib import Path

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected exactly 1 occurrence, got {count}: {old[:100]!r}')
    write(path, text.replace(old, new, 1))


def replace_all(path, old, new, minimum=1):
    text = read(path)
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f'{path}: expected at least {minimum} occurrences, got {count}: {old[:100]!r}')
    write(path, text.replace(old, new))


def replace_between(path, start, end, replacement):
    text = read(path)
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f'{path}: start marker not found: {start!r}')
    j = text.find(end, i)
    if j < 0:
        raise RuntimeError(f'{path}: end marker not found: {end!r}')
    write(path, text[:i] + replacement + text[j:])


# ---- version + Shizuku dependencies ----
replace_once('app/build.gradle.kts', 'versionCode = 21', 'versionCode = 22')
replace_once('app/build.gradle.kts', 'versionName = "0.6.0-dev2"', 'versionName = "0.6.0-dev3"')
replace_once(
    'app/build.gradle.kts',
    '    implementation("org.apache.commons:commons-compress:1.27.1")\n',
    '    implementation("org.apache.commons:commons-compress:1.27.1")\n\n'
    '    // Optional non-root privileged PCM experiment. Shizuku runs with adb-shell identity.\n'
    '    implementation("dev.rikka.shizuku:api:13.1.5")\n'
    '    implementation("dev.rikka.shizuku:provider:13.1.5")\n'
)

replace_once(
    'app/src/main/AndroidManifest.xml',
    '        <service\n            android:name=".TranslationService"',
    '        <provider\n'
    '            android:name="rikka.shizuku.ShizukuProvider"\n'
    '            android:authorities="${applicationId}.shizuku"\n'
    '            android:enabled="true"\n'
    '            android:exported="true"\n'
    '            android:multiprocess="false"\n'
    '            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />\n\n'
    '        <service\n            android:name=".TranslationService"'
)

# ---- API configuration copy: plain local storage, no crypto wording ----
api = 'app/src/main/java/com/zhou/floatingtranslator/ApiSettingsActivity.java'
replace_once(api, ' · 翻译引擎与安全', ' · 翻译引擎与 API')
replace_once(api, '翻译引擎 / API 安全中心', '翻译引擎 / API 配置')
replace_once(api, '所有 Key 使用 Android Keystore + AES/GCM 加密。', '所有 Key 仅普通保存在本机 SharedPreferences，不做额外加密。')
replace_once(api, 'Key 只保存在本机加密配置中。', 'Key 只保存在本机普通配置中。')
replace_once(api, 'root.addView(section("安全中心"));', 'root.addView(section("本机 API 配置"));')
replace_once(api,
    '"这里不会显示或导出完整密钥。把 APK 发给别人，不会把你后来在手机里填写的 Key 一起打包出去。",',
    '"API Key 只保存在当前手机，不会被写进 APK；当前使用普通本地保存，不做额外加密。",')
replace_once(api, '会同时删除这套账号在本机加密保存的 Key / Region。', '会同时删除这套账号在本机保存的 Key / Region。')
replace_once(api, '✅ 已加密保存。Azure 账号池：', '✅ 已保存到本机。Azure 账号池：')
replace_all(api, 'API 安全状态：', 'API 配置状态：', minimum=2)
replace_once(api, '（内容已隐藏）', '（输入框默认隐藏密钥）')

# ---- profile store: ROOT/Shizuku transport is explicit and fixed ----
write('app/src/main/java/com/zhou/floatingtranslator/RootCallProfileStore.java', r'''package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores one fixed ALSA/tinycap capture profile per calling app package. */
public final class RootCallProfileStore {
    public static final String TRANSPORT_ROOT = "root";
    public static final String TRANSPORT_SHIZUKU = "shizuku";

    private static final String PREFS = "floating_translator";
    private static final String SELECTED = "root_call_selected_package";
    private static final String PREFIX = "root_call_profile.";

    private RootCallProfileStore() {}

    public static final class Profile {
        public final String packageName;
        public final String label;
        public final String transport;
        public final int card;
        public final int device;
        public final int rate;
        public final int channels;

        public Profile(String packageName, String label, String transport,
                       int card, int device, int rate, int channels) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.label = label == null ? "" : label.trim();
            this.transport = TRANSPORT_SHIZUKU.equals(transport) ? TRANSPORT_SHIZUKU : TRANSPORT_ROOT;
            this.card = Math.max(0, card);
            this.device = Math.max(0, device);
            this.rate = rate <= 0 ? 48000 : rate;
            this.channels = channels <= 1 ? 1 : 2;
        }

        /** Backward-compatible constructor for older callers/profiles: ROOT is the default. */
        public Profile(String packageName, String label, int card, int device, int rate, int channels) {
            this(packageName, label, TRANSPORT_ROOT, card, device, rate, channels);
        }

        public String sourceLabel() {
            return TRANSPORT_SHIZUKU.equals(transport) ? "Shizuku" : "ROOT";
        }

        public String summary() {
            String name = label.isEmpty() ? packageName : label + "（" + packageName + "）";
            return name + "\n固定来源=" + sourceLabel() + " · ALSA card=" + card + " device=" + device
                + " · " + rate + "Hz · " + channels + "ch";
        }
    }

    public static void save(Context context, Profile profile) {
        if (profile == null || profile.packageName.isEmpty()) return;
        String base = PREFIX + profile.packageName + ".";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SELECTED, profile.packageName)
            .putString(base + "label", profile.label)
            .putString(base + "transport", profile.transport)
            .putInt(base + "card", profile.card)
            .putInt(base + "device", profile.device)
            .putInt(base + "rate", profile.rate)
            .putInt(base + "channels", profile.channels)
            .putBoolean(base + "ready", true)
            .apply();
    }

    public static Profile loadSelected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pkg = prefs.getString(SELECTED, "");
        return load(context, pkg);
    }

    public static Profile load(Context context, String packageName) {
        String pkg = packageName == null ? "" : packageName.trim();
        if (pkg.isEmpty()) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String base = PREFIX + pkg + ".";
        if (!prefs.getBoolean(base + "ready", false)) return null;
        return new Profile(pkg,
            prefs.getString(base + "label", ""),
            prefs.getString(base + "transport", TRANSPORT_ROOT),
            prefs.getInt(base + "card", 0),
            prefs.getInt(base + "device", 0),
            prefs.getInt(base + "rate", 48000),
            prefs.getInt(base + "channels", 2));
    }

    public static String selectedPackage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED, "");
    }

    public static void select(Context context, String packageName) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SELECTED, packageName == null ? "" : packageName.trim()).apply();
    }

    public static boolean hasSelected(Context context) {
        return loadSelected(context) != null;
    }
}
''')

# ---- Shizuku shell bridge ----
write('app/src/main/java/com/zhou/floatingtranslator/ShizukuShell.java', r'''package com.zhou.floatingtranslator;

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
''')

# ---- microphone processing modes ----
write('app/src/main/java/com/zhou/floatingtranslator/MicAudioEffects.java', r'''package com.zhou.floatingtranslator;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/** Optional audio preprocessors for the explicit microphone call mode. */
public final class MicAudioEffects implements AutoCloseable {
    public static final String MODE_REMOTE = "remote";
    public static final String MODE_NEAR = "near";
    public static final String MODE_RAW = "raw";

    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;
    private final String mode;
    private boolean aecEnabled;
    private boolean nsEnabled;
    private boolean agcEnabled;

    private MicAudioEffects(String mode) {
        this.mode = normalize(mode);
    }

    public static String normalize(String value) {
        if (MODE_NEAR.equals(value) || MODE_RAW.equals(value)) return value;
        return MODE_REMOTE;
    }

    public static int recommendedAudioSource(String mode) {
        // VOICE_COMMUNICATION can engage vendor echo cancellation before our recorder sees PCM.
        // That is useful for near-end speech, but can erase the far-end speaker audio we want to translate.
        return MODE_NEAR.equals(normalize(mode))
            ? android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            : android.media.MediaRecorder.AudioSource.MIC;
    }

    public static MicAudioEffects attach(AudioRecord record, String mode) {
        MicAudioEffects effects = new MicAudioEffects(mode);
        if (record == null || MODE_RAW.equals(effects.mode)) return effects;
        int session = record.getAudioSessionId();

        // Remote-speaker mode deliberately leaves AEC off: AEC is designed to remove speaker playback.
        if (MODE_NEAR.equals(effects.mode) && AcousticEchoCanceler.isAvailable()) {
            try {
                effects.aec = AcousticEchoCanceler.create(session);
                if (effects.aec != null) {
                    effects.aec.setEnabled(true);
                    effects.aecEnabled = effects.aec.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        if (NoiseSuppressor.isAvailable()) {
            try {
                effects.ns = NoiseSuppressor.create(session);
                if (effects.ns != null) {
                    effects.ns.setEnabled(true);
                    effects.nsEnabled = effects.ns.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        if (AutomaticGainControl.isAvailable()) {
            try {
                effects.agc = AutomaticGainControl.create(session);
                if (effects.agc != null) {
                    effects.agc.setEnabled(true);
                    effects.agcEnabled = effects.agc.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        return effects;
    }

    public String status() {
        if (MODE_RAW.equals(mode)) return "原始麦克风：AEC/NS/AGC 关闭";
        if (MODE_NEAR.equals(mode)) {
            return "近端优先：AEC" + mark(aecEnabled) + " NS" + mark(nsEnabled) + " AGC" + mark(agcEnabled);
        }
        return "远端外放优先：AEC关 NS" + mark(nsEnabled) + " AGC" + mark(agcEnabled);
    }

    private String mark(boolean enabled) { return enabled ? "✓" : "×"; }

    @Override public void close() {
        if (aec != null) { try { aec.release(); } catch (Throwable ignored) {} aec = null; }
        if (ns != null) { try { ns.release(); } catch (Throwable ignored) {} ns = null; }
        if (agc != null) { try { agc.release(); } catch (Throwable ignored) {} agc = null; }
    }
}
''')

# ---- ROOT PCM bridge becomes fixed ROOT or fixed Shizuku + stalled stream watchdog ----
write('app/src/main/java/com/zhou/floatingtranslator/RootPcmSource.java', r'''package com.zhou.floatingtranslator;

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
''')

# ---- RootCallActivity: explicit fixed ROOT/Shizuku source ----
rc = 'app/src/main/java/com/zhou/floatingtranslator/RootCallActivity.java'
replace_once(rc, 'import android.content.SharedPreferences;\n', 'import android.content.SharedPreferences;\nimport android.content.pm.PackageManager;\n')
replace_once(rc, 'import java.util.regex.Pattern;\n', 'import java.util.regex.Pattern;\n\nimport rikka.shizuku.Shizuku;\n')
replace_once(rc, '/** ROOT VoIP/call compatibility center. No system audio policy files are modified. */',
             '/** Fixed ROOT/Shizuku VoIP/call compatibility center. No system audio policy files are modified. */')
replace_once(rc, '    private Spinner appSpinner;\n', '    private Spinner transportSpinner;\n    private Spinner appSpinner;\n')
replace_once(rc, '    private volatile long testBytes;\n',
'''    private volatile long testBytes;\n\n    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {\n        if (requestCode != ShizukuShell.REQUEST_PERMISSION) return;\n        if (grantResult == PackageManager.PERMISSION_GRANTED) {\n            toast("Shizuku 已授权，开始扫描");\n            main.postDelayed(this::scanPrivilegedAudio, 180L);\n        } else {\n            rootStatus.setText("❌ Shizuku 授权被拒绝；不会自动切换到 ROOT 或麦克风");\n        }\n    };\n''')
replace_once(rc,
'''    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · ROOT 通话");\n        setContentView(buildUi());\n    }\n''',
'''    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);\n        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · 通话内部声音");\n        setContentView(buildUi());\n    }\n''')
replace_once(rc, 'TextView title = text("ROOT 通话 / VoIP 翻译", 28, Color.WHITE);',
             'TextView title = text("通话内部声音 / VoIP 翻译", 28, Color.WHITE);')
replace_between(rc,
    '        TextView intro = text(\n',
    '        LinearLayout detectCard = card(root);\n',
'''        TextView intro = text(\n            "这里提供两个固定内部声音后端：ROOT（uid=0，兼容性最高）和 Shizuku（ADB shell，免 ROOT 实验）。" +\n            "两者都按 App 保存 ALSA card/device/采样率/声道，不会失败后偷偷切换权限来源或 PCM。\\n\\n" +\n            "Shizuku 是否能读通话 PCM 取决于 ROM、shell 权限和 SELinux；失败时请手动改用 ROOT 或外放+麦克风。",\n            14, Color.rgb(210, 200, 225));\n        intro.setPadding(0, dp(7), 0, dp(13));\n        root.addView(intro);\n\n''')
replace_once(rc,
'''        LinearLayout detectCard = card(root);\n        detectCard.addView(section("① ROOT / 音频设备扫描"));\n        scanButton = button("扫描 ROOT、tinycap、ALSA、音频状态");\n        scanButton.setOnClickListener(v -> scanRootAudio());\n''',
'''        LinearLayout detectCard = card(root);\n        detectCard.addView(section("① 固定权限来源 / 音频设备扫描"));\n        transportSpinner = new Spinner(this);\n        transportSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,\n            new String[]{"固定 ROOT｜uid=0", "固定 Shizuku｜ADB shell（实验）"}));\n        RootCallProfileStore.Profile savedTransport = RootCallProfileStore.loadSelected(this);\n        if (savedTransport != null && RootCallProfileStore.TRANSPORT_SHIZUKU.equals(savedTransport.transport)) {\n            transportSpinner.setSelection(1);\n        }\n        transportSpinner.setBackgroundColor(Color.rgb(51, 45, 73));\n        detectCard.addView(transportSpinner, params());\n        scanButton = button("扫描当前固定模式：tinycap / ALSA / 音频状态");\n        scanButton.setOnClickListener(v -> scanPrivilegedAudio());\n''')
replace_once(rc, 'pcmCard.addView(section("③ 选择并测试 ROOT PCM"));',
             'pcmCard.addView(section("③ 选择并测试固定内部 PCM"));')
replace_once(rc, 'Button save = primaryButton("保存为这个 App 的 ROOT 通话配置");',
             'Button save = primaryButton("保存为这个 App 的固定内部声音配置");')
replace_once(rc, 'toast("请先保存一个 ROOT 通话配置");', 'toast("请先保存一个 ROOT / Shizuku 通话配置");')
replace_once(rc, 'Button mic = button("兼容备用：外放 + 麦克风通话翻译");',
             'Button mic = button("免 ROOT/Shizuku：外放 + 麦克风增强模式");')
replace_once(rc, 'Button copy = button("复制 ROOT 完整诊断");', 'Button copy = button("复制完整内部声音诊断");')
replace_between(rc,
    '    private void scanRootAudio() {\n',
    '    private ArrayList<PcmCandidate> parseCandidates(String raw) {\n',
'''    private void scanPrivilegedAudio() {\n        String transport = selectedTransport();\n        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {\n            if (!ShizukuShell.isRunning()) {\n                rootStatus.setText("❌ Shizuku 未运行。先打开 Shizuku 并通过无线调试/ADB 启动服务。\\n不会自动切换到 ROOT。");\n                return;\n            }\n            if (!ShizukuShell.hasPermission()) {\n                rootStatus.setText("正在请求 Shizuku 授权……");\n                try { ShizukuShell.requestPermission(); }\n                catch (Exception e) { rootStatus.setText("❌ " + safe(e)); }\n                return;\n            }\n        }\n\n        scanButton.setEnabled(false);\n        rootStatus.setText(RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)\n            ? "正在用固定 Shizuku(shell) 扫描……"\n            : "正在请求固定 ROOT 并扫描……如果 KernelSU/Magisk 弹授权，请允许浮译。");\n        worker.execute(() -> {\n            String command =\n                "echo PRIV_ID; id; " +\n                "echo SELINUX; getenforce 2>/dev/null || true; " +\n                "echo TINYCAP; for p in /vendor/bin/tinycap /system/bin/tinycap /system/xbin/tinycap; do [ -x \\\"$p\\\" ] && echo $p; done; command -v tinycap 2>/dev/null || true; " +\n                "echo ALSA_CARDS; cat /proc/asound/cards 2>/dev/null || true; " +\n                "echo ALSA_PCM; cat /proc/asound/pcm 2>/dev/null || true; " +\n                "echo ACTIVITY; dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity|topResumedActivity' | head -12 || true; " +\n                "echo AUDIO_STATE; dumpsys audio 2>/dev/null | head -180 || true; " +\n                "echo PACKAGES; pm list packages 2>/dev/null || true";\n            String raw;\n            try {\n                raw = runPrivileged(transport, command, 10, 80000);\n            } catch (Exception e) {\n                raw = "ERROR: " + safe(e);\n            }\n            final boolean accessOk = RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)\n                ? (raw.contains("uid=2000") || raw.contains("uid=0"))\n                : raw.contains("uid=0");\n            final boolean tinycap = raw.contains("/tinycap") || raw.matches("(?s).*TINYCAP\\\\s+tinycap.*");\n            final ArrayList<PcmCandidate> found = parseCandidates(raw);\n            final String previous = findPreviousExternalPackage(raw);\n            final Set<String> installed = installedPackages(raw);\n            final String summary = makeSummary(transport, accessOk, tinycap, found, previous, installed);\n            lastReport = summary + "\\n\\n===== 原始诊断 =====\\n" + raw;\n            detectedPreviousPackage = previous;\n            runOnUiThread(() -> {\n                candidates.clear();\n                candidates.addAll(found);\n                pcmAdapter.notifyDataSetChanged();\n                if (!previous.isEmpty() && appSpinner.getSelectedItemPosition() == 0) packageInput.setText(previous);\n                rootStatus.setText(summary);\n                scanButton.setEnabled(true);\n            });\n        });\n    }\n\n''')
replace_once(rc,
    'RootPcmSource source = new RootPcmSource(this, pcm.card, pcm.device, rate, channels,',
    'RootPcmSource source = new RootPcmSource(this, selectedTransport(), pcm.card, pcm.device, rate, channels,')
replace_once(rc,
'''        RootCallProfileStore.Profile profile = new RootCallProfileStore.Profile(\n            pkg, label, pcm.card, pcm.device, selectedRate(), selectedChannels());\n''',
'''        RootCallProfileStore.Profile profile = new RootCallProfileStore.Profile(\n            pkg, label, selectedTransport(), pcm.card, pcm.device, selectedRate(), selectedChannels());\n''')
replace_once(rc, 'savedStatus.setText("✅ 已保存并设为当前 ROOT 通话来源\\n" + profile.summary());',
             'savedStatus.setText("✅ 已保存并设为当前固定通话来源\\n" + profile.summary());')
replace_once(rc, 'toast("ROOT 通话配置已保存");', 'toast(profile.sourceLabel() + " 通话配置已保存");')
replace_once(rc, 'return p == null ? "当前没有保存 ROOT 通话配置" : "当前 ROOT 配置：\\n" + p.summary();',
             'return p == null ? "当前没有保存内部通话配置" : "当前固定配置：\\n" + p.summary();')
replace_between(rc,
    '    private String makeSummary(boolean rootOk, boolean tinycap, ArrayList<PcmCandidate> found,\n',
    '    private String findPreviousExternalPackage(String raw) {\n',
'''    private String makeSummary(String transport, boolean accessOk, boolean tinycap, ArrayList<PcmCandidate> found,\n                               String previous, Set<String> installed) {\n        String source = RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport) ? "Shizuku" : "ROOT";\n        StringBuilder out = new StringBuilder();\n        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {\n            out.append(accessOk ? "✅ Shizuku：可用（shell/root 服务身份）" : "❌ Shizuku：没有取得可用 shell 身份").append('\\n');\n            out.append(ShizukuShell.describe()).append('\\n');\n        } else {\n            out.append(accessOk ? "✅ ROOT：可用" : "❌ ROOT：没有拿到 uid=0").append('\\n');\n        }\n        out.append(tinycap ? "✅ tinycap：已找到" : "❌ tinycap：未找到").append('\\n');\n        out.append(found.isEmpty() ? "⚠ ALSA：没有解析到 capture PCM" : "✅ ALSA capture：" + found.size() + " 个候选").append('\\n');\n        if (!previous.isEmpty()) out.append("上一个前台 App：").append(appLabel(previous)).append(" · ").append(previous).append('\\n');\n        ArrayList<String> known = new ArrayList<>();\n        for (CommonApp app : COMMON_APPS) if (!app.packageName.isEmpty() && installed.contains(app.packageName)) known.add(app.label);\n        if (!known.isEmpty()) out.append("检测到常见通话 App：").append(String.join("、", known)).append('\\n');\n        if (accessOk && tinycap && !found.isEmpty()) {\n            out.append("\\n下一步：保持通话有声音，逐个做 5 秒测试。当前固定权限来源=").append(source)\n                .append("；失败不会自动切换。 ");\n        }\n        return out.toString().trim();\n    }\n\n''')
replace_once(rc,
'''    private String runRoot(String command, int timeoutSeconds, int maxChars) throws Exception {\n''',
'''    private String selectedTransport() {\n        return transportSpinner != null && transportSpinner.getSelectedItemPosition() == 1\n            ? RootCallProfileStore.TRANSPORT_SHIZUKU : RootCallProfileStore.TRANSPORT_ROOT;\n    }\n\n    private String runPrivileged(String transport, String command, int timeoutSeconds, int maxChars) throws Exception {\n        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {\n            return ShizukuShell.runText(command, timeoutSeconds, maxChars);\n        }\n        return runRoot(command, timeoutSeconds, maxChars);\n    }\n\n    private String runRoot(String command, int timeoutSeconds, int maxChars) throws Exception {\n''')
replace_once(rc, 'if (lastReport.isEmpty()) { toast("请先执行 ROOT 扫描"); return; }',
             'if (lastReport.isEmpty()) { toast("请先执行 ROOT / Shizuku 扫描"); return; }')
replace_once(rc, 'clipboard.setPrimaryClip(ClipData.newPlainText("浮译 ROOT 通话诊断", lastReport));',
             'clipboard.setPrimaryClip(ClipData.newPlainText("浮译通话内部声音诊断", lastReport));')
replace_once(rc,
'''    @Override protected void onDestroy() {\n        RootPcmSource current = testCapture;\n''',
'''    @Override protected void onDestroy() {\n        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}\n        RootPcmSource current = testCapture;\n''')
replace_once(rc,
'''        TextView warning = text(\n            "测试时要让目标 App 正在通话并持续有人说话。某个 PCM 有明显电平才值得保存。ROOT 能绕过一部分普通 Android 录音限制，" +\n            "但厂商 HAL/SELinux/硬件路由仍可能让部分 App 的内部音频不可见。录音或翻译通话请遵守所在地法律并尊重通话参与者隐私。",\n''',
'''        TextView warning = text(\n            "测试时要让目标 App 正在通话并持续有人说话。ROOT 成功率通常高于 Shizuku；Shizuku 只有在 shell/SELinux 允许读取目标 PCM 时才会接近 ROOT。" +\n            "两种内部模式都可能被厂商 HAL/硬件路由限制。录音或翻译通话请遵守所在地法律并尊重通话参与者隐私。",\n''')

# ---- Main console: internal mode is ROOT/Shizuku + explicit microphone processing ----
main = 'app/src/main/java/com/zhou/floatingtranslator/MainActivity.java'
replace_once(main, '    private Spinner inputModeSpinner;\n', '    private Spinner inputModeSpinner;\n    private Spinner micProcessingSpinner;\n')
replace_once(main, 'Button rootLab = secondaryButton("☎ ROOT 通话 / VoIP 兼容中心");',
             'Button rootLab = secondaryButton("☎ 通话内部声音兼容中心｜ROOT / Shizuku");')
replace_once(main,
'''                "系统内部声音｜直播/视频",\n                "麦克风｜通话外放兼容",\n                "ROOT 内部通话/VoIP｜按 App 保存 PCM"\n''',
'''                "系统内部声音｜直播/视频",\n                "麦克风｜通话外放兼容",\n                "ROOT / Shizuku 内部通话/VoIP｜按 App 保存 PCM"\n''')
replace_once(main,
'''        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));\n        audioCard.addView(inputModeSpinner, params());\n\n        audioCard.addView(label("ASR 语音识别"));\n''',
'''        inputModeSpinner.setBackgroundColor(Color.rgb(51, 45, 73));\n        audioCard.addView(inputModeSpinner, params());\n\n        audioCard.addView(label("麦克风通话处理｜仅麦克风 + 本地/有道 PCM ASR"));\n        micProcessingSpinner = new Spinner(this);\n        micProcessingSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,\n            new String[]{\n                "远端外放优先｜NS + AGC，AEC关闭（推荐）",\n                "近端说话优先｜AEC + NS + AGC",\n                "原始麦克风｜AEC/NS/AGC 全关闭"\n            }));\n        micProcessingSpinner.setSelection(micProcessingIndex(preferences.getString(\n            "mic_processing", MicAudioEffects.MODE_REMOTE)));\n        micProcessingSpinner.setBackgroundColor(Color.rgb(51, 45, 73));\n        audioCard.addView(micProcessingSpinner, params());\n        TextView micTip = text(\n            "翻译对方外放声音时不要开 AEC：AEC 的用途正是消除扬声器回声，可能把对方声音一起削掉。系统 SpeechRecognizer 自己占用麦克风，不受这里的 AEC/NS/AGC 开关控制。",\n            12, Color.rgb(184, 174, 207));\n        audioCard.addView(micTip);\n\n        audioCard.addView(label("ASR 语音识别"));\n''')
replace_between(main,
    '        TextView rootTip = text(\n',
    '        LinearLayout languageCard = card(root);\n',
'''        TextView rootTip = text(\n            "内部通话模式：在兼容中心明确选择固定 ROOT 或固定 Shizuku，再扫描/测试 PCM 并按 App 保存。" +\n            "Shizuku 是免 ROOT 实验方案，受 shell/SELinux 限制；失败不会自动切到 ROOT 或麦克风。",\n            12, Color.rgb(184, 174, 207));\n        rootTip.setPadding(0, dp(6), 0, 0);\n        audioCard.addView(rootTip);\n\n''')
replace_once(main, 'status = text("v0.5.3：重点加入 ROOT 通话/VoIP 通用 PCM 后端。", 13,',
             'status = text("v0.6.0-dev3：Shizuku 实验 PCM + 麦克风通话处理。", 13,')
replace_once(main, 'boolean root = mode == 2;', 'boolean root = mode == 2;')
replace_once(main, 'toast("ROOT 模式还没有保存 PCM 配置，先去 ROOT 兼容中心");',
             'toast("内部通话模式还没有保存 PCM 配置，先去 ROOT / Shizuku 兼容中心");')
replace_once(main, 'toast("系统 SpeechRecognizer 只能使用麦克风；内部声音/ROOT 请选本地 ASR 或有道 ASR");',
             'toast("系统 SpeechRecognizer 只能使用麦克风；系统内部声音/ROOT/Shizuku 请选本地 ASR 或有道 ASR");')
replace_once(main, 'if (enableOcr.isChecked()) toast("ROOT 通话模式暂不使用 OCR，已只启动通话声音翻译");',
             'if (enableOcr.isChecked()) toast("ROOT / Shizuku 通话模式暂不使用 OCR，已只启动通话声音翻译");')
replace_once(main, 'status.setText("正在启动 ROOT 通话翻译；保持目标 App 通话即可。");',
'''RootCallProfileStore.Profile p = RootCallProfileStore.loadSelected(this);\n            status.setText("正在启动 " + (p == null ? "内部" : p.sourceLabel()) + " 通话翻译；保持目标 App 通话即可。");''')
replace_once(main,
'''            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())\n            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());\n''',
'''            .putExtra(TranslationService.EXTRA_PREFER_OFFLINE, preferOffline.isChecked())\n            .putExtra(TranslationService.EXTRA_MIC_PROCESSING, selectedMicProcessing())\n            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());\n''')
replace_once(main,
'''            .putBoolean("prefer_offline", preferOffline.isChecked())\n            .putBoolean("enable_ocr", enableOcr.isChecked())\n''',
'''            .putBoolean("prefer_offline", preferOffline.isChecked())\n            .putString("mic_processing", selectedMicProcessing())\n            .putBoolean("enable_ocr", enableOcr.isChecked())\n''')
replace_between(main,
    '    private void refreshSummary() {\n',
    '    private void updateRecent() {\n',
'''    private void refreshSummary() {\n        if (summary == null) return;\n        int mode = inputModeSpinner == null ? preferences.getInt("input_mode", 0) : inputModeSpinner.getSelectedItemPosition();\n        RootCallProfileStore.Profile profile = RootCallProfileStore.loadSelected(this);\n        String source = mode == 2 ? ((profile == null ? "ROOT/Shizuku" : profile.sourceLabel()) + " 通话/VoIP")\n            : mode == 1 ? "麦克风" : "系统内部声音";\n        String internal = profile == null ? "" : "\\n内部 App：" + profile.packageName + " · " + profile.sourceLabel();\n        String mic = mode == 1 ? "\\n麦克风处理：" + micProcessingLabel(preferences.getString(\n            "mic_processing", MicAudioEffects.MODE_REMOTE)) : "";\n        summary.setText("声音：" + source + "\\nASR：" + asrLabel(preferences.getString("asr_mode", TranslationService.ASR_AUTO))\n            + "\\n历史：" + HistoryStore.count(this) + " 条" + internal + mic);\n    }\n\n''')
replace_once(main,
'''    private int languageModeIndex(String mode) {\n''',
'''    private int micProcessingIndex(String mode) {\n        String normalized = MicAudioEffects.normalize(mode);\n        if (MicAudioEffects.MODE_NEAR.equals(normalized)) return 1;\n        if (MicAudioEffects.MODE_RAW.equals(normalized)) return 2;\n        return 0;\n    }\n\n    private String selectedMicProcessing() {\n        if (micProcessingSpinner == null) return MicAudioEffects.MODE_REMOTE;\n        switch (micProcessingSpinner.getSelectedItemPosition()) {\n            case 1: return MicAudioEffects.MODE_NEAR;\n            case 2: return MicAudioEffects.MODE_RAW;\n            default: return MicAudioEffects.MODE_REMOTE;\n        }\n    }\n\n    private String micProcessingLabel(String mode) {\n        String normalized = MicAudioEffects.normalize(mode);\n        if (MicAudioEffects.MODE_NEAR.equals(normalized)) return "近端优先 AEC+NS+AGC";\n        if (MicAudioEffects.MODE_RAW.equals(normalized)) return "原始麦克风";\n        return "远端外放优先 NS+AGC/AEC关";\n    }\n\n    private int languageModeIndex(String mode) {\n''')
replace_once(main,
'''        if (inputModeSpinner != null) inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));\n        refreshSummary();\n''',
'''        if (inputModeSpinner != null) inputModeSpinner.setSelection(Math.min(2, preferences.getInt("input_mode", 0)));\n        if (micProcessingSpinner != null) micProcessingSpinner.setSelection(micProcessingIndex(\n            preferences.getString("mic_processing", MicAudioEffects.MODE_REMOTE)));\n        refreshSummary();\n''')

# ---- TranslationService: manual microphone processing, no hidden source switching ----
ts = 'app/src/main/java/com/zhou/floatingtranslator/TranslationService.java'
replace_once(ts,
'''    public static final String EXTRA_AUTO_MIC_FALLBACK = "auto_mic_fallback"; // compatibility; ignored\n''',
'''    public static final String EXTRA_AUTO_MIC_FALLBACK = "auto_mic_fallback"; // compatibility; ignored\n    public static final String EXTRA_MIC_PROCESSING = "mic_processing";\n''')
replace_once(ts, '    private AudioRecord audioRecord;\n', '    private AudioRecord audioRecord;\n    private MicAudioEffects micEffects;\n')
replace_once(ts, '    private boolean preferOffline;\n', '    private boolean preferOffline;\n    private String micProcessing = MicAudioEffects.MODE_REMOTE;\n')
replace_once(ts,
'''        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, true);\n        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);\n''',
'''        preferOffline = intent.getBooleanExtra(EXTRA_PREFER_OFFLINE, true);\n        micProcessing = MicAudioEffects.normalize(intent.getStringExtra(EXTRA_MIC_PROCESSING));\n        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);\n''')
replace_once(ts,
'''            } else {\n                record = new AudioRecord.Builder()\n                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)\n                    .setAudioFormat(format)\n                    .setBufferSizeInBytes(min * 2)\n                    .build();\n            }\n''',
'''            } else {\n                record = new AudioRecord.Builder()\n                    .setAudioSource(MicAudioEffects.recommendedAudioSource(micProcessing))\n                    .setAudioFormat(format)\n                    .setBufferSizeInBytes(min * 2)\n                    .build();\n            }\n''')
replace_once(ts,
'''            audioRecord = record;\n            audioCaptureStarted = true;\n            int generation = ++captureGeneration;\n            record.startRecording();\n            setDiagAudio(fixedSourceLabel() + " · 采集已启动");\n''',
'''            audioRecord = record;\n            closeMicEffects();\n            if (INPUT_MICROPHONE.equals(inputMode)) micEffects = MicAudioEffects.attach(record, micProcessing);\n            audioCaptureStarted = true;\n            int generation = ++captureGeneration;\n            record.startRecording();\n            String processing = micEffects == null ? "" : " · " + micEffects.status();\n            setDiagAudio(fixedSourceLabel() + " · 采集已启动" + processing);\n''')
replace_once(ts,
'''    private void stopAudioCapture() {\n        captureGeneration++;\n        audioCaptureStarted = false;\n        AudioRecord record = audioRecord;\n''',
'''    private void closeMicEffects() {\n        MicAudioEffects effects = micEffects;\n        micEffects = null;\n        if (effects != null) try { effects.close(); } catch (Exception ignored) {}\n    }\n\n    private void stopAudioCapture() {\n        captureGeneration++;\n        audioCaptureStarted = false;\n        closeMicEffects();\n        AudioRecord record = audioRecord;\n''')
replace_once(ts, '.setContentTitle("浮译 0.5.0")', '.setContentTitle("浮译 " + BuildConfig.VERSION_NAME)')
replace_once(ts, ' * FloatingTranslator 0.5.0 real-time pipeline.', ' * FloatingTranslator real-time pipeline.')

# ---- RootCallTranslationService: transport-aware diagnostics/source ----
rts = 'app/src/main/java/com/zhou/floatingtranslator/RootCallTranslationService.java'
replace_once(rts, ' * Foreground translation pipeline dedicated to ROOT ALSA/tinycap VoIP audio.',
             ' * Foreground translation pipeline dedicated to fixed ROOT/Shizuku ALSA/tinycap VoIP audio.')
replace_once(rts, 'private volatile String diagAudio = "ROOT PCM 未启动";', 'private volatile String diagAudio = "内部 PCM 未启动";')
replace_once(rts, 'CHANNEL_ID, "ROOT 通话翻译", NotificationManager.IMPORTANCE_LOW);',
             'CHANNEL_ID, "内部通话翻译", NotificationManager.IMPORTANCE_LOW);')
replace_once(rts, 'startForeground(NOTIFICATION_ID, notification("准备 ROOT 通话翻译"),',
             'startForeground(NOTIFICATION_ID, notification("准备 " + profile.sourceLabel() + " 通话翻译"),')
replace_once(rts, 'diagAudio = "ROOT · " + profile.packageName + " · 等待 PCM";',
             'diagAudio = profile.sourceLabel() + " · " + profile.packageName + " · 等待 PCM";')
replace_once(rts, 'showStatus("ROOT 通话来源：" + profile.packageName + "\\n正在准备 " + asrLabel(asrMode));',
             'showStatus(profile.sourceLabel() + " 通话来源：" + profile.packageName + "\\n正在准备 " + asrLabel(asrMode));')
replace_all(rts, ' · ROOT PCM', ' · 内部 PCM', minimum=2)
replace_once(rts, 'diagAsr = "ASR：系统 SpeechRecognizer 不能直接接 ROOT PCM";',
             'diagAsr = "ASR：系统 SpeechRecognizer 不能直接接内部 PCM";')
replace_once(rts, 'showStatus("ROOT 通话来源不能使用系统 SpeechRecognizer，请选 Vosk / sherpa / 有道 ASR。");',
             'showStatus("ROOT/Shizuku 内部 PCM 不能使用系统 SpeechRecognizer，请选 Vosk / sherpa / 有道 ASR。");')
replace_between(rts,
    '    private void startRootSource(RootCallProfileStore.Profile profile) {\n',
    '    private void consumePcm(byte[] pcm, int length, int peak) {\n',
'''    private void startRootSource(RootCallProfileStore.Profile profile) {\n        if (!running || rootSource != null) return;\n        rootSource = new RootPcmSource(this, profile.transport, profile.card, profile.device, profile.rate, profile.channels,\n            new RootPcmSource.Callback() {\n                @Override public void onStatus(String message) {\n                    diagAudio = message + " · " + profile.packageName;\n                    renderDiag();\n                    updateNotification(message);\n                }\n                @Override public void onPcm(byte[] pcm, int length, int peak) {\n                    if (!running || paused) return;\n                    consumePcm(pcm, length, peak);\n                }\n                @Override public void onError(String message) {\n                    diagAudio = profile.sourceLabel() + " PCM 失败：" + message;\n                    renderDiag();\n                    showStatus(profile.sourceLabel() + " 内部声音捕获失败：" + message\n                        + "\\n回通话兼容中心调整同一固定模式的 PCM/采样率/声道后再试；不会自动切换来源。");\n                }\n            });\n        rootSource.start();\n    }\n\n''')

print('dev3 patch applied')
