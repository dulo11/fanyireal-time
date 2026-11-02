package com.zhou.floatingtranslator;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Event IDs distinguish repeated utterances; no text-based merging. */
public final class FailureStore {
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "failed-utterances.json")); }
    public static synchronized JSONArray read(Context c) throws Exception {
        AtomicFile f=file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile()+".bak").exists()) return new JSONArray();
        return new JSONArray(new String(f.readFully(),StandardCharsets.UTF_8));
    }
    private static void write(Context c, JSONArray data) throws Exception {
        AtomicFile f=file(c); FileOutputStream stream=null;
        try { stream=f.startWrite(); stream.write(data.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(stream); }
        catch(Exception e) { if(stream!=null)f.failWrite(stream); throw e; }
    }
    public static synchronized void add(Context c,String text,String source,String target,String engine,String category,String reason) {
        if(text==null || text.trim().isEmpty())return;
        try {
            JSONArray a=read(c);
            a.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("time",System.currentTimeMillis())
                .put("text",text).put("source",source).put("target",target).put("engine",engine)
                .put("category",category).put("reason",reason));
            write(c,a);
        } catch(Exception e) { RunPolicy.prefs(c).edit().putString("recovery_error","失败句保存失败："+e.getMessage()).apply(); }
    }
    public static synchronized void remove(Context c,String id) throws Exception {
        JSONArray a=read(c),next=new JSONArray();
        for(int i=0;i<a.length();i++)if(!id.equals(a.getJSONObject(i).getString("id")))next.put(a.get(i));
        write(c,next);
    }
    public static synchronized void clear(Context c) throws Exception {write(c,new JSONArray());}
}
