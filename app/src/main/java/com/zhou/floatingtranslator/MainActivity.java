package com.zhou.floatingtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_CAPTURE = 11;

    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private CheckBox showOriginal;
    private SeekBar fontSize;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = column(20);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = text("浮译", 34, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = text("其他 App 声音 · 实时悬浮翻译", 16, Color.rgb(201,190,221));
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        LinearLayout card = column(14);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(com.zhou.floatingtranslator.R.drawable.panel);
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        card.addView(label("声音语言"));
        sourceSpinner = spinner();
        sourceSpinner.setSelection(1);
        card.addView(sourceSpinner, matchWrap());

        card.addView(label("翻译为"));
        targetSpinner = spinner();
        targetSpinner.setSelection(0);
        card.addView(targetSpinner, matchWrap());

        showOriginal = new CheckBox(this);
        showOriginal.setText("同时显示原文");
        showOriginal.setTextColor(Color.WHITE);
        showOriginal.setChecked(true);
        card.addView(showOriginal);

        TextView sizeLabel = label("字幕大小");
        card.addView(sizeLabel);
        fontSize = new SeekBar(this);
        fontSize.setMax(18);
        fontSize.setProgress(8);
        card.addView(fontSize, matchWrap());

        Button overlay = secondaryButton("① 授予悬浮窗权限");
        overlay.setOnClickListener(v -> openOverlaySettings());
        card.addView(overlay, matchWrap());

        Button model = secondaryButton("② 下载当前语言模型");
        model.setOnClickListener(v -> downloadCurrentModel(model));
        card.addView(model, matchWrap());

        Button start = primaryButton("③ 开始实时翻译");
        start.setOnClickListener(v -> startCapture());
        card.addView(start, matchWrap());

        Button stop = secondaryButton("停止翻译");
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, TranslationService.class).setAction(TranslationService.ACTION_STOP);
            startService(intent);
            status.setText("已停止");
        });
        card.addView(stop, matchWrap());

        status = text("首次使用：先授权悬浮窗，再下载模型。", 14, Color.rgb(201,190,221));
        status.setPadding(0, dp(14), 0, 0);
        card.addView(status);

        TextView note = text(
            "支持：中文、英语、日语、越南语、菲律宾语、马来语。\n\n" +
            "说明：系统会在每次启动时显示“开始录制或投射”确认，这是 Android 的安全要求。" +
            "部分 App 会主动禁止内部音频捕获，此时字幕可能没有声音输入。",
            14, Color.rgb(201,190,221));
        note.setPadding(dp(4), dp(22), dp(4), 0);
        root.addView(note);
        return scroll;
    }

    private Spinner spinner() {
        Spinner s = new Spinner(this);
        ArrayAdapter<LanguageOption> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, LanguageOption.ALL);
        s.setAdapter(adapter);
        s.setBackgroundColor(Color.rgb(51,45,73));
        return s;
    }

    private void downloadCurrentModel(Button button) {
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("原语言和目标语言不能相同");
            return;
        }
        button.setEnabled(false);
        status.setText("正在下载模型，请保持网络连接……");
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag)
            .setTargetLanguage(target.mlKitTag)
            .build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> {
                status.setText("模型已就绪，之后可以离线翻译");
                button.setEnabled(true);
                translator.close();
            })
            .addOnFailureListener(e -> {
                status.setText("模型下载失败：" + e.getMessage());
                button.setEnabled(true);
                translator.close();
            });
    }

    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            openOverlaySettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            return;
        }
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        if (source.mlKitTag.equals(target.mlKitTag)) {
            toast("请选择不同的原语言和目标语言");
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE || resultCode != RESULT_OK || data == null) {
            if (requestCode == REQUEST_CAPTURE) status.setText("你取消了系统声音授权");
            return;
        }
        LanguageOption source = (LanguageOption) sourceSpinner.getSelectedItem();
        LanguageOption target = (LanguageOption) targetSpinner.getSelectedItem();
        Intent service = new Intent(this, TranslationService.class)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_RESULT_DATA, data)
            .putExtra(TranslationService.EXTRA_SOURCE_SPEECH, source.speechTag)
            .putExtra(TranslationService.EXTRA_SOURCE_MLKIT, source.mlKitTag)
            .putExtra(TranslationService.EXTRA_TARGET_MLKIT, target.mlKitTag)
            .putExtra(TranslationService.EXTRA_SHOW_ORIGINAL, showOriginal.isChecked())
            .putExtra(TranslationService.EXTRA_FONT_SIZE, 16 + fontSize.getProgress());
        startForegroundService(service);
        status.setText("翻译已启动，可以切换到视频或直播 App");
        toast("浮译正在后台运行");
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
        }
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
    }

    private LinearLayout column(int spacingDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return layout;
    }
    private TextView label(String value) { return text(value, 14, Color.rgb(201,190,221)); }
    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); return t;
    }
    private Button primaryButton(String value) { Button b=button(value); b.setBackgroundResource(R.drawable.button_primary); return b; }
    private Button secondaryButton(String value) { Button b=button(value); b.setBackgroundResource(R.drawable.button_secondary); return b; }
    private Button button(String value) { Button b=new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams matchWrap() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
