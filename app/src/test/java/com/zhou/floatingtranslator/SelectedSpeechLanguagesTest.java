package com.zhou.floatingtranslator;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SelectedSpeechLanguagesTest {
    @Test public void selectedLanguagesAllowAnyCombination() {
        assertEquals(Arrays.asList("en", "ja", "ms", "vi"),
            SelectedSpeechLanguages.parse("en,ja,ms,vi,en,not-a-language"));
        assertEquals(Arrays.asList("en", "ja", "th"),
            SelectedSpeechLanguages.parse("english,ja,th"));
        assertEquals("en,ja,ms", SelectedSpeechLanguages.serialize(Arrays.asList("en","ja","ms","en")));
    }

    @Test public void legacyTwoLanguageSelectionPreserved() {
        assertEquals(Arrays.asList("ja", "en"),
            SelectedSpeechLanguages.forLegacy(SherpaSpeechEngine.LANG_JA_EN, ""));
        assertEquals(Arrays.asList("zh", "en"),
            SelectedSpeechLanguages.forLegacy(SherpaSpeechEngine.LANG_ZH_EN, ""));
    }

    @Test public void englishAndJapaneseSelectedTranslateOnlyTheseLanguages() {
        List<String> selected = SelectedSpeechLanguages.parse("en,ja");
        assertEquals("en", SelectedSpeechLanguages.choose(
            "Just look around and say the names of objects in English", "ar", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("en", .97f))));
        assertEquals("ja", SelectedSpeechLanguages.choose(
            "こんにちは、今日はいい天気ですね", "en", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("ja", .95f))));
        // Spanish is a different language even though it shares English's script.
        assertEquals("", SelectedSpeechLanguages.choose(
            "Hola, buenos dias amigos", "es", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("es", .98f))));
        assertEquals("", SelectedSpeechLanguages.choose(
            "مرحبا", "ar", selected, Collections.emptyList()));
    }

    @Test public void unselectedLatinAsrTagNeedsIndependentEvidence() {
        List<String> selected = SelectedSpeechLanguages.parse("en,ja");
        assertEquals("", SelectedSpeechLanguages.choose(
            "Hola buenos dias", "es", selected, Collections.emptyList()));
        // An ASR mislabel should not suppress a complete English sentence
        // when independent text identification is strongly English.
        assertEquals("en", SelectedSpeechLanguages.choose(
            "Just look around and name objects in English", "es", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("en", .96f))));
    }

    @Test public void arbitraryThreeWayMixRespectsExplicitEvidence() {
        List<String> selected = SelectedSpeechLanguages.parse("en,ms,vi");
        assertEquals("vi", SelectedSpeechLanguages.choose(
            "Xin chào, tôi tên là Linh", "vi", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("vi", .86f))));
        assertEquals("ms", SelectedSpeechLanguages.choose(
            "Terima kasih kerana membantu saya", "ms", selected,
            Arrays.asList(new SelectedSpeechLanguages.Evidence("ms", .93f))));
        assertEquals("", SelectedSpeechLanguages.choose(
            "Good morning everyone", "", selected, Collections.emptyList()));
    }

    @Test public void extendedScriptLanguagesAreNotMistakenForEnglish() {
        List<String> selected = SelectedSpeechLanguages.parse("en,ta,te,kn,gu,ka,bn");
        assertEquals("en", SelectedSpeechLanguages.choose(
            "Hello everyone", "", selected, null));
        assertEquals("ta", SelectedSpeechLanguages.choose("வணக்கம்", "", selected, null));
        assertEquals("te", SelectedSpeechLanguages.choose("నమస్కారం", "", selected, null));
        assertEquals("kn", SelectedSpeechLanguages.choose("ನಮಸ್ಕಾರ", "", selected, null));
        assertEquals("gu", SelectedSpeechLanguages.choose("નમસ્તે", "", selected, null));
        assertEquals("ka", SelectedSpeechLanguages.choose("გამარჯობა", "", selected, null));
        assertEquals("bn", SelectedSpeechLanguages.choose("নমস্কার", "", selected, null));
    }

    @Test public void unambiguousShortWordsHaveAPath() {
        List<String> selected = SelectedSpeechLanguages.parse("en,ja");
        assertEquals("en", SelectedSpeechLanguages.choose("OK", "", selected, null));
        assertEquals("ja", SelectedSpeechLanguages.choose("こんにちは", "", selected, null));
    }
}
