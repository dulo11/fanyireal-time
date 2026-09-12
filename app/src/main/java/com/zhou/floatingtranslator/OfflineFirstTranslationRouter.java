package com.zhou.floatingtranslator;

import android.content.Context;

/**
 * Offline-first wrapper around TranslationRouter.
 * AUTO means: ML Kit first; only if the local model cannot translate do we try
 * configured online engines. An explicitly selected online engine is still honoured.
 */
public final class OfflineFirstTranslationRouter implements AutoCloseable {
    public interface Callback {
        void onSuccess(String translated, String engineName);
        void onError(String message);
    }

    private final String selectedEngine;
    private final TranslationRouter local;
    private final TranslationRouter selected;
    private boolean closed;

    public OfflineFirstTranslationRouter(Context context, String source, String target, String selectedEngine) {
        String normalized = selectedEngine == null || selectedEngine.isEmpty()
            ? TranslationRouter.AUTO : selectedEngine;
        this.selectedEngine = normalized;
        this.local = new TranslationRouter(context, source, target, TranslationRouter.MLKIT);
        this.selected = TranslationRouter.MLKIT.equals(normalized)
            ? this.local
            : new TranslationRouter(context, source, target, normalized);
    }

    public String selectedEngineName() {
        if (TranslationRouter.AUTO.equals(selectedEngine)) {
            return "自动 · ML Kit 离线优先";
        }
        return TranslationRouter.engineLabel(selectedEngine);
    }

    public boolean hasYoudaoCredentials() {
        return selected.hasYoudaoCredentials();
    }

    public void translate(String text, Callback callback) {
        if (closed) {
            callback.onError("翻译引擎已经关闭");
            return;
        }

        if (!TranslationRouter.AUTO.equals(selectedEngine)) {
            selected.translate(text, adapt(callback));
            return;
        }

        local.translate(text, new TranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                callback.onSuccess(translated, engineName + " · 离线优先");
            }

            @Override public void onError(String localError) {
                // AUTO TranslationRouter will skip cloud engines that have no credentials.
                selected.translate(text, new TranslationRouter.Callback() {
                    @Override public void onSuccess(String translated, String engineName) {
                        callback.onSuccess(translated, engineName + " · 离线失败后兜底");
                    }

                    @Override public void onError(String cloudError) {
                        callback.onError("ML Kit：" + localError + "；备用：" + cloudError);
                    }
                });
            }
        });
    }

    public void translateYoudaoSpeech(byte[] pcm16le, TranslationRouter.SpeechCallback callback) {
        selected.translateYoudaoSpeech(pcm16le, callback);
    }

    private static TranslationRouter.Callback adapt(Callback callback) {
        return new TranslationRouter.Callback() {
            @Override public void onSuccess(String translated, String engineName) {
                callback.onSuccess(translated, engineName);
            }

            @Override public void onError(String message) {
                callback.onError(message);
            }
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { local.close(); } catch (Exception ignored) {}
        if (selected != local) {
            try { selected.close(); } catch (Exception ignored) {}
        }
    }
}
