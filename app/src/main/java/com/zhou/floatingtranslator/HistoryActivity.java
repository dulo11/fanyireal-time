package com.zhou.floatingtranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** View, clear and export persistent translation history. */
public final class HistoryActivity extends Activity {
    private static final int EXPORT_TXT = 41;
    private static final int EXPORT_SRT = 42;
    private android.widget.EditText search;private android.widget.Spinner category;
    private static final String[] CATEGORIES={"","realtime","call","conversation","sample","legacy"};
    private java.util.List<HistoryStore.Entry> filtered=new java.util.ArrayList<>(),exportEntries=new java.util.ArrayList<>();
    private String exportSnapshot;
    private TextView summary;
    private TextView content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("浮译 "+BuildConfig.VERSION_NAME+" · 历史记录");
        setContentView(buildUi());
        if(state!=null){search.setText(state.getString("query",""));category.setSelection(Math.max(0,Math.min(CATEGORIES.length-1,state.getInt("category",0))));String saved=state.getString("export_snapshot");if(saved!=null&&saved.matches("history-export-[a-zA-Z0-9.-]+"))exportSnapshot=saved;}
        refresh();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text("翻译历史", 30, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = text("自动保存实时翻译的原文、译文和时间。最多保留最近 5000 条；搜索与分类后的结果可导出 TXT 或 SRT（时间按翻译完成时间估算）。", 14,
            Color.rgb(201, 190, 221));
        note.setPadding(0, dp(5), 0, dp(12));
        root.addView(note);

        search=new android.widget.EditText(this);search.setHint("搜索原文或译文");search.setTextColor(Color.WHITE);search.setHintTextColor(Color.LTGRAY);root.addView(search);
        category=new android.widget.Spinner(this);category.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部","实时翻译","通话","面对面","示例测试","旧记录"}));root.addView(category);
        Button filter=button("搜索 / 应用分类");filter.setOnClickListener(v->refresh());root.addView(filter);
        summary = text("", 14, Color.rgb(190, 165, 255));
        root.addView(summary);

        Button txt = button("导出 TXT");
        txt.setOnClickListener(v -> createExport(EXPORT_TXT, "txt"));
        root.addView(txt, params());

        Button srt = button("导出 SRT 字幕");
        srt.setOnClickListener(v -> createExport(EXPORT_SRT, "srt"));
        root.addView(srt, params());

        Button refresh = button("刷新历史");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, params());

        Button clear = button("清空全部历史");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("清空翻译历史？")
            .setMessage("这个操作只删除历史记录，不会删除模型或设置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (d, which) -> {
                boolean ok = HistoryStore.clear(this);
                toast(ok ? "历史已清空" : "清空失败");
                refresh();
            }).show());
        root.addView(clear, params());

        content = text("", 14, Color.rgb(225, 220, 235));
        content.setPadding(0, dp(16), 0, 0);
        content.setGravity(Gravity.START);
        root.addView(content);
        return scroll;
    }

    private void refresh() {
        filtered=HistoryStore.filter(HistoryStore.readAll(this),search.getText().toString(),CATEGORIES[category.getSelectedItemPosition()]);
        List<HistoryStore.Entry> entries=filtered.subList(Math.max(0,filtered.size()-200),filtered.size());
        int total = HistoryStore.count(this);
        summary.setText(getString(R.string.history_01, total, filtered.size(), entries.size()));
        if (entries.isEmpty()) {
            content.setText("暂无历史。开始实时翻译后会自动记录。");
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder out = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            HistoryStore.Entry e = entries.get(i);
            out.append('[').append(format.format(new Date(e.time))).append("]\n");
            if (!e.original.isEmpty()) out.append("原文：").append(e.original).append('\n');
            out.append("译文：").append(e.translated).append("\n\n");
        }
        content.setText(out.toString());
    }

    private void createExport(int requestCode, String extension) {
        if (HistoryStore.count(this) == 0) {
            toast("还没有历史记录");
            return;
        }
        refresh();exportEntries=new java.util.ArrayList<>(filtered);if(exportEntries.isEmpty()){toast("当前筛选没有记录");return;}
        try{
            java.io.File snapshot=java.io.File.createTempFile("history-export-",".txt",getCacheDir());
            String value=requestCode==EXPORT_SRT?HistoryStore.buildSrt(exportEntries):HistoryStore.buildTxt(exportEntries);
            java.nio.file.Files.write(snapshot.toPath(),value.getBytes(StandardCharsets.UTF_8));exportSnapshot=snapshot.getName();
        }catch(Exception e){toast("创建导出快照失败："+e.getMessage());return;}
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "FloatingTranslator-history-" + stamp + "." + extension);
        startActivityForResult(intent, requestCode);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != EXPORT_TXT && requestCode != EXPORT_SRT) return;
        Uri uri = data.getData();
        if(exportSnapshot==null){toast("导出快照不存在，请重新选择导出");return;}
        java.io.File snapshot=new java.io.File(getCacheDir(),exportSnapshot);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("无法打开目标文件");
            try(java.io.InputStream in=new java.io.FileInputStream(snapshot)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            out.flush();snapshot.delete();exportSnapshot=null;
            toast(requestCode == EXPORT_SRT ? "SRT 已导出" : "TXT 已导出");
        } catch (Exception e) {
            toast("导出失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putString("query",search.getText().toString());state.putInt("category",category.getSelectedItemPosition());state.putString("export_snapshot",exportSnapshot);
    }
    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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

    private LinearLayout.LayoutParams params() {
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
}
