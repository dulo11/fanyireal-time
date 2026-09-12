package com.zhou.floatingtranslator;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;

import java.util.Locale;

/** Lightweight process/system RAM diagnostics for the dashboard. */
public final class RuntimeMemory {
    private static final Object LOCK = new Object();
    private static long processBaselinePssKb;
    private static long processPeakPssKb;

    public static final class Snapshot {
        public final long totalPssKb;
        public final long javaPssKb;
        public final long nativePssKb;
        public final long otherPssKb;
        public final long deviceAvailBytes;
        public final long deviceTotalBytes;
        public final boolean lowMemory;
        public final long baselinePssKb;
        public final long deltaPssKb;
        public final long peakPssKb;

        Snapshot(long totalPssKb, long javaPssKb, long nativePssKb, long otherPssKb,
                 long deviceAvailBytes, long deviceTotalBytes, boolean lowMemory,
                 long baselinePssKb, long deltaPssKb, long peakPssKb) {
            this.totalPssKb = totalPssKb;
            this.javaPssKb = javaPssKb;
            this.nativePssKb = nativePssKb;
            this.otherPssKb = otherPssKb;
            this.deviceAvailBytes = deviceAvailBytes;
            this.deviceTotalBytes = deviceTotalBytes;
            this.lowMemory = lowMemory;
            this.baselinePssKb = baselinePssKb;
            this.deltaPssKb = deltaPssKb;
            this.peakPssKb = peakPssKb;
        }

        public String compact() {
            String delta = deltaPssKb >= 0 ? "+" + humanKb(deltaPssKb) : "-" + humanKb(-deltaPssKb);
            return "App RAM(PSS)：" + humanKb(totalPssKb)
                + "（Java " + humanKb(javaPssKb)
                + " · Native " + humanKb(nativePssKb)
                + " · 其他 " + humanKb(otherPssKb) + "）\n"
                + "进程基线：" + humanKb(baselinePssKb)
                + " · 当前增加：" + delta
                + " · 本次峰值：" + humanKb(peakPssKb) + "\n"
                + "手机 RAM：可用 " + humanBytes(deviceAvailBytes)
                + " / " + humanBytes(deviceTotalBytes)
                + (lowMemory ? " · ⚠ 系统低内存" : "");
        }
    }

    private static final class Raw {
        long totalPssKb;
        long javaPssKb;
        long nativePssKb;
        long otherPssKb;
        long deviceAvailBytes;
        long deviceTotalBytes;
        boolean lowMemory;
    }

    private RuntimeMemory() {}

    /** Call once as early as possible in Application.onCreate(), before ASR model loading. */
    public static void captureProcessBaseline(Context context) {
        Raw raw = readRaw(context);
        synchronized (LOCK) {
            if (processBaselinePssKb <= 0L) processBaselinePssKb = Math.max(0L, raw.totalPssKb);
            processPeakPssKb = Math.max(processPeakPssKb, raw.totalPssKb);
        }
    }

    public static Snapshot read(Context context) {
        Raw raw = readRaw(context);
        long baseline;
        long peak;
        synchronized (LOCK) {
            if (processBaselinePssKb <= 0L) processBaselinePssKb = Math.max(0L, raw.totalPssKb);
            processPeakPssKb = Math.max(processPeakPssKb, raw.totalPssKb);
            baseline = processBaselinePssKb;
            peak = processPeakPssKb;
        }
        return new Snapshot(raw.totalPssKb, raw.javaPssKb, raw.nativePssKb, raw.otherPssKb,
            raw.deviceAvailBytes, raw.deviceTotalBytes, raw.lowMemory,
            baseline, raw.totalPssKb - baseline, peak);
    }

    private static Raw readRaw(Context context) {
        Raw raw = new Raw();
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Debug.MemoryInfo[] infos = manager.getProcessMemoryInfo(new int[]{Process.myPid()});
            Debug.MemoryInfo process = infos != null && infos.length > 0 ? infos[0] : new Debug.MemoryInfo();
            ActivityManager.MemoryInfo device = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(device);
            raw.totalPssKb = process.getTotalPss();
            raw.javaPssKb = process.dalvikPss;
            raw.nativePssKb = process.nativePss;
            raw.otherPssKb = Math.max(0L, raw.totalPssKb - raw.javaPssKb - raw.nativePssKb);
            raw.deviceAvailBytes = device.availMem;
            raw.deviceTotalBytes = device.totalMem;
            raw.lowMemory = device.lowMemory;
            return raw;
        } catch (Throwable ignored) {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            raw.totalPssKb = Math.max(0L, used / 1024L);
            raw.javaPssKb = raw.totalPssKb;
            return raw;
        }
    }

    public static String humanKb(long kb) {
        return humanBytes(Math.max(0L, kb) * 1024L);
    }

    public static String humanBytes(long bytes) {
        if (bytes <= 0L) return "未知";
        double mb = bytes / 1024.0 / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.0f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
