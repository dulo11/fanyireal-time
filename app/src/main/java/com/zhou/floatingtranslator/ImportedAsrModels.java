package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Metadata for user-imported sherpa-onnx packs.
 * Files never leave app-private storage. A chosen architecture determines
 * which native recognizer runs; random ONNX files are NOT executable plugins.
 */
public final class ImportedAsrModels {
    public static final String PREFIX = "imported_asr_";
    private static final String PREFS = "imported_asr_registry";
    private static final String KEY = "models";

    private ImportedAsrModels() {}

    public static boolean isImported(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public static String newId() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public static synchronized void register(Context context, String id, String name, String templateId) {
        if (!isImported(id)) throw new IllegalArgumentException("自定义模型 ID 不合法");
        OfflineAsrModelCatalog.Model base = OfflineAsrModelCatalog.find(templateId);
        if (base == null || isImported(templateId)) throw new IllegalArgumentException("不支持的模型结构");
        String clean = safeName(name);
        JSONArray source = read(context);
        JSONArray updated = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) updated.put(item);
        }
        JSONObject item = new JSONObject();
        try {
            item.put("id", id);
            item.put("name", clean);
            item.put("template", templateId);
        } catch (Exception e) {
            throw new IllegalStateException("模型元数据无法保存", e);
        }
        updated.put(item);
        save(context, updated);
    }

    public static synchronized void remove(Context context, String id) {
        JSONArray source = read(context);
        JSONArray out = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) out.put(item);
        }
        save(context, out);
    }

    public static synchronized OfflineAsrModelCatalog.Model find(Context context, String id) {
        if (!isImported(id)) return null;
        JSONArray data = read(context);
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !id.equals(item.optString("id"))) continue;
            String templateId = item.optString("template");
            OfflineAsrModelCatalog.Model base = OfflineAsrModelCatalog.find(templateId);
            if (base == null) return null;
            return new OfflineAsrModelCatalog.Model(
                id,
                "自定义 · " + safeName(item.optString("name")),
                "用户手动导入 · 仅匹配 " + base.name + " 的 sherpa-onnx 文件格式；"
                    + "具体参数量、许可证和文件来源需要由导入者自行核实。",
                "", "", "本机文件（不联网下载）", base.family,
                base.supportsFp32, base.supportsInt8,
                templateId, "参数量未验证");
        }
        return null;
    }

    public static synchronized List<OfflineAsrModelCatalog.Model> all(Context context) {
        JSONArray data = read(context);
        List<OfflineAsrModelCatalog.Model> models = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            OfflineAsrModelCatalog.Model model = find(context, item.optString("id"));
            if (model != null) models.add(model);
        }
        return models;
    }

    public static String safeName(String input) {
        String name = input == null ? "" : input.trim().replaceAll("[\\p{Cntrl}]+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("请输入模型名称");
        return name.substring(0, Math.min(60, name.length()));
    }

    private static JSONArray read(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray models) {
        boolean ok = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, models.toString()).commit();
        if (!ok) throw new IllegalStateException("自定义模型登记失败");
    }
}
