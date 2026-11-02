package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Prevents translation overlays from covering the app UI and attaches global nav. */
public final class FloatingTranslatorApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "floating_translator";
    private static final String PREF_DEV5_SMART_LANGUAGE_MIGRATED = "dev5_smart_language_migrated";
    private static final String PREF_071_SCREEN_QUALITY_MIGRATED = "v071_screen_quality_migrated";

    @Override public void onCreate() {
        super.onCreate();
        RuntimeMemory.captureProcessBaseline(this);
        registerActivityLifecycleCallbacks(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        prefs.edit().putBoolean("service_running",false).apply();
        UserArchive.checkpointUpgrade(this);
        try{DownloadTasks.recover(this);}catch(Exception ignored){}

        // dev5: make automatic multilingual recognition/routing the default for existing installs too.
        // The configured source language is retained as the fallback language for ASR/very short text.
        if (!prefs.getBoolean(PREF_DEV5_SMART_LANGUAGE_MIGRATED, false)) {
            prefs.edit()
                .putBoolean("auto_language_enabled", true)
                .putString("language_mode", SherpaSpeechEngine.LANG_AUTO)
                .putBoolean(PREF_DEV5_SMART_LANGUAGE_MIGRATED, true)
                .apply();
        }

        // v0.7.1: improve real-world Telegram/web coverage without enabling continuous mode behind
        // the user's back. OCR fallback and smart completion are safe defaults once accessibility
        // translation is explicitly enabled by the user.
        if (!prefs.getBoolean(PREF_071_SCREEN_QUALITY_MIGRATED, false)) {
            prefs.edit()
                .putBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_OCR_FALLBACK, true)
                .putBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_SMART_OCR, true)
                .putBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_INCREMENTAL_CACHE, true)
                .putBoolean(PREF_071_SCREEN_QUALITY_MIGRATED, true)
                .apply();
        }


    }

    private void signal(Activity activity, String action) {
        boolean running = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("service_running", false);
        if (!running) return;
        try { startService(new Intent(this, TranslationService.class).setAction(action)); }
        catch (Exception ignored) {}
        try { startService(new Intent(this, RootCallTranslationService.class).setAction(action)); }
        catch (Exception ignored) {}
    }

    @Override public void onActivityResumed(Activity activity) {
        signal(activity, TranslationService.ACTION_UI_VISIBLE);
        activity.getWindow().getDecorView().post(() -> {
            UiLocalizer.apply(activity);
            AppBottomNav.attach(activity);
        });
    }

    @Override public void onActivityPaused(Activity activity) {
        signal(activity, TranslationService.ACTION_UI_HIDDEN);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
