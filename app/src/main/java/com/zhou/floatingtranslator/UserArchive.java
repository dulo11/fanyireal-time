package com.zhou.floatingtranslator;

import android.content.*;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit allowlist: credentials, permissions, cloud consent and service state never travel. */
public final class UserArchive {
    private static final Set<String> KEYS=new HashSet<>(Arrays.asList(
        "source_index","target_index","face_source_index","face_target_index","asr_mode","engine_id","language_mode",
        "auto_language_enabled","input_mode","mic_processing","asr_precision","asr_conversation_profile","model_goal",
        "font_size","show_original","show_diagnostics","translation_only_overlay","subtitle_alpha","subtitle_y","output_mode",
        "session_protection","root_call_selected_package"));
    static boolean allowed(String key){return KEYS.contains(key)||key.matches("root_call_profile\\.[a-zA-Z0-9_.]+\\.(label|transport|card|device|rate|channels)");}
    public static JSONObject settings(Context c)throws Exception {
        JSONObject j=new JSONObject();for(Map.Entry<String,?> e:RunPolicy.prefs(c).getAll().entrySet())if(allowed(e.getKey()))j.put(e.getKey(),e.getValue());return j;
    }
    static void validateSettings(JSONObject j)throws Exception {
        for(Iterator<String> i=j.keys();i.hasNext();){String k=i.next();Object v=j.get(k);
            if(!allowed(k)||!(v instanceof String||v instanceof Boolean||v instanceof Number))throw new IOException("不支持的设置："+k);
            boolean boolKey=Arrays.asList("auto_language_enabled","show_original","show_diagnostics","translation_only_overlay","session_protection").contains(k);
            boolean intKey=k.endsWith("_index")||Arrays.asList("input_mode","font_size","subtitle_alpha","subtitle_y").contains(k)||k.matches(".*\\.(card|device|rate|channels)");
            if(boolKey?!(v instanceof Boolean):intKey?!(v instanceof Integer):!(v instanceof String))throw new IOException("设置类型错误："+k);
            if(k.equals("font_size")&&((Integer)v<0||(Integer)v>100))throw new IOException("字号越界");
            if(k.equals("subtitle_alpha")&&((Integer)v<0||(Integer)v>255))throw new IOException("透明度越界");
            if(v instanceof String&&((String)v).length()>2000)throw new IOException("设置内容过长");
            if(k.endsWith("_index")&&(!(v instanceof Number)||((Number)v).intValue()<0||((Number)v).intValue()>=LanguageOption.ALL.length))throw new IOException("语言索引错误");
            if(k.equals("input_mode")&&(!(v instanceof Number)||((Number)v).intValue()<0||((Number)v).intValue()>2))throw new IOException("声音来源错误");
        }
    }
    public static void applySettings(Context c,JSONObject j)throws Exception {
        if(SessionHealth.active()||ModelDownloadService.active||RunPolicy.prefs(c).getBoolean("service_running",false))throw new IOException("请先停止翻译会话");
        validateSettings(j);SharedPreferences.Editor e=RunPolicy.prefs(c).edit();
        for(Iterator<String> i=j.keys();i.hasNext();){String k=i.next();Object v=j.get(k);if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Number)e.putInt(k,((Number)v).intValue());else e.putString(k,(String)v);}
        for(Iterator<String> i=j.keys();i.hasNext();){String k=i.next();if(k.startsWith("root_call_profile."))e.putBoolean(k.substring(0,k.lastIndexOf('.')+1)+"ready",false);}
        // Imported data never enables cloud access or restores permissions.
        e.putBoolean("cloud_consent",false).putString("run_mode",RunPolicy.OFFLINE);
        if(!e.commit())throw new IOException("设置写入失败");
    }
    public static JSONObject snapshot(Context c)throws Exception {
        JSONArray history=new JSONArray();for(HistoryStore.Entry e:HistoryStore.readAll(c))history.put(new JSONObject().put("time",e.time).put("original",e.original).put("translated",e.translated).put("category",e.category).put("eventId",e.eventId));
        return new JSONObject().put("format","floating-translator-user-data").put("schema",1).put("version",BuildConfig.VERSION_NAME)
            .put("settings",settings(c)).put("history",history).put("scenes",scenes(c));
    }
    public static void exportTo(Context c,Uri uri)throws Exception {
        byte[] bytes=snapshot(c).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>32*1024*1024)throw new IOException("备份超过 32 MB，请先按分类导出历史并整理后再备份");
        try(OutputStream out=c.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法写入备份");out.write(bytes);}
    }
    public static void importFrom(Context c,Uri uri)throws Exception {
        if(SessionHealth.active()||ModelDownloadService.active||RunPolicy.prefs(c).getBoolean("service_running",false))throw new IOException("请先停止翻译");
        byte[] bytes;try(InputStream in=c.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("无法读取备份");byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("备份超过 32 MB");out.write(buffer,0,n);}bytes=out.toByteArray();}
        JSONObject j=new JSONObject(new String(bytes,StandardCharsets.UTF_8));validate(j);
        JSONObject before=snapshot(c);saveLocal(c,"before-restore.json",before);
        File journal=new File(c.getFilesDir(),"restore-in-progress");
        try(FileOutputStream marker=new FileOutputStream(journal)){marker.write(1);marker.getFD().sync();}
        try{restore(c,j);journal.delete();}catch(Exception e){try{restore(c,before);journal.delete();}catch(Exception recovery){e.addSuppressed(recovery);}throw e;}
    }
    static void validate(JSONObject j)throws Exception {
        if(!"floating-translator-user-data".equals(j.optString("format"))||j.optInt("schema")!=1)throw new IOException("备份版本或格式不支持");
        validateSettings(j.getJSONObject("settings"));JSONArray rows=j.getJSONArray("history");if(rows.length()>5000)throw new IOException("历史超过 5000 条");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);row.getLong("time");for(String k:new String[]{"original","translated","category"})if(row.getString(k).length()>200000)throw new IOException("历史条目过长");}
        JSONObject scenes=j.getJSONObject("scenes");if(scenes.length()>20)throw new IOException("场景过多");
        for(Iterator<String> i=scenes.keys();i.hasNext();){String name=i.next();if(name.length()>60)throw new IOException("场景名过长");validateSettings(scenes.getJSONObject(name));}
    }
    private static void restore(Context c,JSONObject j)throws Exception {
        applySettings(c,j.getJSONObject("settings"));
        HistoryStore.replace(c,j.getJSONArray("history"));
        if(!c.getSharedPreferences("user_scenes",0).edit().putString("scenes",j.getJSONObject("scenes").toString()).commit())throw new IOException("场景恢复失败");
    }
    public static JSONObject scenes(Context c)throws Exception {return new JSONObject(c.getSharedPreferences("user_scenes",0).getString("scenes","{}"));}
    public static void saveScene(Context c,String name)throws Exception {JSONObject all=scenes(c);if(all.length()>=20&&!all.has(name))throw new IOException("最多保存 20 个场景");all.put(name,settings(c));if(!c.getSharedPreferences("user_scenes",0).edit().putString("scenes",all.toString()).commit())throw new IOException("保存失败");}
    public static void deleteScene(Context c,String name)throws Exception {JSONObject all=scenes(c);all.remove(name);c.getSharedPreferences("user_scenes",0).edit().putString("scenes",all.toString()).commit();}
    private static void saveLocal(Context c,String name,JSONObject value)throws Exception {
        AtomicFile f=new AtomicFile(new File(c.getFilesDir(),name));FileOutputStream out=null;
        try{out=f.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    public static void checkpointUpgrade(Context c){
        File journal=new File(c.getFilesDir(),"restore-in-progress");
        if(journal.exists())try{restoreCheckpoint(c,"before-restore.json");journal.delete();}catch(Exception e){RunPolicy.prefs(c).edit().putString("upgrade_checkpoint","上次恢复中断，自动回退失败："+e.getMessage()).apply();return;}
        int previous=RunPolicy.prefs(c).getInt("last_data_version",0);
        if(previous==BuildConfig.VERSION_CODE)return;
        try{saveLocal(c,"before-upgrade.json",snapshot(c));RunPolicy.prefs(c).edit().putInt("last_data_version",BuildConfig.VERSION_CODE).putString("upgrade_checkpoint","已保存升级前历史与设置；不含密钥、模型或权限").commit();}
        catch(Exception e){RunPolicy.prefs(c).edit().putString("upgrade_checkpoint","升级前备份失败："+e.getMessage()).apply();}
    }
    public static void restoreCheckpoint(Context c,String name)throws Exception {
        if(!Arrays.asList("before-upgrade.json","before-restore.json").contains(name))throw new IOException("未知恢复点");
        JSONObject j=new JSONObject(new String(new AtomicFile(new File(c.getFilesDir(),name)).readFully(),StandardCharsets.UTF_8));validate(j);restore(c,j);
    }
}
