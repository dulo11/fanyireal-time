package com.zhou.floatingtranslator;

import android.view.View;
import android.widget.Spinner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class FaceToFaceActivityTest {
    private Object field(Object activity, String name) throws Exception {
        java.lang.reflect.Field f = FaceToFaceActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(activity);
    }
    @Test public void pageCreatesAndAllLanguageModesAreUsable() throws Exception {
        try (org.robolectric.android.controller.ActivityController<FaceToFaceActivity> controller =
                Robolectric.buildActivity(FaceToFaceActivity.class).setup()) {
            FaceToFaceActivity activity = controller.get();
            Spinner mode = (Spinner) field(activity, "recognitionMode");
            Spinner input = (Spinner) field(activity, "fixedInputLanguage");
            assertEquals(3, mode.getCount());
            assertNotNull(field(activity, "languageStatus"));
            for (int index : new int[]{0, 1, 2, 0}) {
                mode.setSelection(index);
                mode.getOnItemSelectedListener().onItemSelected(mode, null, index, index);
                assertEquals(index == 2 ? View.VISIBLE : View.GONE, input.getVisibility());
            }
        }
    }
}
