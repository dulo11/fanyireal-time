package com.zhou.floatingtranslator;

import org.junit.Test;
import static org.junit.Assert.*;

public class RegressionTest {
    @Test public void stableSupersedesDev() { assertTrue(AppVersion.compare("0.7.0", "0.7.0-dev5") > 0); }
    @Test public void devNeverDowngradesStable() { assertTrue(AppVersion.compare("0.7.0-dev99", "0.7.0") < 0); }
    @Test public void numericDevelopmentVersions() { assertTrue(AppVersion.compare("0.7.0-dev10", "0.7.0-dev9") > 0); }
    @Test public void minorVersionBeatsDevelopmentSuffix() { assertTrue(AppVersion.compare("0.8.0-dev1", "0.7.0") > 0); }
    @Test public void prefixMetadataAndMissingPatch() { assertEquals(0, AppVersion.compare("v0.7.0+build42", "0.7")); }
    @Test public void olderStableDoesNotDowngrade() { assertTrue(AppVersion.compare("0.6.0", "0.7.0-dev5") < 0); }

    @Test public void finalUtterancesAreNotOverwritten() {
        SpeechQueue queue = new SpeechQueue();
        for (int i = 0; i < 100; i++) queue.offer("句子" + i, true, "ja");
        for (int i = 0; i < 100; i++) {
            SpeechQueue.Item item = queue.poll();
            assertEquals("句子" + i, item.text); assertEquals("ja", item.language); assertTrue(item.complete);
        }
        assertNull(queue.poll());
    }

    @Test public void finalsReplaceOnlyTheirInterimHypotheses() {
        SpeechQueue queue = new SpeechQueue();
        queue.offer("hel", false, "en"); queue.offer("hello", true, "en");
        queue.offer("wor", false, "en"); queue.offer("world", true, "en");
        assertEquals("hello", queue.poll().text); assertEquals("world", queue.poll().text); assertNull(queue.poll());
    }

    @Test public void partialsCoalesceWithoutReplacingFinals() {
        SpeechQueue queue = new SpeechQueue();
        queue.offer("first", true, "en"); queue.offer("sec", false, "en"); queue.offer("second", false, "en");
        assertEquals("first", queue.poll().text); assertEquals("second", queue.poll().text); assertNull(queue.poll());
    }

    @Test public void stoppingClearsPendingUtterances() {
        SpeechQueue queue = new SpeechQueue(); queue.offer("old", true, "ja"); queue.clear(); assertNull(queue.poll());
    }

    @Test public void sharedScriptsDoNotInventLanguage() {
        assertEquals("", OfflineFirstTranslationRouter.scriptHint("selamat pagi"));
        assertEquals("", OfflineFirstTranslationRouter.scriptHint("salamat"));
        assertEquals("", OfflineFirstTranslationRouter.scriptHint("東京"));
    }

    @Test public void distinctiveScriptsStillProvideFallback() {
        assertEquals("ja", OfflineFirstTranslationRouter.scriptHint("こんにちは"));
        assertEquals("ko", OfflineFirstTranslationRouter.scriptHint("안녕"));
    }

    @Test public void shortHanTextUsesConfiguredSourceFallback() {
        assertTrue(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("土"));
        assertTrue(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("東京"));
        assertTrue(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("中国語"));
    }

    @Test public void distinctiveOrLongTextCanUseAutomaticDetection() {
        assertFalse(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("こんにちは"));
        assertFalse(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("hello world"));
        assertFalse(OfflineFirstTranslationRouter.shouldPreferConfiguredSourceForShortText("selamat pagi"));
    }

    @Test public void languageAliasesAreEquivalent() {
        assertTrue(OfflineFirstTranslationRouter.sameLanguage("fil-PH", "tl"));
        assertTrue(OfflineFirstTranslationRouter.sameLanguage("iw-IL", "he"));
        assertTrue(OfflineFirstTranslationRouter.sameLanguage("in-ID", "id"));
        assertTrue(OfflineFirstTranslationRouter.sameLanguage("zh-CN", "zh"));
    }

    @Test public void asrControlTokensNeverReachConversation() {
        assertEquals("", AsrTranscriptGuard.clean("<|endoftext|>"));
        assertEquals("hello world", AsrTranscriptGuard.clean("hello <|endoftext|> world"));
        assertEquals("你好", AsrTranscriptGuard.clean("<|startoftranscript|> 你好 <|endoftext|>"));
    }

    @Test public void mixedLatinWordsDoNotInventEnglish() {
        assertEquals("zh", AsrTranscriptGuard.stabilizeLanguage("OK OK，好啊，有。", "zh", "zh"));
    }

    @Test public void incompatibleScriptDiscardsNoisyTagWithoutInventingLanguage() {
        assertEquals("en", AsrTranscriptGuard.stabilizeLanguage("hello こんにちは", "en", "zh"));
        assertEquals("", AsrTranscriptGuard.stabilizeLanguage("안녕하세요", "en", "zh"));
    }
}
