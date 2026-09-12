package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ROOT call-translation laboratory.
 * This release intentionally performs diagnostics only: root by itself does not turn an ordinary
 * app into a privileged telephony recorder, and actual uplink/downlink ALSA routes differ by device.
 */
public final class RootCallActivity extends Activity {
    private static final String PREFS = "floating_translator";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView result;
    private Button detect;
    private volatile String lastReport = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("浮译 0.5.1 · ROOT 通话实验室");
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text("ROOT 通话翻译实验室", 28, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView intro = text(
            "ROOT 会明显增加通话音频适配的可能性，但不是“有 ROOT 就一定能抓双方声音”。普通 App 即使获得 su，" +
            "Android 的电话音频仍受 AudioPolicy、SELinux、厂商音频 HAL 和设备路由限制。\n\n" +
            "0.5.1 先加入 ROOT 检测、tinycap/ALSA 设备扫描和稳定的“外放 + 麦克风”通话方案入口。" +
            "后续可以根据你的 OnePlus/ROM 实际扫描结果接入真正的 ROOT 内部通话音频后端。",
            14, Color.rgb(210, 200, 225));
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        detect = button("① 检测 ROOT / KernelSU / Magisk 音频条件");
        detect.setOnClickListener(v -> detectRootAudio());
        root.addView(detect, params());

        Button mic = button("② 使用通话外放 + 麦克风模式");
        mic.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putInt("input_mode", 1).putBoolean("call_mode_hint", true).apply();
            toast("已把声音来源设为麦克风。通话时打开扬声器，再回主界面开始翻译。");
            finish();
        });
        root.addView(mic, params());

        Button copy = button("复制 ROOT 音频诊断");
        copy.setOnClickListener(v -> {
            if (lastReport.isEmpty()) { toast("请先执行检测"); return; }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("浮译 ROOT 音频诊断", lastReport));
            toast("诊断已复制");
        });
        root.addView(copy, params());

        TextView warning = text(
            "真正的 ROOT 双向通话抓音需要根据扫描结果确定 ALSA card/device 或厂商音频路由。" +
            "本页不会自动修改 audio_policy、SELinux 或系统音频配置，避免把手机通话/媒体声音改坏。" +
            "录音/翻译通话时请遵守所在地法律和对方隐私要求。",
            13, Color.rgb(184, 174, 207));
        warning.setPadding(0, dp(10), 0, dp(12));
        root.addView(warning);

        result = text("尚未检测。", 13, Color.rgb(225, 220, 235));
        result.setGravity(Gravity.START);
        root.addView(result);
        return scroll;
    }

    private void detectRootAudio() {
        detect.setEnabled(false);
        result.setText("正在请求 ROOT 并扫描音频设备……\n如果弹出 KernelSU/Magisk 授权，请允许浮译。 ");
        worker.execute(() -> {
            String command =
                "echo ROOT_ID; id; " +
                "echo SELINUX; getenforce 2>/dev/null || true; " +
                "echo TINYCAP; " +
                "for p in /vendor/bin/tinycap /system/bin/tinycap /system/xbin/tinycap; do [ -x \"$p\" ] && echo $p; done; " +
                "command -v tinycap 2>/dev/null || true; " +
                "echo ALSA_CARDS; cat /proc/asound/cards 2>/dev/null || true; " +
                "echo ALSA_PCM; cat /proc/asound/pcm 2>/dev/null || true; " +
                "echo AUDIO_POLICY; ls -1 /vendor/etc/*audio*policy* /system/etc/*audio*policy* 2>/dev/null | head -30 || true";
            String report;
            try {
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null && out.length() < 24000) {
                        out.append(line).append('\n');
                    }
                }
                boolean exited = process.waitFor(7, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    throw new IllegalStateException("ROOT 命令超时");
                }
                String raw = out.toString();
                boolean rootOk = raw.contains("uid=0");
                boolean tinycap = raw.contains("/tinycap") || raw.matches("(?s).*TINYCAP\\s+tinycap.*");
                boolean alsa = raw.contains("ALSA_PCM") && raw.split("ALSA_PCM", 2).length > 1
                    && raw.split("ALSA_PCM", 2)[1].trim().length() > 0;
                String verdict = rootOk
                    ? "✅ ROOT：可用\n" + (tinycap ? "✅ tinycap：发现候选工具\n" : "⚠ tinycap：未发现\n")
                        + (alsa ? "✅ ALSA：可读取设备列表\n" : "⚠ ALSA：没有可见 PCM 列表\n")
                        + ((tinycap && alsa)
                            ? "\n结论：具备继续做 ROOT 内部通话抓音适配的基础条件，但仍需确定本机通话上下行设备。\n\n"
                            : "\n结论：ROOT 已有，但当前环境还不能直接判定可抓电话双方音轨。\n\n")
                    : "❌ 没拿到 uid=0。请确认 KernelSU/Magisk 已给浮译 ROOT 权限。\n\n";
                report = verdict + "===== 原始诊断 =====\n" + raw;
            } catch (Exception e) {
                report = "❌ ROOT/音频检测失败：" + safe(e)
                    + "\n\n如果没有弹出授权，请检查 KernelSU/Magisk 的超级用户列表。";
            }
            lastReport = report;
            runOnUiThread(() -> {
                result.setText(lastReport);
                detect.setEnabled(true);
            });
        });
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
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
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private String safe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
