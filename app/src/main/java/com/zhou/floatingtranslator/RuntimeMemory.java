package com.zhou.floatingtranslator;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;

import java.util.Locale;

/** Lightweight process/system RAM diagnostics for the dashboard. */
public final class RuntimeMemory {
    public static final class Snapshot {
        public final long totalPssKb;
        public final long javaPssKb;
        public final long nativePssKb;
        public final long otherPssKb;
        public final long deviceAvailBytes;
        public final long deviceTotalBytes;
        public final boolean lowMemory;

        Snapshot(long totalPssKb, long javaPssKb, long nativePssKb, long otherPssKb,
                 long deviceAvailBytes, long deviceTotalBytes, boolean lowMemory) {
            this.totalPssKb = totalPssKb;
            this.javaPssKb = javaPssKb;
            this.nativePssKb = nativePssKb;
            this.otherPssKb = otherPssKb;
            this.deviceAvailBytes = deviceAvailBytes;
            this.deviceTotalBytes = deviceTotalBytes;
            this.lowMemory = lowMemory;
        }

        public String compact() {
            return "App RAM(PSS)：" + humanKb(totalPssKb)
                + "（Java " + humanKb(javaPssKb)
                + " · Native " + humanKb(nativePssKb)
                + " · 其他 " + humanKb(otherPssKb) + "）\n"
                + "手机 RAM：可用 " + humanBytes(deviceAvailBytes)
                + " / " + humanBytes(deviceTotalBytes)
                + (lowMemory ? " · ⚠ 系统低内存" : "");
        }
    }

    private RuntimeMemory() {}

    public static Snapshot read(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Debug.MemoryInfo[] infos = manager.getProcessMemoryInfo(new int[]{Process.myPid()});
            Debug.MemoryInfo process = infos != null && infos.length > 0 ? infos[0] : new Debug.MemoryInfo();
            ActivityManager.MemoryInfo device = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(device);
            long total = process.getTotalPss();
            long javaPss = process.dalvikPss;
            long nativePss = process.nativePss;
            long other = Math.max(0L, total - javaPss - nativePss);
            return new Snapshot(total, javaPss, nativePss, other,
                device.availMem, device.totalMem, device.lowMemory);
        } catch (Throwable ignored) {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long kb = Math.max(0L, used / 1024L);
            return new Snapshot(kb, kb, 0L, 0L, 0L, 0L, false);
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
