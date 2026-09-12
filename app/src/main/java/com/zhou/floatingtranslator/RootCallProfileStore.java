package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores one ALSA/tinycap capture profile per calling app package. */
public final class RootCallProfileStore {
    private static final String PREFS = "floating_translator";
    private static final String SELECTED = "root_call_selected_package";
    private static final String PREFIX = "root_call_profile.";

    private RootCallProfileStore() {}

    public static final class Profile {
        public final String packageName;
        public final String label;
        public final int card;
        public final int device;
        public final int rate;
        public final int channels;

        public Profile(String packageName, String label, int card, int device, int rate, int channels) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.label = label == null ? "" : label.trim();
            this.card = Math.max(0, card);
            this.device = Math.max(0, device);
            this.rate = rate <= 0 ? 48000 : rate;
            this.channels = channels <= 1 ? 1 : 2;
        }

        public String summary() {
            String name = label.isEmpty() ? packageName : label + "（" + packageName + "）";
            return name + "\nALSA card=" + card + " device=" + device + " · " + rate + "Hz · " + channels + "ch";
        }
    }

    public static void save(Context context, Profile profile) {
        if (profile == null || profile.packageName.isEmpty()) return;
        String base = PREFIX + profile.packageName + ".";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SELECTED, profile.packageName)
            .putString(base + "label", profile.label)
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
