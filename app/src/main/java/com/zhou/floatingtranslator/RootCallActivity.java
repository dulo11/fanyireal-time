package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/** Fixed ROOT/Shizuku VoIP/call compatibility center. No system audio policy files are modified. */
public final class RootCallActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private static final Pattern PCM = Pattern.compile("(?m)^\\s*(\\d+)-(\\d+):[^\\n]*capture[^\\n]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPONENT = Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_$]+)+)/[A-Za-z0-9_.$]+");

    private static final CommonApp[] COMMON_APPS = new CommonApp[]{
        new CommonApp("自动：上一个前台 App", ""),
        new CommonApp("微信", "com.tencent.mm"),
        new CommonApp("QQ", "com.tencent.mobileqq"),
        new CommonApp("Telegram", "org.telegram.messenger"),
        new CommonApp("WhatsApp", "com.whatsapp"),
        new CommonApp("LINE", "jp.naver.line.android"),
        new CommonApp("Signal", "org.thoughtcrime.securesms"),
        new CommonApp("Discord", "com.discord"),
        new CommonApp("Messenger", "com.facebook.orca"),
        new CommonApp("Microsoft Teams", "com.microsoft.teams"),
        new CommonApp("Zoom", "us.zoom.videomeetings"),
        new CommonApp("Google Meet", "com.google.android.apps.tachyon"),
        new CommonApp("其他 App：手动填写包名", "")
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<PcmCandidate> candidates = new ArrayList<>();

    private TextView rootStatus;
    private TextView savedStatus;
    private Spinner transportSpinner;
    private Spinner appSpinner;
    private Spinner pcmSpinner;
    private Spinner rateSpinner;
    private Spinner channelSpinner;
    private EditText packageInput;
    private Button scanButton;
    private Button testButton;
    private ArrayAdapter<PcmCandidate> pcmAdapter;
    private volatile String lastReport = "";
    private volatile String detectedPreviousPackage = "";
    private volatile RootPcmSource testCapture;
    private volatile int testPeak;
    private volatile long testBytes;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode != ShizukuShell.REQUEST_PERMISSION) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku 已授权，开始扫描");
            main.postDelayed(this::scanPrivilegedAudio, 180L);
        } else {
            rootStatus.setText("❌ Shizuku 授权被拒绝；不会自动切换到 ROOT 或麦克风");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · 通话内部声音");
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column();
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        scroll.addView(root);

        TextView title = text("通话内部声音 / VoIP 翻译", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView intro = text(
            "这里提供两个固定内部声音后端：ROOT（uid=0，兼容性最高）和 Shizuku（ADB shell，免 ROOT 实验）。" +
            "两者都按 App 保存 ALSA card/device/采样率/声道，不会失败后偷偷切换权限来源或 PCM。\n\n" +
            "Shizuku 是否能读通话 PCM 取决于 ROM、shell 权限和 SELinux；失败时请手动改用 ROOT 或外放+麦克风。",
            14, Color.rgb(210, 200, 225));
        intro.setPadding(0, dp(7), 0, dp(13));
        root.addView(intro);

        LinearLayout detectCard = card(root);
        detectCard.addView(section("① 固定权限来源 / 音频设备扫描"));
        transportSpinner = new Spinner(this);
        transportSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"固定 ROOT｜uid=0", "固定 Shizuku｜ADB shell（实验）"}));
        RootCallProfileStore.Profile savedTransport = RootCallProfileStore.loadSelected(this);
        if (savedTransport != null && RootCallProfileStore.TRANSPORT_SHIZUKU.equals(savedTransport.transport)) {
            transportSpinner.setSelection(1);
        }
        transportSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        detectCard.addView(transportSpinner, params());
        scanButton = button("扫描当前固定模式：tinycap / ALSA / 音频状态");
        scanButton.setOnClickListener(v -> scanPrivilegedAudio());
        detectCard.addView(scanButton, params());
        rootStatus = text("尚未扫描。先点上面的按钮。", 13, Color.rgb(218, 209, 231));
        rootStatus.setPadding(0, dp(7), 0, 0);
        detectCard.addView(rootStatus);

        LinearLayout appCard = card(root);
        appCard.addView(section("② 选择要翻译通话的 App"));
        appSpinner = new Spinner(this);
        String[] names = new String[COMMON_APPS.length];
        for (int i = 0; i < names.length; i++) names[i] = COMMON_APPS[i].label;
        appSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        appSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        appCard.addView(appSpinner, params());

        packageInput = new EditText(this);
        packageInput.setHint("包名，例如 org.telegram.messenger");
        packageInput.setTextColor(Color.WHITE);
        packageInput.setHintTextColor(Color.rgb(145, 135, 165));
        packageInput.setSingleLine(true);
        appCard.addView(packageInput, params());
        String selected = RootCallProfileStore.selectedPackage(this);
        if (!selected.isEmpty()) packageInput.setText(selected);

        appSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CommonApp app = COMMON_APPS[position];
                if (!app.packageName.isEmpty()) packageInput.setText(app.packageName);
                else if (position == 0 && !detectedPreviousPackage.isEmpty()) packageInput.setText(detectedPreviousPackage);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextView appTip = text(
            "“上一个前台 App”适合：先打开通话 App，再返回浮译扫描。也可以直接填写任意 App 包名，所以并不限于上面这些常见软件。",
            12, Color.rgb(184, 174, 207));
        appCard.addView(appTip);

        LinearLayout pcmCard = card(root);
        pcmCard.addView(section("③ 选择并测试固定内部 PCM"));
        pcmSpinner = new Spinner(this);
        pcmAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, candidates);
        pcmSpinner.setAdapter(pcmAdapter);
        pcmSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        pcmCard.addView(pcmSpinner, params());

        rateSpinner = new Spinner(this);
        rateSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"48000 Hz（优先测试）", "16000 Hz", "44100 Hz"}));
        rateSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        pcmCard.addView(rateSpinner, params());

        channelSpinner = new Spinner(this);
        channelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"2 声道（优先测试）", "1 声道"}));
        channelSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        pcmCard.addView(channelSpinner, params());

        testButton = button("通话中测试 5 秒内部声音");
        testButton.setOnClickListener(v -> testSelectedPcm());
        pcmCard.addView(testButton, params());

        Button save = primaryButton("保存为这个 App 的固定内部声音配置");
        save.setOnClickListener(v -> saveSelectedProfile());
        pcmCard.addView(save, params());

        savedStatus = text(savedProfileText(), 13, Color.rgb(190, 165, 255));
        savedStatus.setPadding(0, dp(6), 0, 0);
        pcmCard.addView(savedStatus);

        LinearLayout launchCard = card(root);
        launchCard.addView(section("④ 开始使用"));
        Button console = primaryButton("打开实时翻译控制台");
        console.setOnClickListener(v -> {
            if (!RootCallProfileStore.hasSelected(this)) {
                toast("请先保存一个 ROOT / Shizuku 通话配置");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("input_mode", 2).apply();
            startActivity(new Intent(this, MainActivity.class));
        });
        launchCard.addView(console, params());

        Button mic = button("免 ROOT/Shizuku：外放 + 麦克风增强模式");
        mic.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("input_mode", 1).apply();
            startActivity(new Intent(this, MainActivity.class));
        });
        launchCard.addView(mic, params());

        Button copy = button("复制完整内部声音诊断");
        copy.setOnClickListener(v -> copyReport());
        launchCard.addView(copy, params());

        TextView warning = text(
            "测试时要让目标 App 正在通话并持续有人说话。ROOT 成功率通常高于 Shizuku；Shizuku 只有在 shell/SELinux 允许读取目标 PCM 时才会接近 ROOT。" +
            "两种内部模式都可能被厂商 HAL/硬件路由限制。录音或翻译通话请遵守所在地法律并尊重通话参与者隐私。",
            12, Color.rgb(180, 170, 205));
        warning.setPadding(dp(2), dp(8), dp(2), 0);
        root.addView(warning);
        return scroll;
    }

    private void scanPrivilegedAudio() {
        String transport = selectedTransport();
        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {
            if (!ShizukuShell.isRunning()) {
                rootStatus.setText("❌ Shizuku 未运行。先打开 Shizuku 并通过无线调试/ADB 启动服务。\n不会自动切换到 ROOT。");
                return;
            }
            if (!ShizukuShell.hasPermission()) {
                rootStatus.setText("正在请求 Shizuku 授权……");
                try { ShizukuShell.requestPermission(); }
                catch (Exception e) { rootStatus.setText("❌ " + safe(e)); }
                return;
            }
        }

        scanButton.setEnabled(false);
        rootStatus.setText(RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)
            ? "正在用固定 Shizuku(shell) 扫描……"
            : "正在请求固定 ROOT 并扫描……如果 KernelSU/Magisk 弹授权，请允许浮译。");
        worker.execute(() -> {
            String command =
                "echo PRIV_ID; id; " +
                "echo SELINUX; getenforce 2>/dev/null || true; " +
                "echo TINYCAP; for p in /vendor/bin/tinycap /system/bin/tinycap /system/xbin/tinycap; do [ -x \"$p\" ] && echo $p; done; command -v tinycap 2>/dev/null || true; " +
                "echo ALSA_CARDS; cat /proc/asound/cards 2>/dev/null || true; " +
                "echo ALSA_PCM; cat /proc/asound/pcm 2>/dev/null || true; " +
                "echo ACTIVITY; dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity|topResumedActivity' | head -12 || true; " +
                "echo AUDIO_STATE; dumpsys audio 2>/dev/null | head -180 || true; " +
                "echo PACKAGES; pm list packages 2>/dev/null || true";
            String raw;
            try {
                raw = runPrivileged(transport, command, 10, 80000);
            } catch (Exception e) {
                raw = "ERROR: " + safe(e);
            }
            final boolean accessOk = RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)
                ? (raw.contains("uid=2000") || raw.contains("uid=0"))
                : raw.contains("uid=0");
            final boolean tinycap = raw.contains("/tinycap") || raw.matches("(?s).*TINYCAP\\s+tinycap.*");
            final ArrayList<PcmCandidate> found = parseCandidates(raw);
            final String previous = findPreviousExternalPackage(raw);
            final Set<String> installed = installedPackages(raw);
            final String summary = makeSummary(transport, accessOk, tinycap, found, previous, installed);
            lastReport = summary + "\n\n===== 原始诊断 =====\n" + raw;
            detectedPreviousPackage = previous;
            runOnUiThread(() -> {
                candidates.clear();
                candidates.addAll(found);
                pcmAdapter.notifyDataSetChanged();
                if (!previous.isEmpty() && appSpinner.getSelectedItemPosition() == 0) packageInput.setText(previous);
                rootStatus.setText(summary);
                scanButton.setEnabled(true);
            });
        });
    }

    private ArrayList<PcmCandidate> parseCandidates(String raw) {
        ArrayList<PcmCandidate> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = PCM.matcher(raw == null ? "" : raw);
        while (m.find()) {
            int card = Integer.parseInt(m.group(1));
            int device = Integer.parseInt(m.group(2));
            String key = card + ":" + device;
            if (!seen.add(key)) continue;
            String line = m.group(0).trim();
            if (line.length() > 100) line = line.substring(0, 100) + "…";
            list.add(new PcmCandidate(card, device, line));
        }
        return list;
    }

    private void testSelectedPcm() {
        if (testCapture != null) {
            toast("已经在测试，请稍候");
            return;
        }
        PcmCandidate pcm = selectedPcm();
        if (pcm == null) {
            toast("请先扫描并选择一个 capture PCM");
            return;
        }
        int rate = selectedRate();
        int channels = selectedChannels();
        testPeak = 0;
        testBytes = 0;
        testButton.setEnabled(false);
        rootStatus.setText("测试中：请保持目标 App 通话，并让对方持续说话约 5 秒……");
        RootPcmSource source = new RootPcmSource(this, selectedTransport(), pcm.card, pcm.device, rate, channels,
            new RootPcmSource.Callback() {
                @Override public void onStatus(String message) {
                    runOnUiThread(() -> rootStatus.setText(message + "\n请保持通话有声音……"));
                }
                @Override public void onPcm(byte[] pcmBytes, int length, int peak) {
                    testBytes += length;
                    if (peak > testPeak) testPeak = peak;
                }
                @Override public void onError(String message) {
                    finishTest("❌ 测试失败：" + message);
                }
            });
        testCapture = source;
        source.start();
        main.postDelayed(() -> {
            RootPcmSource current = testCapture;
            if (current == null) return;
            current.close();
            testCapture = null;
            int peak = testPeak;
            long bytes = testBytes;
            String level;
            if (bytes < 8000) level = "❌ 几乎没有读到 PCM 数据，换设备/采样率/声道";
            else if (peak < 80) level = "⚠ 读到了 PCM，但接近静音；这个设备大概率不是通话声音";
            else if (peak < 500) level = "⚠ 有弱信号；可以再测试别的 PCM 对比";
            else level = "✅ 检测到明显内部音频，可以保存这个配置继续实测翻译";
            rootStatus.setText(level + "\n峰值=" + peak + " · 已转换 16k PCM=" + human(bytes));
            testButton.setEnabled(true);
        }, 6500L);
    }

    private void finishTest(String message) {
        RootPcmSource current = testCapture;
        testCapture = null;
        if (current != null) current.close();
        runOnUiThread(() -> {
            rootStatus.setText(message);
            testButton.setEnabled(true);
        });
    }

    private void saveSelectedProfile() {
        String pkg = packageInput.getText().toString().trim();
        PcmCandidate pcm = selectedPcm();
        if (pkg.isEmpty() || !pkg.contains(".")) {
            toast("请填写正确的 App 包名");
            return;
        }
        if (pcm == null) {
            toast("请先扫描并选择一个 PCM 设备");
            return;
        }
        String label = appLabel(pkg);
        RootCallProfileStore.Profile profile = new RootCallProfileStore.Profile(
            pkg, label, selectedTransport(), pcm.card, pcm.device, selectedRate(), selectedChannels());
        RootCallProfileStore.save(this, profile);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("input_mode", 2).apply();
        savedStatus.setText("✅ 已保存并设为当前固定通话来源\n" + profile.summary());
        toast(profile.sourceLabel() + " 通话配置已保存");
    }

    private String savedProfileText() {
        RootCallProfileStore.Profile p = RootCallProfileStore.loadSelected(this);
        return p == null ? "当前没有保存内部通话配置" : "当前固定配置：\n" + p.summary();
    }

    private PcmCandidate selectedPcm() {
        Object item = pcmSpinner == null ? null : pcmSpinner.getSelectedItem();
        return item instanceof PcmCandidate ? (PcmCandidate) item : null;
    }

    private int selectedRate() {
        int p = rateSpinner == null ? 0 : rateSpinner.getSelectedItemPosition();
        return p == 1 ? 16000 : p == 2 ? 44100 : 48000;
    }

    private int selectedChannels() {
        return channelSpinner != null && channelSpinner.getSelectedItemPosition() == 1 ? 1 : 2;
    }

    private String makeSummary(String transport, boolean accessOk, boolean tinycap, ArrayList<PcmCandidate> found,
                               String previous, Set<String> installed) {
        String source = RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport) ? "Shizuku" : "ROOT";
        StringBuilder out = new StringBuilder();
        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {
            out.append(accessOk ? "✅ Shizuku：可用（shell/root 服务身份）" : "❌ Shizuku：没有取得可用 shell 身份").append('\n');
            out.append(ShizukuShell.describe()).append('\n');
        } else {
            out.append(accessOk ? "✅ ROOT：可用" : "❌ ROOT：没有拿到 uid=0").append('\n');
        }
        out.append(tinycap ? "✅ tinycap：已找到" : "❌ tinycap：未找到").append('\n');
        out.append(found.isEmpty() ? "⚠ ALSA：没有解析到 capture PCM" : "✅ ALSA capture：" + found.size() + " 个候选").append('\n');
        if (!previous.isEmpty()) out.append("上一个前台 App：").append(appLabel(previous)).append(" · ").append(previous).append('\n');
        ArrayList<String> known = new ArrayList<>();
        for (CommonApp app : COMMON_APPS) if (!app.packageName.isEmpty() && installed.contains(app.packageName)) known.add(app.label);
        if (!known.isEmpty()) out.append("检测到常见通话 App：").append(String.join("、", known)).append('\n');
        if (accessOk && tinycap && !found.isEmpty()) {
            out.append("\n下一步：保持通话有声音，逐个做 5 秒测试。当前固定权限来源=").append(source)
                .append("；失败不会自动切换。 ");
        }
        return out.toString().trim();
    }

    private String findPreviousExternalPackage(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf("ACTIVITY");
        int end = raw.indexOf("AUDIO_STATE");
        String section = start >= 0 ? raw.substring(start, end > start ? end : raw.length()) : raw;
        Matcher m = COMPONENT.matcher(section);
        while (m.find()) {
            String pkg = m.group(1);
            if (pkg.equals(getPackageName()) || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.launcher")) continue;
            return pkg;
        }
        return "";
    }

    private Set<String> installedPackages(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        for (String line : raw.split("\\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8).trim());
        }
        return out;
    }

    private String appLabel(String pkg) {
        for (CommonApp app : COMMON_APPS) if (app.packageName.equals(pkg)) return app.label;
        return pkg;
    }

    private String selectedTransport() {
        return transportSpinner != null && transportSpinner.getSelectedItemPosition() == 1
            ? RootCallProfileStore.TRANSPORT_SHIZUKU : RootCallProfileStore.TRANSPORT_ROOT;
    }

    private String runPrivileged(String transport, String command, int timeoutSeconds, int maxChars) throws Exception {
        if (RootCallProfileStore.TRANSPORT_SHIZUKU.equals(transport)) {
            return ShizukuShell.runText(command, timeoutSeconds, maxChars);
        }
        return runRoot(command, timeoutSeconds, maxChars);
    }

    private String runRoot(String command, int timeoutSeconds, int maxChars) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < maxChars) out.append(line).append('\n');
            } catch (Exception ignored) {}
        }, "root-call-scan-reader");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ROOT 扫描超时");
        }
        try { reader.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out.toString();
    }

    private void copyReport() {
        if (lastReport.isEmpty()) { toast("请先执行 ROOT / Shizuku 扫描"); return; }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("浮译通话内部声音诊断", lastReport));
        toast("完整诊断已复制");
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = column();
        card.setGravity(Gravity.NO_GRAVITY);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.panel);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        root.addView(card, p);
        return card;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button primaryButton(String value) {
        Button b = button(value);
        b.setBackgroundResource(R.drawable.button_primary);
        return b;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024d);
    }

    private String safe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}
        RootPcmSource current = testCapture;
        testCapture = null;
        if (current != null) current.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class PcmCandidate {
        final int card;
        final int device;
        final String description;
        PcmCandidate(int card, int device, String description) {
            this.card = card;
            this.device = device;
            this.description = description;
        }
        @Override public String toString() {
            return "card " + card + " / device " + device + " · " + description;
        }
    }

    private static final class CommonApp {
        final String label;
        final String packageName;
        CommonApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
