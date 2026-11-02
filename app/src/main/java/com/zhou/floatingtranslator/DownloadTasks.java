package com.zhou.floatingtranslator;
import android.content.Context;
import org.json.*;
import java.io.IOException;

/** A durable work list, separate from download cache files. */
public final class DownloadTasks {
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("model_download_tasks",0);}
    public static synchronized JSONArray all(Context c)throws Exception{return new JSONArray(prefs(c).getString("tasks","[]"));}
    private static void write(Context c,JSONArray a)throws Exception{if(!prefs(c).edit().putString("tasks",a.toString()).commit())throw new IOException("无法保存下载任务");}
    static boolean valid(String id){return OfflineAsrModelCatalog.find(id)!=null||id.equals("vad")||(id.startsWith("vosk:")&&OfflineSpeechEngine.supports(id.substring(5)))||(id.startsWith("text:")&&com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages().contains(id.substring(5)));}
    public static synchronized void enqueue(Context c,String... ids)throws Exception{
        RunPolicy.requireDownload(c);JSONArray a=all(c);
        for(String id:ids){if(!valid(id))throw new IOException("未知下载任务");boolean found=false;
            for(int i=0;i<a.length();i++){JSONObject task=a.getJSONObject(i);if(id.equals(task.getString("id"))){if(!"running".equals(task.optString("state")))task.put("state","pending").put("note","等待检查已有文件");found=true;break;}}
            if(!found)a.put(new JSONObject().put("id",id).put("state","pending").put("note","等待下载"));
        }write(c,a);
    }
    public static synchronized void update(Context c,String id,String state,String note)throws Exception{
        JSONArray a=all(c);for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if(id.equals(t.getString("id")))t.put("state",state).put("note",note);}write(c,a);
    }
    public static synchronized void recover(Context c)throws Exception{JSONArray a=all(c);for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if("running".equals(t.optString("state")))t.put("state","paused").put("note","任务被中断；点击继续，缓存会保留");}write(c,a);}
    public static synchronized String next(Context c)throws Exception{JSONArray a=all(c);for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if("pending".equals(t.optString("state")))return t.getString("id");}return null;}
    public static synchronized void resume(Context c)throws Exception{RunPolicy.requireDownload(c);JSONArray a=all(c);for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);if(!"done".equals(t.optString("state")))t.put("state","pending");}write(c,a);}
    public static String summary(Context c){try{StringBuilder b=new StringBuilder();JSONArray a=all(c);if(a.length()==0)return "没有下载任务";for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);b.append(t.getString("id")).append(" · ").append(t.getString("state")).append("\n").append(t.optString("note")).append('\n');}return b.toString();}catch(Exception e){return "下载队列读取失败："+e.getMessage();}}
}
