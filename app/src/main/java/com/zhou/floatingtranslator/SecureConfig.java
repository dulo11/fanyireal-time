package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore backed store for user-supplied API credentials. */
public final class SecureConfig {
    public static final String BAIDU_APP_ID = "baidu_app_id";
    public static final String BAIDU_SECRET = "baidu_secret";
    public static final String YOUDAO_APP_KEY = "youdao_app_key";
    public static final String YOUDAO_SECRET = "youdao_secret";

    // Azure slot 1 deliberately keeps the original key names for seamless migration.
    public static final String AZURE_KEY = "azure_key";
    public static final String AZURE_REGION = "azure_region";
    public static final String AZURE_KEY_1 = AZURE_KEY;
    public static final String AZURE_REGION_1 = AZURE_REGION;
    public static final String AZURE_KEY_2 = "azure_key_2";
    public static final String AZURE_REGION_2 = "azure_region_2";
    public static final String AZURE_KEY_3 = "azure_key_3";
    public static final String AZURE_REGION_3 = "azure_region_3";
    public static final String AZURE_KEY_4 = "azure_key_4";
    public static final String AZURE_REGION_4 = "azure_region_4";

    public static final String ALIYUN_ACCESS_KEY_ID = "aliyun_access_key_id";
    public static final String ALIYUN_ACCESS_KEY_SECRET = "aliyun_access_key_secret";
    public static final String DEEPL_KEY = "deepl_key";
    public static final String GOOGLE_KEY = "google_key";
    public static final String LIBRE_ENDPOINT = "libre_endpoint";
    public static final String LIBRE_KEY = "libre_key";

    public static final String[] ALL_KEYS = {
        BAIDU_APP_ID, BAIDU_SECRET, YOUDAO_APP_KEY, YOUDAO_SECRET,
        AZURE_KEY_1, AZURE_REGION_1, AZURE_KEY_2, AZURE_REGION_2,
        AZURE_KEY_3, AZURE_REGION_3, AZURE_KEY_4, AZURE_REGION_4,
        ALIYUN_ACCESS_KEY_ID, ALIYUN_ACCESS_KEY_SECRET,
        DEEPL_KEY, GOOGLE_KEY, LIBRE_ENDPOINT, LIBRE_KEY
    };

    private static final String PREFS = "floating_translator_secure_v1";
    private static final String KEY_ALIAS = "floating_translator_api_key_v1";
    private final SharedPreferences prefs;

    public SecureConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void put(String name, String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            prefs.edit().remove(name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(cleaned.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(name, packed).apply();
        } catch (Exception e) {
            throw new IllegalStateException("无法安全保存 API 配置", e);
        }
    }

    public String get(String name) {
        String packed = prefs.getString(name, "");
        if (packed == null || packed.isEmpty()) return "";
        try {
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean has(String name) {
        return !get(name).isEmpty();
    }

    public static String azureKeyName(int slot) {
        switch (slot) {
            case 2: return AZURE_KEY_2;
            case 3: return AZURE_KEY_3;
            case 4: return AZURE_KEY_4;
            case 1:
            default: return AZURE_KEY_1;
        }
    }

    public static String azureRegionName(int slot) {
        switch (slot) {
            case 2: return AZURE_REGION_2;
            case 3: return AZURE_REGION_3;
            case 4: return AZURE_REGION_4;
            case 1:
            default: return AZURE_REGION_1;
        }
    }

    public boolean hasAnyAzureProfile() {
        for (int slot = 1; slot <= 4; slot++) {
            if (has(azureKeyName(slot))) return true;
        }
        return false;
    }

    public int configuredAzureProfileCount() {
        int count = 0;
        for (int slot = 1; slot <= 4; slot++) {
            if (has(azureKeyName(slot))) count++;
        }
        return count;
    }

    /** Clears every API credential/value stored by this app. */
    public void clearAll() {
        prefs.edit().clear().apply();
    }

    /** Returns only a count; never exposes secret values. */
    public int configuredValueCount() {
        int count = 0;
        for (String key : ALL_KEYS) if (has(key)) count++;
        return count;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
