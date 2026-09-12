package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.Intent;

/** Prevents MediaProjection/OCR from recursively processing the app's own UI. */
public final class FloatingTranslatorApp extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    private void signal(Activity activity, String action) {
        if (!(activity instanceof MainActivity)) return;
        boolean running = getSharedPreferences("floating_translator", MODE_PRIVATE)
            .getBoolean("service_running", false);
        if (!running) return;
        try {
            startService(new Intent(this, TranslationService.class).setAction(action));
        } catch (Exception ignored) {}
    }

    @Override public void onActivityResumed(Activity activity) {
        signal(activity, TranslationService.ACTION_UI_VISIBLE);
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
