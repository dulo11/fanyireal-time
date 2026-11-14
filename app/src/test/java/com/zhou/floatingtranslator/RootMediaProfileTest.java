package com.zhou.floatingtranslator;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class RootMediaProfileTest {
    @Test public void mediaAndCallSelectionsStayIndependent() {
        Context app = RuntimeEnvironment.getApplication();
        RootCallProfileStore.Profile call = new RootCallProfileStore.Profile(
            "com.example.call", "通话", RootCallProfileStore.TRANSPORT_ROOT, 1, 2, 48000, 2);
        RootCallProfileStore.Profile media = new RootCallProfileStore.Profile(
            "com.example.video", "直播", RootCallProfileStore.TRANSPORT_ROOT, 3, 4, 48000, 2);

        RootCallProfileStore.save(app, call);
        RootCallProfileStore.saveMedia(app, media);

        assertTrue(RootCallProfileStore.hasSelected(app));
        assertTrue(RootCallProfileStore.hasSelectedMedia(app));
        assertEquals("com.example.call", RootCallProfileStore.loadSelected(app).packageName);
        assertEquals("com.example.video", RootCallProfileStore.loadSelectedMedia(app).packageName);
        assertEquals(3, RootCallProfileStore.loadSelectedMedia(app).card);
    }
}
