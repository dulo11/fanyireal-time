package com.zhou.floatingtranslator;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/** Optional audio preprocessors for the explicit microphone call mode. */
public final class MicAudioEffects implements AutoCloseable {
    public static final String MODE_REMOTE = "remote";
    public static final String MODE_NEAR = "near";
    public static final String MODE_RAW = "raw";

    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;
    private final String mode;
    private boolean aecEnabled;
    private boolean nsEnabled;
    private boolean agcEnabled;

    private MicAudioEffects(String mode) {
        this.mode = normalize(mode);
    }

    public static String normalize(String value) {
        if (MODE_NEAR.equals(value) || MODE_RAW.equals(value)) return value;
        return MODE_REMOTE;
    }

    public static int recommendedAudioSource(String mode) {
        // VOICE_COMMUNICATION can engage vendor echo cancellation before our recorder sees PCM.
        // That is useful for near-end speech, but can erase the far-end speaker audio we want to translate.
        return MODE_NEAR.equals(normalize(mode))
            ? android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            : android.media.MediaRecorder.AudioSource.MIC;
    }

    public static MicAudioEffects attach(AudioRecord record, String mode) {
        MicAudioEffects effects = new MicAudioEffects(mode);
        if (record == null || MODE_RAW.equals(effects.mode)) return effects;
        int session = record.getAudioSessionId();

        // Remote-speaker mode deliberately leaves AEC off: AEC is designed to remove speaker playback.
        if (MODE_NEAR.equals(effects.mode) && AcousticEchoCanceler.isAvailable()) {
            try {
                effects.aec = AcousticEchoCanceler.create(session);
                if (effects.aec != null) {
                    effects.aec.setEnabled(true);
                    effects.aecEnabled = effects.aec.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        if (NoiseSuppressor.isAvailable()) {
            try {
                effects.ns = NoiseSuppressor.create(session);
                if (effects.ns != null) {
                    effects.ns.setEnabled(true);
                    effects.nsEnabled = effects.ns.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        if (AutomaticGainControl.isAvailable()) {
            try {
                effects.agc = AutomaticGainControl.create(session);
                if (effects.agc != null) {
                    effects.agc.setEnabled(true);
                    effects.agcEnabled = effects.agc.getEnabled();
                }
            } catch (Throwable ignored) {}
        }
        return effects;
    }

    public String status() {
        if (MODE_RAW.equals(mode)) return "原始麦克风：AEC/NS/AGC 关闭";
        if (MODE_NEAR.equals(mode)) {
            return "近端优先：AEC" + mark(aecEnabled) + " NS" + mark(nsEnabled) + " AGC" + mark(agcEnabled);
        }
        return "远端外放优先：AEC关 NS" + mark(nsEnabled) + " AGC" + mark(agcEnabled);
    }

    private String mark(boolean enabled) { return enabled ? "✓" : "×"; }

    @Override public void close() {
        if (aec != null) { try { aec.release(); } catch (Throwable ignored) {} aec = null; }
        if (ns != null) { try { ns.release(); } catch (Throwable ignored) {} ns = null; }
        if (agc != null) { try { agc.release(); } catch (Throwable ignored) {} agc = null; }
    }
}
