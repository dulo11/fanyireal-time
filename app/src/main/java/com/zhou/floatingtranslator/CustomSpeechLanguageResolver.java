package com.zhou.floatingtranslator;

import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-session best-effort source selector for an explicitly chosen mixed
 * language set. The ASR model may recognize more languages; only verified
 * selected source languages can reach translation.
 */
final class CustomSpeechLanguageResolver implements AutoCloseable {
    interface Callback {
        void onSelected(String sourceLanguage);
        void onRejected(String reason);
    }

    private final List<String> allowed;
    private final LanguageIdentifier identifier;
    private volatile boolean closed;

    CustomSpeechLanguageResolver(List<String> allowed) {
        this.allowed = new ArrayList<>(allowed);
        identifier = LanguageIdentification.getClient();
    }

    void resolve(String transcript, String asrLanguage, Callback callback) {
        if (closed || callback == null) return;
        if (AsrTranscriptGuard.isLikelyNoise(transcript)) {
            callback.onRejected("已过滤无意义短字符");
            return;
        }
        // ML Kit language ID works on recognized text, not the original PCM.
        // Do not claim the underlying model was forced to these languages.
        identifier.identifyPossibleLanguages(transcript)
            .addOnSuccessListener(candidates -> {
                if (closed) return;
                List<SelectedSpeechLanguages.Evidence> evidence = new ArrayList<>();
                for (IdentifiedLanguage candidate : candidates) {
                    evidence.add(new SelectedSpeechLanguages.Evidence(
                        candidate.getLanguageTag(), candidate.getConfidence()));
                }
                complete(transcript, asrLanguage, evidence, callback);
            })
            .addOnFailureListener(error -> {
                if (!closed) complete(transcript, asrLanguage, null, callback);
            });
    }

    private void complete(String transcript, String asrLanguage,
                          List<SelectedSpeechLanguages.Evidence> candidates, Callback callback) {
        String chosen = SelectedSpeechLanguages.choose(transcript, asrLanguage, allowed, candidates);
        if (chosen.isEmpty()) callback.onRejected("不在所选语种中，或识别语言不确定");
        else callback.onSelected(chosen);
    }

    @Override public void close() {
        closed = true;
        try { identifier.close(); } catch (Exception ignored) {}
    }
}
