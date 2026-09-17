package com.zhou.floatingtranslator;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.util.*;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class ScreenTranslationRegressionTest {
    Context context = RuntimeEnvironment.getApplication();
    static class Output implements ScreenTranslationClient.Callback {
        List<ScreenTranslationClient.Result> results;
        int progress;
        public void onSuccess(List<ScreenTranslationClient.Result> results, String name) { this.results = results; }
        public void onError(String message) { fail(message); }
        public void onProgress(List<ScreenTranslationClient.Result> results, int completed, int total, String engine) { progress++; }
    }
    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, v);
    }
    private static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args);
    }
    @Test public void splittingPreservesLongTextAndEmojiExactly() {
        String original = ("Very long text. 中文😀\n").repeat(5000);
        List<String> chunks = ScreenTextPlan.split(original, 1000);
        assertEquals(original, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 1000);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }
    @Test public void allBlocksSurviveBeyondOldItemAndCharacterLimits() {
        List<String> input = new ArrayList<>();
        for (int i=0;i<95;i++) input.add("Block " + i + ": " + "hello ".repeat(250));
        List<String> sent = new ArrayList<>();
        Output out = new Output();
        try (ScreenTranslationClient client = new ScreenTranslationClient(context, (batch, target, callback) -> {
            assertTrue(batch.size() <= 60);
            assertTrue(batch.stream().mapToInt(String::length).sum() <= 30000);
            sent.addAll(batch);
            List<ScreenTranslationClient.Result> results = new ArrayList<>();
            for (String piece : batch) results.add(new ScreenTranslationClient.Result("en", piece));
            callback.onSuccess(results, "fake");
        })) {
            client.translate(input, "zh", out);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(out.results); assertEquals(95, out.results.size());
            for (int i=0;i<input.size();i++) assertEquals(input.get(i).trim(), out.results.get(i).translated.replace("\n", ""));
            assertTrue(out.progress > 1);
        }
    }
    @Test public void failedBatchDoesNotDiscardLaterBlocksOrAlignment() {
        List<String> input = new ArrayList<>();
        for (int i=0;i<125;i++) input.add("item " + i);
        int[] calls = {0}; Output out = new Output();
        try (ScreenTranslationClient client = new ScreenTranslationClient(context, (batch, target, callback) -> {
            if (++calls[0] == 1) { callback.onError("quota"); return; }
            List<ScreenTranslationClient.Result> results = new ArrayList<>();
            for (String piece : batch) results.add(new ScreenTranslationClient.Result("en", "translated " + piece));
            callback.onSuccess(results, "fake");
        })) {
            client.translate(input, "zh", out); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(125, out.results.size()); assertEquals("quota", out.results.get(0).error);
            assertEquals("translated item 60", out.results.get(60).translated);
            assertEquals("translated item 124", out.results.get(124).translated);
        }
    }
    @Test public void missingResponseBecomesVisibleErrorInsteadOfShiftingRows() {
        Output out = new Output();
        try (ScreenTranslationClient client = new ScreenTranslationClient(context, (batch, target, callback) ->
            callback.onSuccess(Collections.singletonList(new ScreenTranslationClient.Result("en", "one")), "fake"))) {
            client.translate(Arrays.asList("first", "second"), "zh", out);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("one", out.results.get(0).translated);
            assertFalse(out.results.get(1).error.isEmpty()); assertTrue(out.results.get(1).translated.contains("second"));
        }
    }
    @Test public void longNodeAndSingleCharacterAreAccepted() throws Exception {
        Method m=ScreenTranslationAccessibilityService.class.getDeclaredMethod("isTranslatableText", String.class);
        m.setAccessible(true);
        assertEquals(true, m.invoke(null,"long message ".repeat(1000)));
        assertEquals(true, m.invoke(null,"Go")); assertEquals(true, m.invoke(null,"家"));
        assertEquals(false, m.invoke(null,"12345"));
    }
    @Test public void smallChildCannotEraseUniqueParentParagraph() throws Exception {
        ScreenTranslationOverlayView view = new ScreenTranslationOverlayView(context);
        view.setEntries(Arrays.asList(
            new ScreenTranslationOverlayView.Entry(new Rect(0,0,500,800), "Header and unique body", "完整正文"),
            new ScreenTranslationOverlayView.Entry(new Rect(0,0,100,30), "Header", "标题"),
            new ScreenTranslationOverlayView.Entry(new Rect(0,40,100,70), "Button", "按钮")));
        assertEquals(3, ((List<?>)field(view,"entries")).size());
    }
    @Test public void overlappingExactDuplicateIsRemoved() throws Exception {
        ScreenTranslationOverlayView view=new ScreenTranslationOverlayView(context);
        view.setEntries(Arrays.asList(
            new ScreenTranslationOverlayView.Entry(new Rect(0,0,200,100),"same","相同"),
            new ScreenTranslationOverlayView.Entry(new Rect(0,0,210,110),"same","相同")));
        assertEquals(1,((List<?>)field(view,"entries")).size());
    }
    @Test public void ocrAddsOnlyUncoveredTextAtMatchingPosition() {
        Rect whole=new Rect(0,0,500,800), line=new Rect(20,20,300,50);
        assertTrue(ScreenCoverage.covers("Header\nfull body",whole,"full body",line));
        assertFalse(ScreenCoverage.covers("Header",whole,"new image text",line));
        assertFalse(ScreenCoverage.covers("Header",line,"Header and unseen body",whole));
        assertFalse(ScreenCoverage.covers("Header",line,"Header",new Rect(20,400,300,430)));
    }
    @Test public void scrollInvalidatesOldWorkAndQueuesRescan() throws Exception {
        ScreenTranslationAccessibilityService service=Robolectric.buildService(ScreenTranslationAccessibilityService.class).create().get();
        android.content.SharedPreferences prefs=context.getSharedPreferences("screen-test",0);
        prefs.edit().putBoolean(ScreenTranslationAccessibilityService.PREF_SCREEN_CONTINUOUS,true).commit();
        set(service,"prefs",prefs); set(service,"translating",true);
        AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        event.setPackageName("org.telegram.messenger"); service.onAccessibilityEvent(event);
        assertEquals(1,field(service,"screenGeneration")); assertEquals(true,field(service,"rescanPending"));
        invoke(service,"finishScan",new Class<?>[0]);
        Handler handler=(Handler)field(service,"main");
        assertTrue(handler.hasCallbacks((Runnable)field(service,"eventScan")));
        handler.removeCallbacksAndMessages(null); service.onDestroy();
    }
}
