package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Download/delete/select high-accuracy offline ASR packs. */
public class ModelManagerActivity extends Activity {
    private static final String PREFS = "floating_translator";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private OfflineModelStore modelStore;
    private SharedPreferences prefs;
    private Spinner asrModelSpinner;
    private Spinner languageSpinner;
    private TextView selectedInfo;
    private TextView summary;
    private TextView status;
    private Button downloadButton;
    private Button deleteButton;
    private Button clearPartialButton;
    private volatile boolean downloadInProgress;

    private final List<String> modelIds = new ArrayList<>();
    private final List<String> modelLabels = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        modelStore = new OfflineModelStore(this);
        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · 离线模型中心");
        setContentView(buildUi());
        refresh();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(34));
        scroll.addView(root);

        TextView title = text("离线模型中心", 30, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView note = text(
            "大模型不会强塞进 APK。需要哪个就下载哪个，保存到浮译私有目录；不用时可以删除。\n" +
            "纯日语优先 Parakeet / ReazonSpeech；日英混说优先 Qwen3-ASR / Whisper；韩语和东南亚语言可优先 Qwen3 / Whisper / Omnilingual。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(6), 0, dp(14));
        root.addView(note);

        summary = text("正在读取模型……", 14, Color.rgb(190, 165, 255));
        summary.setPadding(0, dp(8), 0, dp(8));
        root.addView(summary);

        clearPartialButton = button("🧹 清理未完成下载缓存");
        clearPartialButton.setOnClickListener(v -> clearPartialDownloads());
        root.addView(clearPartialButton, matchWrap());

        root.addView(section("高精度离线 ASR"));
        buildModelOptions();
        asrModelSpinner = new Spinner(this);
        asrModelSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, modelLabels));
        asrModelSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        asrModelSpinner.setSelection(selectedModelIndex());
        root.addView(asrModelSpinner, matchWrap());

        selectedInfo = text("", 13, Color.rgb(184, 174, 207));
        selectedInfo.setPadding(dp(4), dp(6), dp(4), dp(8));
        root.addView(selectedInfo);
        asrModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSelectedInfo();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        downloadButton = button("⬇ 下载所选模型");
        downloadButton.setOnClickListener(v -> downloadSelected());
        root.addView(downloadButton, matchWrap());

        deleteButton = button("删除所选模型");
        deleteButton.setOnClickListener(v -> deleteSelected());
        root.addView(deleteButton, matchWrap());

        Button defaultButton = button("★ 设为默认 ASR 并保存");
        defaultButton.setOnClickListener(v -> setDefaultSelected());
        root.addView(defaultButton, matchWrap());

        root.addView(section("Vosk / ML Kit 按语言管理"));
        languageSpinner = new Spinner(this);
        languageSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL));
        int saved = prefs.getInt("source_index", 2);
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

        TextView translationFuture = text(
            "文字翻译目前仍是 ML Kit 离线优先。NLLB / OPUS-MT 属于下一步文字翻译大模型，" +
            "本页不会把尚未接入的模型伪装成可用。",
            13, Color.rgb(180, 170, 205));
        translationFuture.setPadding(0, dp(22), 0, 0);
        root.addView(translationFuture);
        return scroll;
    }

    private void buildModelOptions() {
        modelIds.clear();
        modelLabels.clear();
        modelIds.add(TranslationService.ASR_VOSK);
        modelLabels.add("Vosk｜省电★★★★★｜速度快｜模型小｜日语快语速准确率一般");
        for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all()) {
            modelIds.add(model.id);
            modelLabels.add(model.optionLabel());
        }
    }

    private int selectedModelIndex() {
        String selected = prefs.getString("asr_mode", TranslationService.ASR_AUTO);
        if (TranslationService.ASR_AUTO.equals(selected)) return 0;
        for (int i = 0; i < modelIds.size(); i++) if (modelIds.get(i).equals(selected)) return i;
        return 0;
    }

    private String selectedModelId() {
        int p = asrModelSpinner == null ? 0 : asrModelSpinner.getSelectedItemPosition();
        return modelIds.get(Math.max(0, Math.min(p, modelIds.size() - 1)));
    }

    private void updateSelectedInfo() {
        if (selectedInfo == null) return;
        String id = selectedModelId();
        if (TranslationService.ASR_VOSK.equals(id)) {
            selectedInfo.setText("Vosk：轻量流式 ASR。语言包首次使用自动下载；之后完全离线。适合省电，纯日语快语速/长句不是强项。");
            if (downloadButton != null) downloadButton.setEnabled(false);
            if (deleteButton != null) deleteButton.setEnabled(false);
            return;
        }
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(id);
        boolean installed = modelStore.isInstalled(id);
        long bytes = modelStore.installedBytes(id);
        selectedInfo.setText(model.name + "\n" + model.remark + "\n下载/模型大小：" + model.approximateSize
            + "\n状态：" + (installed ? "✅ 已下载，实际占用 " + OfflineModelStore.human(bytes) : "未下载"));
        if (downloadButton != null) downloadButton.setEnabled(!installed && !downloadInProgress);
        if (deleteButton != null) deleteButton.setEnabled(installed && !downloadInProgress);
    }

    private void downloadSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(id);
        if (model == null) {
            status.setText("Vosk 语言包会在实际使用时自动下载，不需要从这里重复下载。");
            return;
        }
        if (downloadInProgress) {
            status.setText("已有模型正在下载，请等待完成或失败后再操作缓存。");
            return;
        }
        downloadInProgress = true;
        downloadButton.setEnabled(false);
        deleteButton.setEnabled(false);
        if (clearPartialButton != null) clearPartialButton.setEnabled(false);
        modelStore.download(model, new OfflineModelStore.Callback() {
            @Override public void onStatus(String message) {
                status.setText(message);
            }

            @Override public void onProgress(int percent, long downloaded, long total) {
                status.setText(percent >= 0
                    ? "⬇ " + model.name + "：" + percent + "% · " + OfflineModelStore.human(downloaded)
                        + (total > 0 ? " / " + OfflineModelStore.human(total) : "")
                    : "⬇ " + model.name + "：已下载 " + OfflineModelStore.human(downloaded));
            }

            @Override public void onSuccess(File modelDir) {
                downloadInProgress = false;
                status.setText("✅ " + model.name + " 下载并解压完成，可直接设为默认使用");
                if (clearPartialButton != null) clearPartialButton.setEnabled(true);
                updateSelectedInfo();
                refresh();
            }

            @Override public void onError(String message) {
                downloadInProgress = false;
                status.setText("❌ " + model.name + " 下载失败：" + message);
                if (clearPartialButton != null) clearPartialButton.setEnabled(true);
                updateSelectedInfo();
                refresh();
            }
        });
    }

    private void clearPartialDownloads() {
        if (downloadInProgress) {
            status.setText("模型正在下载，不能同时清理断点缓存。");
            return;
        }
        final long before = modelStore.partialDownloadBytes();
        if (before <= 0L) {
            status.setText("没有未完成下载缓存");
            return;
        }
        if (clearPartialButton != null) clearPartialButton.setEnabled(false);
        status.setText("正在清理未完成下载缓存 " + OfflineModelStore.human(before) + "……");
        worker.execute(() -> {
            boolean ok = modelStore.clearPartialDownloads();
            long remaining = modelStore.partialDownloadBytes();
            runOnUiThread(() -> {
                if (clearPartialButton != null) clearPartialButton.setEnabled(true);
                status.setText(ok && remaining == 0L
                    ? "✅ 已清理未完成下载缓存 " + OfflineModelStore.human(before)
                    : "清理完成，但仍剩余 " + OfflineModelStore.human(remaining));
                refresh();
            });
        });
    }

    private void deleteSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(id);
        if (model == null || downloadInProgress) return;
        status.setText("正在删除 " + model.name + "……");
        worker.execute(() -> {
            boolean ok = modelStore.delete(id);
            runOnUiThread(() -> {
                status.setText(ok ? "✅ 已删除 " + model.name : "删除时遇到部分失败");
                if (id.equals(prefs.getString("asr_mode", ""))) {
                    prefs.edit().putString("asr_mode", TranslationService.ASR_AUTO).apply();
                }
                updateSelectedInfo();
                refresh();
            });
        });
    }

    private void setDefaultSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(id);
        if (model != null && !modelStore.isInstalled(id)) {
            status.setText("请先下载 " + model.name + "，下载完成后再设为默认");
            return;
        }
        prefs.edit().putString("asr_mode", id).apply();
        status.setText("✅ 已保存默认 ASR：" + modelLabels.get(asrModelSpinner.getSelectedItemPosition()));
        toast("默认 ASR 已保存");
    }

    private void refresh() {
        if (status != null) status.setText("正在扫描……");
        worker.execute(() -> {
            File voskBase = new File(getFilesDir(), "vosk-models");
            long voskBytes = OfflineModelStore.folderSize(voskBase);
            int voskCount = countModelFolders(voskBase);
            long sherpaInstalledBytes = modelStore.allInstalledBytes();
            long partialBytes = modelStore.partialDownloadBytes();
            int sherpaCount = 0;
            for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all()) {
                if (modelStore.isInstalled(model.id)) sherpaCount++;
            }
            int finalSherpaCount = sherpaCount;
            RemoteModelManager manager = RemoteModelManager.getInstance();
            manager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    String text = "Vosk：" + voskCount + " 个，约 " + OfflineModelStore.human(voskBytes) + "\n" +
                        "高精度 sherpa-onnx：" + finalSherpaCount + " 个，已安装 " + OfflineModelStore.human(sherpaInstalledBytes) + "\n" +
                        "未完成下载缓存：" + OfflineModelStore.human(partialBytes) + "\n" +
                        "ML Kit 翻译：" + models.size() + " 个语言模型";
                    summary.setText(text);
                    if (clearPartialButton != null) clearPartialButton.setEnabled(!downloadInProgress && partialBytes > 0L);
                    status.setText("模型状态已刷新");
                    updateSelectedInfo();
                })
                .addOnFailureListener(e -> {
                    summary.setText("Vosk：" + voskCount + " 个 · sherpa：" + finalSherpaCount + " 个\n"
                        + "未完成下载缓存：" + OfflineModelStore.human(partialBytes));
                    if (clearPartialButton != null) clearPartialButton.setEnabled(!downloadInProgress && partialBytes > 0L);
                    status.setText("ML Kit 模型列表读取失败：" + safe(e));
                    updateSelectedInfo();
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
                    if (matchesVoskLanguage(file.getName(), option.mlKitTag)
                        && OfflineModelStore.deleteRecursively(file)) deleted++;
                }
            }
            int result = deleted;
            runOnUiThread(() -> {
                status.setText(result > 0 ? "✅ 已删除 " + option.label + " Vosk 模型" : "当前没有该 Vosk 模型");
                refresh();
            });
        });
    }

    private void deleteAllVosk() {
        status.setText("正在删除全部 Vosk 语音模型……");
        worker.execute(() -> {
            boolean ok = OfflineModelStore.deleteRecursively(new File(getFilesDir(), "vosk-models"));
            runOnUiThread(() -> {
                status.setText(ok ? "✅ 已删除全部 Vosk 语音模型" : "删除 Vosk 时遇到部分失败");
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
            .addOnSuccessListener(x -> { status.setText("✅ 已删除 ML Kit " + option.label); refresh(); })
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
        if (models.isEmpty()) { status.setText("没有已下载的 ML Kit 翻译模型"); return; }
        AtomicInteger remaining = new AtomicInteger(models.size());
        AtomicInteger failed = new AtomicInteger(0);
        for (TranslateRemoteModel model : models) {
            manager.deleteDownloadedModel(model)
                .addOnFailureListener(e -> failed.incrementAndGet())
                .addOnCompleteListener(task -> {
                    if (remaining.decrementAndGet() == 0) {
                        status.setText(failed.get() == 0 ? "✅ 已删除全部 ML Kit 翻译模型"
                            : "删除完成，但有 " + failed.get() + " 个失败");
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

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, 1);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.button_secondary);
        return b;
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private String safe(Exception e) {
        if (e == null) return "未知错误";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override protected void onDestroy() {
        modelStore.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
