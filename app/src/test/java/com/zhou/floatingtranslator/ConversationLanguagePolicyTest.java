package com.zhou.floatingtranslator;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationLanguagePolicyTest {
    private ConversationLanguagePolicy.Route route(String mode, String text, String asr, String id, float confidence) {
        return ConversationLanguagePolicy.decide(mode, "zh", "ja", "ja", "en", text, asr, id, confidence);
    }
    @Test public void englishNeverUsesJapaneseFallbackAsTarget() {
        ConversationLanguagePolicy.Route r = route("auto", "How are you?", "en", "en", .99f);
        assertTrue(r.valid()); assertEquals("en", r.source); assertEquals("zh", r.target); assertEquals("en", r.partner);
    }
    @Test public void englishProviderLanguageNamesAreAccepted() {
        ConversationLanguagePolicy.Route r = route("auto", "How are you?", "English", "en", .99f);
        assertTrue(r.valid()); assertEquals("zh", r.target);
    }
    @Test public void englishTranscriptWithStaleChineseTagUsesTextEvidence() {
        ConversationLanguagePolicy.Route r = route("auto", "How are you?", "zh", "en", .99f);
        assertTrue(r.valid()); assertEquals("en", r.source); assertEquals("zh", r.target);
    }
    @Test public void weakTextIdentificationCannotFlipReliableAsr() {
        ConversationLanguagePolicy.Route r = route("auto", "hello", "en", "de", .52f);
        assertTrue(r.valid()); assertEquals("en", r.source); assertEquals("zh", r.target);
    }
    @Test public void genuineLatinLanguageConflictRequiresConfirmation() {
        assertFalse(route("auto", "selamat pagi", "en", "ms", .95f).valid());
    }
    @Test public void missingAndUncertainLanguageDoesNotGuessJapanese() {
        assertFalse(route("auto", "OK", "", "en", .55f).valid());
    }
    @Test public void englishCanBeConfirmedWithoutAsrLanguage() {
        assertEquals("zh", route("auto", "Hello how are you?", "", "en", .99f).target);
    }
    @Test public void myReplyUsesMostRecentlyConfirmedPartner() {
        ConversationLanguagePolicy.Route r = ConversationLanguagePolicy.decide("auto", "zh", "ja", "en", "en",
            "我很好", "zh", "zh", .99f);
        assertTrue(r.valid()); assertEquals("en", r.target);
    }
    @Test public void sameSpeakerCanKeepSpeakingWithoutAlternation() {
        for (int i = 0; i < 5; i++) assertEquals("zh", route("auto", "How are you?", "en", "en", .99f).target);
    }
    @Test public void limitedPairRejectsAThirdLanguage() {
        assertFalse(route("pair", "Hello", "en", "en", .99f).valid());
    }
    @Test public void limitedPairAcceptsJapanese() {
        assertEquals("zh", route("pair", "こんにちは", "ja", "ja", .99f).target);
    }
    @Test public void limitedPairDoesNotRememberUnrelatedPriorLanguage() {
        ConversationLanguagePolicy.Route r = ConversationLanguagePolicy.decide("pair", "zh", "ja", "en", "en",
            "我很好", "zh", "zh", .99f);
        assertTrue(r.valid()); assertEquals("ja", r.target); assertEquals("ja", r.partner);
    }
    @Test public void fixedEnglishIsAlwaysEnglishToChinese() {
        ConversationLanguagePolicy.Route r = route("fixed", "How are you?", "ja", "de", .99f);
        assertTrue(r.valid()); assertEquals("en", r.source); assertEquals("zh", r.target);
    }
    @Test public void englishMisheardAsChineseDoesNotBecomeJapaneseTranslationInFixedMode() {
        ConversationLanguagePolicy.Route r = route("fixed", "好啊，哟。", "zh", "zh", .99f);
        assertFalse(r.valid()); assertEquals("", r.target);
    }
    @Test public void languageAliasesAndControlTagsNormalize() {
        assertEquals("en", AsrTranscriptGuard.normalizeTag("<|en|>"));
        assertEquals("en", AsrTranscriptGuard.normalizeTag("English"));
        assertEquals("tl", AsrTranscriptGuard.normalizeTag("fil-PH"));
        assertEquals("ja", AsrTranscriptGuard.normalizeTag("Japanese"));
    }
    @Test public void latinScriptIsNotAnEnglishDetector() {
        assertEquals("", AsrTranscriptGuard.stabilizeLanguage("selamat pagi", "zh", "zh"));
        assertEquals("ms", route("auto", "selamat pagi", "ms", "ms", .99f).source);
    }
    @Test public void monolingualModelsCannotPretendToBeAutomaticBilingual() {
        assertFalse(ConversationLanguagePolicy.supportsAutomatic("vosk", "zh", "en"));
        assertFalse(ConversationLanguagePolicy.supportsAutomatic("reazon_ja", "zh", "ja"));
        assertFalse(ConversationLanguagePolicy.supportsAutomatic("parakeet_ja", "zh", "ja"));
    }
    @Test public void fixedInputRequiresAnEngineWithLanguageControl() {
        assertFalse(ConversationLanguagePolicy.supportsFixed("qwen3_asr_06b", "en"));
        assertFalse(ConversationLanguagePolicy.supportsFixed("omnilingual_300m", "en"));
        assertTrue(ConversationLanguagePolicy.supportsFixed("whisper_small", "en"));
        assertTrue(ConversationLanguagePolicy.supportsFixed("sensevoice", "en"));
    }
    @Test public void senseVoiceRequiresBothLanguagesToBeSupported() {
        assertFalse(ConversationLanguagePolicy.supportsAutomatic("sensevoice", "zh", "vi"));
        assertTrue(ConversationLanguagePolicy.supportsAutomatic("sensevoice", "zh", "en"));
    }
    @Test public void japaneseOnlyModelCannotBeUsedForFixedEnglish() {
        assertFalse(ConversationLanguagePolicy.supportsFixed("reazon_ja", "en"));
        assertTrue(ConversationLanguagePolicy.supportsFixed("reazon_ja", "ja"));
    }
}
