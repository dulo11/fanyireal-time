package com.zhou.floatingtranslator;

import org.junit.Test;

import static org.junit.Assert.*;

public class SingleLanguageAsrGuardTest {
    @Test public void malayAcceptsRumiButRejectsWrongScripts() {
        assertTrue(SingleLanguageAsrGuard.accepts("Selamat pagi, apa khabar?", "ms", ""));
        assertTrue(SingleLanguageAsrGuard.accepts("Terima kasih", "ms-MY", "malay"));
        assertFalse(SingleLanguageAsrGuard.accepts("ال", "ms", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("こんにちは", "ms", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("你好", "ms", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("안녕하세요", "ms", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("<|endoftext|>", "ms", ""));
    }

    @Test public void asrLanguageEvidenceOverridesSameScript() {
        assertFalse(SingleLanguageAsrGuard.accepts("Hello, good morning", "ms", "en"));
        assertFalse(SingleLanguageAsrGuard.accepts("Apa khabar?", "ms", "id"));
        assertTrue(SingleLanguageAsrGuard.accepts("Apa khabar?", "ms", ""));
    }

    @Test public void liveLatinSpeechIgnoresUnreliableModelLanguageTags() {
        // Omnilingual's language label is not a reliable basis for dropping
        // a clearly Latin-script English transcript. Text language ID follows.
        assertTrue(SingleLanguageAsrGuard.acceptsLive(
            "Just look around and say the names of objects in English", "en", "es"));
        assertTrue(SingleLanguageAsrGuard.acceptsLive(
            "I will show you how to fix them", "en", "ar"));
        assertFalse(SingleLanguageAsrGuard.acceptsLive("ايسلوي", "en", "ar"));
        assertFalse(SingleLanguageAsrGuard.acceptsLive("こんにちは", "en", "ja"));
        assertFalse(SingleLanguageAsrGuard.acceptsLive("Bonjour", "ja", "fr"));
    }

    @Test public void otherLanguageScriptsRemainAvailableWhenSelected() {
        assertTrue(SingleLanguageAsrGuard.accepts("こんにちは東京", "ja", ""));
        assertTrue(SingleLanguageAsrGuard.accepts("你好", "zh", ""));
        assertTrue(SingleLanguageAsrGuard.accepts("مرحبا", "ar", ""));
        assertTrue(SingleLanguageAsrGuard.accepts("สวัสดี", "th", ""));
        assertFalse(SingleLanguageAsrGuard.accepts("Bonjour", "ja", ""));
    }

    @Test public void uncertainTextLanguageIsNotOverFiltered() {
        assertFalse(SingleLanguageAsrGuard.needsLanguageId("Selamat pagi", "ms"));
        assertTrue(SingleLanguageAsrGuard.needsLanguageId("Selamat pagi, apa khabar semua?", "ms"));
        assertFalse(SingleLanguageAsrGuard.rejectConfidentOther("ms", "id", 0.99f, 0.01f));
        assertFalse(SingleLanguageAsrGuard.rejectConfidentOther("ms", "en", 0.80f, 0.01f));
        assertTrue(SingleLanguageAsrGuard.rejectConfidentOther("ms", "en", 0.99f, 0.01f));
        assertFalse(SingleLanguageAsrGuard.rejectConfidentOther("ms", "en", 0.99f, 0.25f));
    }
}
