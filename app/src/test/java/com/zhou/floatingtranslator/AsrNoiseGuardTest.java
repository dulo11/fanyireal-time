package com.zhou.floatingtranslator;

import org.junit.Test;

import static org.junit.Assert.*;

public class AsrNoiseGuardTest {
    @Test public void rejectsHallucinatedSymbolsAndLonelyLetters() {
        assertTrue(AsrTranscriptGuard.isLikelyNoise("!"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("！！"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("ا"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("ل"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("l"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("zzzzzzzz"));
        assertTrue(AsrTranscriptGuard.isLikelyNoise("<|endoftext|>"));
    }

    @Test public void keepsLegitimateEnglishSpeechAndVeryShortWords() {
        assertFalse(AsrTranscriptGuard.isLikelyNoise("I"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("A"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("No!"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("OK"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise(
            "but also when you're watching movies or listening to music."));
    }

    @Test public void supportsLegitimateCjkAndOtherLanguagePhrases() {
        assertFalse(AsrTranscriptGuard.isLikelyNoise("好"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("こんにちは"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("مرحبا"));
        assertFalse(AsrTranscriptGuard.isLikelyNoise("Apa khabar?"));
    }

    @Test public void strictEnglishStillRejectsNonLatinTranscript() {
        assertFalse(SingleLanguageAsrGuard.accepts("ا", "en", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("こんにちは", "en", ""));
        assertTrue(SingleLanguageAsrGuard.accepts("Good morning everyone", "en", ""));
    }
}
