package com.zhou.floatingtranslator;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class RepeatedSpeechHistoryTest {
    @Test public void identicalTranslationsAreSavedAndExportedAsSeparateEvents() {
        Context context = RuntimeEnvironment.getApplication();
        HistoryStore.clear(context);
        for (int i = 0; i < 3; i++) HistoryStore.append(context, "hello", "你好");
        assertEquals(3, HistoryStore.count(context));
        assertEquals(3, HistoryStore.buildTxt(context).split("hello", -1).length - 1);
        assertEquals(3, HistoryStore.buildSrt(context).split("-->", -1).length - 1);
        HistoryStore.clear(context);
    }

    @Test public void systemPcmDeliversEveryRepeatedFinal() throws Exception {
        List<String> received = new ArrayList<>();
        AndroidPcmSpeechEngine engine = new AndroidPcmSpeechEngine(
            RuntimeEnvironment.getApplication(), "single", "en-US",
            new AndroidPcmSpeechEngine.Callback() {
                public void onStatus(String message) {}
                public void onReady(String name) {}
                public void onText(String text, String lang) { received.add(text); }
                public void onError(String message, boolean fatal) { fail(message); }
            });
        try {
            Bundle result = new Bundle();
            result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(Arrays.asList("hello")));
            for (int i = 0; i < 3; i++) {
                engine.onResults(result);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            assertEquals(Arrays.asList("hello", "hello", "hello"), received);
        } finally { engine.close(); }
    }
}
