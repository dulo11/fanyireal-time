package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Plain local SharedPreferences store for user-supplied API credentials. */
public final class SecureConfig {
    public static final String BAIDU_APP_ID = "baidu_app_id";
    public static final String BAIDU_SECRET = "baidu_secret";
    public static final String YOUDAO_APP_KEY = "youdao_app_key";
    public static final String YOUDAO_SECRET = "youdao_secret";

    public static final String AZURE_KEY = "azure_key";
    public static final String AZURE_REGION = "azure_region";

    public static final String ALIYUN_ACCESS_KEY_ID = "aliyun_access_key_id";
    public static final String ALIYUN_ACCESS_KEY_SECRET = "aliyun_access_key_secret";
    public static final String DEEPL_KEY = "deepl_key";
    public static final String GOOGLE_KEY = "google_key";
    public static final String LIBRE_ENDPOINT = "libre_endpoint";
    public static final String LIBRE_KEY = "libre_key";

    // Free-tier online ASR credentials. Values stay only on this phone.
    public static final String GROQ_API_KEY = "groq_api_key";
    public static final String CLOUDFLARE_ACCOUNT_ID = "cloudflare_account_id";
    public static final String CLOUDFLARE_AI_TOKEN = "cloudflare_ai_token";

    private static final String PREFS = "floating_translator_api_local_v1";
    private static final String LEGACY_ENCRYPTED_PREFS = "floating_translator_secure_v1";
    private static final String AZURE_KEY_PREFIX = "azure_key_";
    private static final String AZURE_REGION_PREFIX = "azure_region_";
    private final SharedPreferences prefs;

    public SecureConfig(Context context) {
        Context app = context.getApplicationContext();
        // The project no longer uses the previous Android Keystore/AES encrypted store.
        // Remove the obsolete encrypted preference file; API values must be entered again once.
        app.deleteSharedPreferences(LEGACY_ENCRYPTED_PREFS);
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void put(String name, String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            prefs.edit().remove(name).apply();
        } else {
            prefs.edit().putString(name, cleaned).apply();
        }
    }

    public String get(String name) {
        String value = prefs.getString(name, "");
        return value == null ? "" : value;
    }

    public boolean has(String name) {
        return !get(name).isEmpty();
    }

    public static String azureKeyName(int slot) {
        int safeSlot = Math.max(1, slot);
        return safeSlot == 1 ? AZURE_KEY : AZURE_KEY_PREFIX + safeSlot;
    }

    public static String azureRegionName(int slot) {
        int safeSlot = Math.max(1, slot);
        return safeSlot == 1 ? AZURE_REGION : AZURE_REGION_PREFIX + safeSlot;
    }

    /** Returns every configured Azure profile number in ascending order. */
    public List<Integer> configuredAzureSlots() {
        List<Integer> slots = new ArrayList<>();
        if (has(AZURE_KEY)) slots.add(1);
        Map<String, ?> all = prefs.getAll();
        for (String name : all.keySet()) {
            if (!name.startsWith(AZURE_KEY_PREFIX)) continue;
            String suffix = name.substring(AZURE_KEY_PREFIX.length());
            try {
                int slot = Integer.parseInt(suffix);
                if (slot >= 2 && has(azureKeyName(slot)) && !slots.contains(slot)) slots.add(slot);
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(slots);
        return slots;
    }

    public boolean hasAnyAzureProfile() {
        return !configuredAzureSlots().isEmpty();
    }

    public int configuredAzureProfileCount() {
        return configuredAzureSlots().size();
    }

    public int highestConfiguredAzureSlot() {
        List<Integer> slots = configuredAzureSlots();
        return slots.isEmpty() ? 0 : slots.get(slots.size() - 1);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }

    public int configuredValueCount() {
        return prefs.getAll().size();
    }
}
