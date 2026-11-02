package com.zhou.floatingtranslator;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Persistent app-private translation history with TXT/SRT export helpers. */
public final class HistoryStore {
    private static final String FILE_NAME = "translation-history.jsonl";
    private static final int MAX_ENTRIES = 5000;
    private static final Object LOCK = new Object();

    public static final class Entry {
        public final long time;
        public final String original;
        public final String translated;
        public final String category;
        public final String eventId;

        Entry(long time, String original, String translated) {
            this(time,original,translated,"legacy");
        }
        Entry(long time,String original,String translated,String category){this(time,original,translated,category,"");}
        Entry(long time,String original,String translated,String category,String eventId){
            this.eventId=eventId;
            this.category=category;
            this.time = time;
            this.original = original;
            this.translated = translated;
        }
    }

    private HistoryStore() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void append(Context context, String original, String translated) {
        append(context,original,translated,"legacy");
    }
    public static void append(Context context,String original,String translated,String category){
        if (context == null || translated == null) return;
        String o = original == null ? "" : original.trim();
        String t = translated.trim();
        if (t.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file(context), true), StandardCharsets.UTF_8))) {
                JSONObject json = new JSONObject();
                json.put("time", now);json.put("category",category);
                json.put("original", o);
                json.put("translated", t);
                out.write(json.toString());
                out.newLine();
            } catch (Exception ignored) {}
            trimIfNeeded(context);
        }
    }

    public static void appendRecovered(Context context,JSONObject source,String translation)throws Exception {
        synchronized(LOCK) {
            String id=source.getString("id");
            for(Entry e:readAll(context))if(id.equals(e.eventId))return;
            JSONObject row=new JSONObject().put("eventId",id).put("time",source.getLong("time"))
                .put("original",source.getString("text")).put("translated",translation).put("category",source.getString("category"));
            try(FileOutputStream out=new FileOutputStream(file(context),true)){
                out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            trimIfNeeded(context);
        }
    }

    public static void replace(Context context, org.json.JSONArray rows) throws Exception {
        synchronized(LOCK) {
            android.util.AtomicFile target=new android.util.AtomicFile(file(context));FileOutputStream stream=null;
            try {stream=target.startWrite();for(int i=0;i<rows.length();i++){stream.write(rows.getJSONObject(i).toString().getBytes(StandardCharsets.UTF_8));stream.write('\n');}target.finishWrite(stream);}
            catch(Exception e){if(stream!=null)target.failWrite(stream);throw e;}
        }
    }

    public static List<Entry> readAll(Context context) {
        synchronized (LOCK) {
            ArrayList<Entry> entries = new ArrayList<>();
            File f = file(context);
            if (!f.isFile() && !new File(f+".bak").isFile()) return entries;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new android.util.AtomicFile(f).openRead(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    try {
                        JSONObject json = new JSONObject(line);
                        String translated = json.optString("translated", "").trim();
                        if (translated.isEmpty()) continue;
                        entries.add(new Entry(
                            json.optLong("time", 0L),
                            json.optString("original", ""),
                            translated,json.optString("category","legacy"),json.optString("eventId","")));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return entries;
        }
    }

    public static List<Entry> filter(List<Entry> entries,String query,String category){
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);List<Entry> out=new ArrayList<>();
        for(Entry e:entries)if((category==null||category.isEmpty()||category.equals(e.category))&&(q.isEmpty()||e.original.toLowerCase(Locale.ROOT).contains(q)||e.translated.toLowerCase(Locale.ROOT).contains(q)))out.add(e);
        return out;
    }

    public static List<Entry> readLatest(Context context, int limit) {
        List<Entry> all = readAll(context);
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    public static int count(Context context) {
        return readAll(context).size();
    }

    public static boolean clear(Context context) {
        synchronized (LOCK) {
            File f = file(context);
            return !f.exists() || f.delete();
        }
    }

    public static String buildTxt(Context context) {
        return buildTxt(readAll(context));
    }
    public static String buildTxt(List<Entry> entries){
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder out = new StringBuilder();
        out.append("浮译 翻译历史\n");
        out.append("共 ").append(entries.size()).append(" 条\n\n");
        for (Entry entry : entries) {
            out.append('[').append(format.format(new Date(entry.time))).append("]\n");
            if (!entry.original.isEmpty()) out.append("原文：").append(entry.original).append('\n');
            out.append("译文：").append(entry.translated).append("\n\n");
        }
        return out.toString();
    }

    public static String buildSrt(Context context) {
        return buildSrt(readAll(context));
    }
    public static String buildSrt(List<Entry> entries){
        if (entries.isEmpty()) return "";
        long first = entries.get(0).time;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            long start = Math.max(0L, entry.time - first);
            long end;
            if (i + 1 < entries.size()) {
                long next = Math.max(start + 800L, entries.get(i + 1).time - first);
                end = Math.min(start + 8000L, next);
            } else {
                end = start + 4000L;
            }
            out.append(i + 1).append('\n');
            out.append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n');
            if (!entry.original.isEmpty()) out.append(entry.original).append('\n');
            out.append(entry.translated).append("\n\n");
        }
        return out.toString();
    }

    private static String srtTime(long ms) {
        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        ms %= 60_000L;
        long seconds = ms / 1000L;
        long millis = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private static void trimIfNeeded(Context context) {
        List<Entry> entries = readAll(context);
        if (entries.size() <= MAX_ENTRIES) return;
        List<Entry> keep = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
        rewrite(context, keep);
    }

    private static void rewrite(Context context, List<Entry> entries) {
        try {
            org.json.JSONArray rows=new org.json.JSONArray();
            for(Entry e:entries)rows.put(new JSONObject().put("time",e.time).put("category",e.category).put("eventId",e.eventId).put("original",e.original).put("translated",e.translated));
            replace(context,rows);
        }catch(Exception e){RunPolicy.prefs(context).edit().putString("recovery_error","历史整理失败："+e.getMessage()).apply();}
    }
}
