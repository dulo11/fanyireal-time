package com.zhou.floatingtranslator;

import org.junit.Test;
import java.util.HashSet;
import java.util.Arrays;
import static org.junit.Assert.*;

/** New optional model options must be truthful and independently selectable. */
public class ExtendedOfflineAsrCatalogTest {
    @Test public void allModelIdsUniqueAndEveryModelHasParameterLabel() {
        HashSet<String> ids = new HashSet<>();
        for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all()) {
            assertTrue("duplicate model ID: " + model.id, ids.add(model.id));
            assertNotNull(model.url);
            assertTrue("only HTTPS model sources", model.url.startsWith("https://"));
            assertTrue(model.optionLabel().contains(model.parameterText()));
            assertTrue("empty parameter label: " + model.id, !model.parameterText().isEmpty());
        }
    }

    @Test public void requestedModelsAreInTheSelectableCatalog() {
        String[] models = {
            OfflineAsrModelCatalog.QWEN3_17B,
            OfflineAsrModelCatalog.WHISPER_TURBO,
            OfflineAsrModelCatalog.WHISPER_LARGE_V3,
            OfflineAsrModelCatalog.PARAKEET_EN_V2,
            OfflineAsrModelCatalog.FUNASR_NANO,
            OfflineAsrModelCatalog.MOONSHINE_TINY_EN,
            OfflineAsrModelCatalog.MOONSHINE_BASE_EN,
            OfflineAsrModelCatalog.MOONSHINE_V2_EN,
            OfflineAsrModelCatalog.MOONSHINE_V2_JA,
            OfflineAsrModelCatalog.MOONSHINE_V2_VI,
            OfflineAsrModelCatalog.ZIPFORMER_STREAM_EN
        };
        for (String id : models) assertNotNull("missing model " + id, OfflineAsrModelCatalog.find(id));
        assertEquals("17亿参数", OfflineAsrModelCatalog.parameterText(OfflineAsrModelCatalog.QWEN3_17B));
        assertEquals("8.09亿参数", OfflineAsrModelCatalog.parameterText(OfflineAsrModelCatalog.WHISPER_TURBO));
        assertEquals("15.5亿参数", OfflineAsrModelCatalog.parameterText(OfflineAsrModelCatalog.WHISPER_LARGE_V3));
    }

    @Test public void modelLanguageLimitsAreNotHidden() {
        assertFalse(OfflineAsrModelCatalog.supportsLanguage(OfflineAsrModelCatalog.PARAKEET_EN_V2, "ja"));
        assertTrue(OfflineAsrModelCatalog.supportsLanguage(OfflineAsrModelCatalog.PARAKEET_EN_V2, "en"));
        assertFalse(OfflineAsrModelCatalog.supportsLanguage(OfflineAsrModelCatalog.MOONSHINE_V2_JA, "en"));
        assertTrue(OfflineAsrModelCatalog.supportsLanguage(OfflineAsrModelCatalog.MOONSHINE_V2_JA, "ja"));
        assertFalse(OfflineAsrModelCatalog.supportsLanguage(OfflineAsrModelCatalog.FUNASR_NANO, "ms"));
        assertFalse(OfflineAsrModelCatalog.supportsMixedLanguages(
            OfflineAsrModelCatalog.ZIPFORMER_STREAM_EN, Arrays.asList("en", "ja")));
        assertTrue(OfflineAsrModelCatalog.supportsMixedLanguages(
            OfflineAsrModelCatalog.QWEN3_ASR, Arrays.asList("en", "ja", "ms")));
    }
}
