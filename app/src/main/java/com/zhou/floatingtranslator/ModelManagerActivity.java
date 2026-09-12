package com.zhou.floatingtranslator;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple storage manager for downloaded offline speech/translation packs. */
public class ModelManagerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Spinner languageSpinner;
    private TextView summary;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("浮译 · 离线模型管理");
        setContentView(buildUi());
        refresh();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("离线模型管理", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView note = text(
            "这里可以删除已经下载到手机里的离线模型。删除后需要再次使用该语言时会重新下载。\n" +
            "Vosk 是语音识别模型；ML Kit 是文字翻译模型。删除模型不会删除你的 App 设置。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(6), 0, dp(16));
        root.addView(note);

        summary = text("正在读取模型……", 14, Color.rgb(190, 165, 255));
        summary.setPadding(0, dp(8), 0, dp(12));
        root.addView(summary);

        root.addView(label("选择语言"));
        languageSpinner = new Spinner(this);
        languageSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL));
        int saved = getSharedPreferences("floating_translator", MODE_PRIVATE)
            .getInt("source_index", 2);
        languageSpinner.setSelection(Math.max(0, Math.min(saved, LanguageOption.ALL.length - 1)));
        languageSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        root.addView(languageSpinner, matchWrap());

        Button deleteCurrentSpeech = button("删除当前语言 Vosk 语音模型");
        deleteCurrentSpeech.setOnClickListener(v -> deleteCurrentVosk());
        root.addView(deleteCurrentSpeech, matchWrap());

        Button deleteAllSpeech = button("删除全部 Vosk 语音模型");
        deleteAllSpeech.setOnClickListener(v -> deleteAllVosk());
        root.addView(deleteAllSpeech, matchWrap());

        Button deleteCurrentMl = button("删除当前语言 ML Kit 翻译模型");
        deleteCurrentMl.setOnClickListener(v -> deleteCurrentMlKit());
        root.addView(deleteCurrentMl, matchWrap());

        Button deleteAllMl = button("删除全部 ML Kit 翻译模型");
        deleteAllMl.setOnClickListener(v -> deleteAllMlKit());
        root.addView(deleteAllMl, matchWrap());

        Button refresh = button("刷新模型状态");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());

        status = text("", 14, Color.rgb(218, 209, 231));
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status);

        TextView future = text(
            "后续加入 NLLB / OPUS-MT / sherpa-onnx 等大模型后，也会统一放在这里管理、下载和删除，" +
            "不会强制把几 GB 模型塞进基础 APK。",
            13, Color.rgb(180, 170, 205));
        future.setPadding(0, dp(22), 0, 0);
        root.addView(future);
        return scroll;
    }

    private void refresh() {
        status.setText("正在扫描……");
        worker.execute(() -> {
            File voskBase = new File(getFilesDir(), "vosk-models");
            long voskBytes = folderSize(voskBase);
            int voskCount = countModelFolders(voskBase);
            RemoteModelManager manager = RemoteModelManager.getInstance();
            manager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    StringBuilder langs = new StringBuilder();
                    for (TranslateRemoteModel model : models) {
                        if (langs.length() > 0) langs.append("、");
                        langs.append(model.getLanguage());
                    }
                    String text = "Vosk 语音模型：" + voskCount + " 个，约 " + human(voskBytes) + "\n" +
                        "ML Kit 翻译模型：" + models.size() + " 个" +
                        (langs.length() == 0 ? "" : "（" + langs + "）");
                    summary.setText(text);
                    status.setText("模型状态已刷新");
                })
                .addOnFailureListener(e -> {
                    summary.setText("Vosk 语音模型：" + voskCount + " 个，约 " + human(voskBytes));
                    status.setText("ML Kit 模型列表读取失败：" + safe(e));
                });
        });
    }

    private void deleteCurrentVosk() {
        LanguageOption option = (LanguageOption) languageSpinner.getSelectedItem();
        if (option == null) return;
        status.setText("正在删除 " + option.label + " 的 Vosk 模型……");
        worker.execute(() -> {
            File base = new File(getFilesDir(), "vosk-models");
            int deleted = 0;
            File[] files = base.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (matchesVoskLanguage(file.getName(), option.mlKitTag)) {
                        if (deleteRecursively(file)) deleted++;
                    }
                }
            }
            int result = deleted;
            runOnUiThread(() -> {
                status.setText(result > 0
                    ? "✅ 已删除 " + option.label + " Vosk 模型"
                    : "当前没有找到 " + option.label + " 的 Vosk 模型");
                refresh();
            });
        });
    }

    private void deleteAllVosk() {
        status.setText("正在删除全部 Vosk 语音模型……");
        worker.execute(() -> {
            File base = new File(getFilesDir(), "vosk-models");
            boolean ok = deleteRecursively(base);
            runOnUiThread(() -> {
                status.setText(ok ? "✅ 已删除全部 Vosk 语音模型" : "删除 Vosk 模型时遇到部分失败");
                refresh();
            });
        });
    }

    private void deleteCurrentMlKit() {
        LanguageOption option = (LanguageOption) languageSpinner.getSelectedItem();
        if (option == null) return;
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(option.mlKitTag).build();
        status.setText("正在删除 ML Kit " + option.label + " 模型……");
        RemoteModelManager.getInstance().deleteDownloadedModel(model)
            .addOnSuccessListener(x -> {
                status.setText("✅ 已删除 ML Kit " + option.label + " 模型");
                refresh();
            })
            .addOnFailureListener(e -> status.setText("删除失败：" + safe(e)));
    }

    private void deleteAllMlKit() {
        RemoteModelManager manager = RemoteModelManager.getInstance();
        status.setText("正在读取 ML Kit 模型……");
        manager.getDownloadedModels(TranslateRemoteModel.class)
            .addOnSuccessListener(models -> deleteMlKitSet(manager, models))
            .addOnFailureListener(e -> status.setText("读取模型失败：" + safe(e)));
    }

    private void deleteMlKitSet(RemoteModelManager manager, Set<TranslateRemoteModel> models) {
        if (models.isEmpty()) {
            status.setText("没有已下载的 ML Kit 翻译模型");
            return;
        }
        AtomicInteger remaining = new AtomicInteger(models.size());
        AtomicInteger failed = new AtomicInteger(0);
        for (TranslateRemoteModel model : models) {
            manager.deleteDownloadedModel(model)
                .addOnFailureListener(e -> failed.incrementAndGet())
                .addOnCompleteListener(task -> {
                    if (remaining.decrementAndGet() == 0) {
                        status.setText(failed.get() == 0
                            ? "✅ 已删除全部 ML Kit 翻译模型"
                            : "删除完成，但有 " + failed.get() + " 个模型删除失败");
                        refresh();
                    }
                });
        }
    }

    private static boolean matchesVoskLanguage(String name, String lang) {
        String n = name.toLowerCase(Locale.ROOT);
        switch (lang) {
            case "zh": return n.contains("-cn-") || n.contains("-zh-");
            case "en": return n.contains("-en-") || n.contains("-en-us-");
            case "ja": return n.contains("-ja-");
            case "vi": return n.contains("-vn-") || n.contains("-vi-");
            case "ko": return n.contains("-ko-");
            case "pt": return n.contains("-pt-");
            case "hi": return n.contains("-hi-");
            case "ru": return n.contains("-ru-");
            case "pl": return n.contains("-pl-");
            case "cs": return n.contains("-cs-");
            default: return n.contains("-" + lang + "-");
        }
    }

    private static int countModelFolders(File base) {
        File[] files = base.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) if (file.isDirectory() && !file.getName().endsWith(".part")) count++;
        return count;
    }

    private static long folderSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += folderSize(child);
        return total;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) ok &= deleteRecursively(child);
            }
        }
        return file.delete() && ok;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
        return String.format(Locale.ROOT, "%.2f GB", kb / 1024.0 / 1024.0);
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
    }

    private TextView label(String value) {
        TextView t = text(value, 14, Color.rgb(201, 190, 221));
        t.setPadding(0, dp(6), 0, dp(3));
        return t;
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
