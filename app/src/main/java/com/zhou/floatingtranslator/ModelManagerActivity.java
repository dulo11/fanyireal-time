package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.EditText;
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
    private static final int REQ_IMPORT_ASR_ARCHIVE = 5301;

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
    private Button officialSourceButton;
    private Button modelPackButton;
    private volatile boolean downloadInProgress;
    private String pendingImportName;
    private String pendingImportTemplate;

    private final List<String> modelIds = new ArrayList<>();
    private final List<String> modelLabels = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        modelStore = new OfflineModelStore(this);
        setTitle("浮译 " + BuildConfig.VERSION_NAME + " · 离线模型中心");
        setContentView(buildUi());
        if (state != null) {
            pendingImportName = state.getString("pending_import_name");
            pendingImportTemplate = state.getString("pending_import_type");
        }
        refresh(true);
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(34));
        scroll.addView(root);

        TextView title = text("离线模型中心", 30, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = text(
            "大模型不会强塞进 APK。需要哪个就下载哪个，保存到浮译私有目录；不用时可以删除。\n" +
            "下载完成后会先保留完整安装包，只有模型解压并验证安装成功后才会删除；如果解压失败，再点下载会直接重试安装，不会重新下载。\n" +
            "纯日语优先 Parakeet / ReazonSpeech；日英混说优先 Qwen3-ASR / Whisper；韩语和东南亚语言可优先 Qwen3 / Whisper / Omnilingual。",
            14, Color.rgb(201, 190, 221));
        note.setPadding(0, dp(6), 0, dp(14));
        root.addView(note);

        summary = text("正在读取模型……", 14, Color.rgb(190, 165, 255));
        summary.setPadding(0, dp(8), 0, dp(8));
        root.addView(summary);

        clearPartialButton = button("🧹 清理下载 / 安装缓存");
        clearPartialButton.setOnClickListener(v -> clearDownloadCaches());
        root.addView(clearPartialButton, matchWrap());

        root.addView(section("高精度离线 ASR"));
        buildModelOptions();
        asrModelSpinner = new Spinner(this);
        asrModelSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, modelLabels));
        asrModelSpinner.setBackgroundColor(Color.rgb(51, 45, 73));
        asrModelSpinner.setSelection(selectedModelIndex());
        root.addView(asrModelSpinner, matchWrap());
        Button importButton = button("＋ 新增自定义 ASR 模型｜从本机导入 ZIP / TAR.BZ2");
        importButton.setOnClickListener(v -> chooseImportTemplate());
        root.addView(importButton, matchWrap());

        Button disclosure = button("🔒 查看音频权限、数据去向与浮译源码");
        disclosure.setOnClickListener(v ->
            startActivity(new Intent(this, PrivacyAndSourcesActivity.class)));
        root.addView(disclosure, matchWrap());

        selectedInfo = text("", 13, Color.rgb(184, 174, 207));
        selectedInfo.setPadding(dp(4), dp(6), dp(4), dp(8));
        root.addView(selectedInfo);

        officialSourceButton = button("🌐 当前模型研发方官网 / 开源项目");
        officialSourceButton.setOnClickListener(v -> openSelectedModelOfficialSite());
        root.addView(officialSourceButton, matchWrap());

        modelPackButton = button("⬇ 查看实际模型包下载地址 / 来源");
        modelPackButton.setOnClickListener(v -> openSelectedModelPackSource());
        root.addView(modelPackButton, matchWrap());

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
        refresh.setOnClickListener(v -> refresh(true));
        root.addView(refresh, matchWrap());

        status = text("", 14, Color.rgb(218, 209, 231));
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status);

        TextView translationFuture = text(
            OfflineAsrModelCatalog.pendingModelsText()
                + "\n\n文字翻译的大模型 NLLB / OPUS-MT 仍未接入，不能当作已支持的模型。",
            13, Color.rgb(180, 170, 205));
        translationFuture.setPadding(0, dp(22), 0, 0);
        root.addView(translationFuture);
        return scroll;
    }

    private void buildModelOptions() {
        modelIds.clear();
        modelLabels.clear();
        modelIds.add(TranslationService.ASR_VOSK);
        modelLabels.add("Vosk｜按语言包大小，参数量不固定｜速度快、省电");
        for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all(this)) {
            modelIds.add(model.id);
            modelLabels.add(model.optionLabel());
        }
    }

    private void openSelectedModelOfficialSite() {
        String id = selectedModelId();
        if (TranslationService.ASR_VOSK.equals(id)) {
            ModelSourceLinks.open(this, ModelSourceLinks.speechRecognizerUrl(id));
            return;
        }
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, id);
        if (model != null) ModelSourceLinks.open(this,
            ModelSourceLinks.officialModelUrl(model.runtimeModelId));
    }

    private void openSelectedModelPackSource() {
        OfflineAsrModelCatalog.Model model =
            OfflineAsrModelCatalog.find(this, selectedModelId());
        if (model == null || ImportedAsrModels.isImported(model.id)) {
            toast("自定义模型由用户自行选取，来源地址无法自动验证");
            return;
        }
        ModelSourceLinks.open(this, ModelSourceLinks.packSourceUrl(model));
    }

    private void chooseImportTemplate() {
        if (downloadInProgress) {
            toast("已有模型正在下载或导入");
            return;
        }
        List<OfflineAsrModelCatalog.Model> templates = OfflineAsrModelCatalog.all();
        String[] names = new String[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            OfflineAsrModelCatalog.Model template = templates.get(i);
            names[i] = template.name + "｜" + template.parameterText();
        }
        new AlertDialog.Builder(this)
            .setTitle("第一步：选择模型对应的 sherpa-onnx 架构")
            .setMessage("本机导入是已有架构的模型变体。"
                + "PyTorch、GGUF、任意 ONNX 权重不能自动转换为可用 ASR。"
                + "请先确认模型导出格式与所选架构匹配。")
            .setItems(names, (dialog, index) -> chooseImportName(templates.get(index)))
            .setNegativeButton("取消", null).show();
    }

    private void chooseImportName(OfflineAsrModelCatalog.Model template) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如：自己的日语直播模型");
        input.setText(template.name + " · 自备");
        new AlertDialog.Builder(this)
            .setTitle("第二步：填写显示名称")
            .setMessage("之后可在 ASR 设置里手动切换；"
                + "导入仅复制本机文件，不访问第三方网站。"
                + "请检查模型许可证和来源是否可信。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("选择本地模型文件", (dialog, which) -> {
                try {
                    pendingImportName = ImportedAsrModels.safeName(input.getText().toString());
                    pendingImportTemplate = template.id;
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(picker, REQ_IMPORT_ASR_ARCHIVE);
                } catch (Exception ex) {
                    toast("无法选择文件：" + ex.getMessage());
                }
            }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_import_name", pendingImportName);
        state.putString("pending_import_type", pendingImportTemplate);
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_ASR_ARCHIVE || resultCode != RESULT_OK
            || data == null || data.getData() == null) return;
        if (pendingImportName == null || pendingImportTemplate == null) {
            toast("导入已过期，请重新开始选择");
            return;
        }
        Uri document = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(document,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { /* Files provided by some apps are read-once only. */ }

        String filename = documentName(document);
        final String importName = pendingImportName;
        final String templateId = pendingImportTemplate;
        final String importId = ImportedAsrModels.newId();
        pendingImportName = null;
        pendingImportTemplate = null;
        downloadInProgress = true;
        updateSelectedInfo();
        status.setText("正在读取 " + filename + "，仅复制到本机，不联网传输……");

        modelStore.importLocalArchive(document, importId, templateId, filename,
            new OfflineModelStore.Callback() {
                @Override public void onStatus(String message) { status.setText(message); }
                @Override public void onProgress(int percent, long current, long total) {
                    status.setText("正在导入本地文件：" + OfflineModelStore.human(current));
                }
                @Override public void onSuccess(File directory) {
                    try {
                        ImportedAsrModels.register(ModelManagerActivity.this,
                            importId, importName, templateId);
                        rebuildModelSelector(importId);
                        status.setText("✅ 模型已导入，仅保存在本机。"
                            + "请设为默认 ASR 并重启翻译。"
                            + "使用 Azure 等在线翻译时，识别后的文字仍会发送给该翻译提供商。");
                    } catch (Exception ex) {
                        status.setText("模型文件已保存，但登记失败：" + ex.getMessage());
                    }
                    downloadInProgress = false;
                    updateSelectedInfo();
                    refresh(false);
                }
                @Override public void onError(String message) {
                    downloadInProgress = false;
                    status.setText(message);
                    updateSelectedInfo();
                }
            });
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private void rebuildModelSelector(String selectedId) {
        buildModelOptions();
        asrModelSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, modelLabels));
        int index = modelIds.indexOf(selectedId);
        asrModelSpinner.setSelection(Math.max(0, index));
        updateSelectedInfo();
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
            if (downloadButton != null) {
                downloadButton.setText("⬇ 下载所选模型");
                downloadButton.setEnabled(false);
            }
            if (deleteButton != null) deleteButton.setEnabled(false);
            if (officialSourceButton != null) officialSourceButton.setEnabled(true);
            if (modelPackButton != null) modelPackButton.setEnabled(false);
            return;
        }

        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, id);
        if (model == null) return;
        boolean imported = ImportedAsrModels.isImported(id);
        if (officialSourceButton != null) officialSourceButton.setEnabled(true);
        if (modelPackButton != null) modelPackButton.setEnabled(!imported);
        boolean installed = modelStore.isInstalled(id);
        long installedBytes = modelStore.installedBytes(id);
        long readyBytes = modelStore.cachedArchiveBytes(id);
        long partialBytes = modelStore.partialArchiveBytes(id);
        String state;
        if (installed) {
            state = "✅ 已安装，实际占用 " + OfflineModelStore.human(installedBytes);
        } else if (readyBytes > 0L) {
            state = "📦 完整下载包已缓存 " + OfflineModelStore.human(readyBytes)
                + "，可直接重新解压/安装，不会重复下载";
        } else if (partialBytes > 0L) {
            state = "⏸ 下载未完成，已缓存 " + OfflineModelStore.human(partialBytes) + "，可断点续传";
        } else {
            state = "未下载";
        }

        String author = ModelSourceLinks.officialModelUrl(model.runtimeModelId);
        String deployedPack = ModelSourceLinks.packSourceUrl(model);
        selectedInfo.setText(model.name + "\n参数规模：" + model.parameterText()
            + "\n" + model.remark + "\n下载/模型大小：" + model.approximateSize
            + "\n模型研发方：" + author
            + "\n部署文件来源：" + (deployedPack.isEmpty()
                ? "本地导入（不会通过导入操作上传）" : deployedPack)
            + "\n状态：" + state);
        if (downloadButton != null) {
            downloadButton.setText(imported ? "本地导入模型｜不可在线自动下载"
                : (readyBytes > 0L && !installed ? "↻ 重新解压 / 安装" : "⬇ 下载所选模型"));
            downloadButton.setEnabled(!imported && !installed && !downloadInProgress);
        }
        if (deleteButton != null) {
            deleteButton.setText(imported ? "删除自定义模型及本机登记" : "删除所选模型");
            deleteButton.setEnabled((installed || imported) && !downloadInProgress);
        }
    }

    private void downloadSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, id);
        if (model == null) {
            status.setText("Vosk 语言包会在实际使用时自动下载，不需要从这里重复下载。");
            return;
        }
        if (ImportedAsrModels.isImported(id)) {
            status.setText("自定义模型是本机导入，没有自动下载地址。请先删除后重新导入新文件。");
            return;
        }
        if (downloadInProgress) {
            status.setText("已有模型正在下载或安装，请等待完成后再操作缓存。");
            return;
        }

        downloadInProgress = true;
        downloadButton.setEnabled(false);
        deleteButton.setEnabled(false);
        if (clearPartialButton != null) clearPartialButton.setEnabled(false);
        modelStore.download(model, new OfflineModelStore.Callback() {
            @Override public void onStatus(String message) {
                status.setText(message);
                updateSelectedInfo();
            }

            @Override public void onProgress(int percent, long downloaded, long total) {
                status.setText(percent >= 0
                    ? "⬇ " + model.name + "：" + percent + "% · " + OfflineModelStore.human(downloaded)
                        + (total > 0 ? " / " + OfflineModelStore.human(total) : "")
                    : "⬇ " + model.name + "：已下载 " + OfflineModelStore.human(downloaded));
            }

            @Override public void onSuccess(File modelDir) {
                downloadInProgress = false;
                status.setText("✅ " + model.name + " 下载、解压和安装完成，可直接设为默认使用");
                updateSelectedInfo();
                refresh(false);
            }

            @Override public void onError(String message) {
                downloadInProgress = false;
                status.setText("❌ " + model.name + " 下载/安装失败：" + message);
                updateSelectedInfo();
                refresh(false);
            }
        });
    }

    private void clearDownloadCaches() {
        if (downloadInProgress) {
            status.setText("模型正在下载或安装，不能同时清理缓存。");
            return;
        }
        final long before = modelStore.allDownloadCacheBytes();
        if (before <= 0L) {
            status.setText("没有下载/安装缓存");
            return;
        }
        if (clearPartialButton != null) clearPartialButton.setEnabled(false);
        status.setText("正在清理下载/安装缓存 " + OfflineModelStore.human(before) + "……");
        worker.execute(() -> {
            boolean ok = modelStore.clearDownloadCaches();
            long remaining = modelStore.allDownloadCacheBytes();
            runOnUiThread(() -> {
                status.setText(ok && remaining == 0L
                    ? "✅ 已清理下载/安装缓存 " + OfflineModelStore.human(before)
                    : "清理完成，但仍剩余 " + OfflineModelStore.human(remaining));
                updateSelectedInfo();
                refresh(false);
            });
        });
    }

    private void deleteSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, id);
        if (model == null || downloadInProgress) return;
        status.setText("正在删除 " + model.name + "……");
        worker.execute(() -> {
            boolean ok = modelStore.delete(id);
            if (ok && ImportedAsrModels.isImported(id)) ImportedAsrModels.remove(this, id);
            runOnUiThread(() -> {
                if (ok && ImportedAsrModels.isImported(id)) {
                    rebuildModelSelector(TranslationService.ASR_VOSK);
                }
                status.setText(ok ? "✅ 已删除 " + model.name : "删除时遇到部分失败");
                if (id.equals(prefs.getString("asr_mode", ""))) {
                    prefs.edit().putString("asr_mode", TranslationService.ASR_SYSTEM).apply();
                }
                updateSelectedInfo();
                refresh(false);
            });
        });
    }

    private void setDefaultSelected() {
        String id = selectedModelId();
        OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(this, id);
        if (model != null && !modelStore.isInstalled(id)) {
            long readyBytes = modelStore.cachedArchiveBytes(id);
            status.setText(readyBytes > 0L
                ? "完整下载包已在本机，请先点“重新解压 / 安装”，安装成功后再设为默认"
                : "请先下载 " + model.name + "，下载完成后再设为默认");
            return;
        }
        prefs.edit().putString("asr_mode", id).apply();
        status.setText("✅ 已保存默认 ASR：" + modelLabels.get(asrModelSpinner.getSelectedItemPosition()));
        toast("默认 ASR 已保存");
    }

    private void refresh(boolean overwriteStatus) {
        if (overwriteStatus && status != null && !downloadInProgress) status.setText("正在扫描……");
        worker.execute(() -> {
            File voskBase = new File(getFilesDir(), "vosk-models");
            long voskBytes = OfflineModelStore.folderSize(voskBase);
            int voskCount = countModelFolders(voskBase);
            long sherpaInstalledBytes = modelStore.allInstalledBytes();
            long partialBytes = modelStore.partialDownloadBytes();
            long readyBytes = modelStore.completedDownloadCacheBytes();
            long freeBytes = modelStore.availableBytes();
            int sherpaCount = 0;
            for (OfflineAsrModelCatalog.Model model : OfflineAsrModelCatalog.all(this)) {
                if (modelStore.isInstalled(model.id)) sherpaCount++;
            }
            int finalSherpaCount = sherpaCount;
            RemoteModelManager manager = RemoteModelManager.getInstance();
            manager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    String text = "Vosk：" + voskCount + " 个，约 " + OfflineModelStore.human(voskBytes) + "\n" +
                        "高精度 sherpa-onnx：" + finalSherpaCount + " 个，已安装 " + OfflineModelStore.human(sherpaInstalledBytes) + "\n" +
                        "完整下载待安装缓存：" + OfflineModelStore.human(readyBytes) + "\n" +
                        "未完成下载/解压缓存：" + OfflineModelStore.human(partialBytes) + "\n" +
                        "手机当前可用空间：" + OfflineModelStore.human(freeBytes) + "\n" +
                        "ML Kit 翻译：" + models.size() + " 个语言模型";
                    summary.setText(text);
                    if (clearPartialButton != null) {
                        clearPartialButton.setEnabled(!downloadInProgress && (readyBytes + partialBytes) > 0L);
                    }
                    if (overwriteStatus && !downloadInProgress) status.setText("模型状态已刷新");
                    updateSelectedInfo();
                })
                .addOnFailureListener(e -> {
                    summary.setText("Vosk：" + voskCount + " 个 · sherpa：" + finalSherpaCount + " 个\n"
                        + "完整下载待安装缓存：" + OfflineModelStore.human(readyBytes) + "\n"
                        + "未完成下载/解压缓存：" + OfflineModelStore.human(partialBytes) + "\n"
                        + "手机当前可用空间：" + OfflineModelStore.human(freeBytes));
                    if (clearPartialButton != null) {
                        clearPartialButton.setEnabled(!downloadInProgress && (readyBytes + partialBytes) > 0L);
                    }
                    if (overwriteStatus && !downloadInProgress) {
                        status.setText("ML Kit 模型列表读取失败：" + safe(e));
                    }
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
                refresh(false);
            });
        });
    }

    private void deleteAllVosk() {
        status.setText("正在删除全部 Vosk 语音模型……");
        worker.execute(() -> {
            boolean ok = OfflineModelStore.deleteRecursively(new File(getFilesDir(), "vosk-models"));
            runOnUiThread(() -> {
                status.setText(ok ? "✅ 已删除全部 Vosk 语音模型" : "删除 Vosk 时遇到部分失败");
                refresh(false);
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
                status.setText("✅ 已删除 ML Kit " + option.label);
                refresh(false);
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
                        status.setText(failed.get() == 0 ? "✅ 已删除全部 ML Kit 翻译模型"
                            : "删除完成，但有 " + failed.get() + " 个失败");
                        refresh(false);
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
        for (File file : files) {
            if (file.isDirectory() && !file.getName().endsWith(".part")) count++;
        }
        return count;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
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
