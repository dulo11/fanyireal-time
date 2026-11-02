package com.zhou.floatingtranslator;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=android.app.Application.class)
public class RecoveryTest {
    private Context c;
    @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();RunPolicy.prefs(c).edit().clear().commit();HistoryStore.clear(c);FailureStore.clear(c);c.getSharedPreferences("model_download_tasks",0).edit().clear().commit();}
    @Test public void repeatedFailuresStaySeparateAndRetryIsEventIdempotent()throws Exception{
        FailureStore.add(c,"hello","en","zh","auto","realtime","offline");FailureStore.add(c,"hello","en","zh","auto","realtime","offline");
        JSONArray rows=FailureStore.read(c);assertEquals(2,rows.length());assertNotEquals(rows.getJSONObject(0).getString("id"),rows.getJSONObject(1).getString("id"));
        HistoryStore.appendRecovered(c,rows.getJSONObject(0),"你好");HistoryStore.appendRecovered(c,rows.getJSONObject(0),"你好");HistoryStore.appendRecovered(c,rows.getJSONObject(1),"你好");
        assertEquals(2,HistoryStore.count(c));FailureStore.remove(c,rows.getJSONObject(0).getString("id"));assertEquals(1,FailureStore.read(c).length());
    }
    @Test public void settingsCannotImportSecretsConsentOrWrongTypes()throws Exception{
        for(JSONObject value:new JSONObject[]{new JSONObject().put("cloud_consent",true),new JSONObject().put("groq_api_key","x"),new JSONObject().put("font_size","huge"),new JSONObject().put("source_index",999)}){
            try{UserArchive.validateSettings(value);fail("unsafe setting accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void sceneRestoreDoesNotRestoreCloudAuthorizationOrRootReadiness()throws Exception{
        RunPolicy.prefs(c).edit().putBoolean("cloud_consent",true).putString("run_mode","online").putBoolean("root_call_profile.test.app.ready",true).commit();
        UserArchive.applySettings(c,new JSONObject().put("source_index",2).put("root_call_profile.test.app.rate",16000));
        assertFalse(RunPolicy.cloud(c));assertTrue(RunPolicy.offline(c));assertFalse(RunPolicy.prefs(c).getBoolean("root_call_profile.test.app.ready",true));
    }
    @Test public void snapshotsPreserveRepeatedHistoryButExcludeApiKeys()throws Exception{
        HistoryStore.append(c,"a","b");HistoryStore.append(c,"a","b");RunPolicy.prefs(c).edit().putString("azure_key","secret").putInt("target_index",0).commit();
        JSONObject j=UserArchive.snapshot(c);UserArchive.validate(j);assertEquals(2,j.getJSONArray("history").length());assertFalse(j.toString().contains("secret"));
    }
    @Test public void interruptedDownloadsNeedExplicitResumeAndOfflineCannotResume()throws Exception{
        DownloadTasks.enqueue(c,"whisper_small");DownloadTasks.update(c,"whisper_small","running","10%");DownloadTasks.recover(c);assertNull(DownloadTasks.next(c));
        RunPolicy.prefs(c).edit().putString("run_mode","offline").commit();try{DownloadTasks.resume(c);fail();}catch(IllegalStateException expected){}
        RunPolicy.prefs(c).edit().putString("run_mode","hybrid").commit();DownloadTasks.resume(c);assertEquals("whisper_small",DownloadTasks.next(c));
    }
    @Test public void captureProtectionUsesHysteresisWithoutDroppingQueue(){
        assertTrue(SessionHealth.shouldHold(3,false,0,false));assertTrue(SessionHealth.shouldHold(0,true,0,false));assertTrue(SessionHealth.shouldHold(0,false,24,false));
        assertTrue(SessionHealth.shouldHold(0,false,4,true));assertFalse(SessionHealth.shouldHold(0,false,3,true));assertFalse(SessionHealth.shouldHold(0,false,4,false));
    }
}
