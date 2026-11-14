package com.zhou.floatingtranslator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Plain-language, inspectable explanation of capture permissions and provider
 * destinations. This page does NOT request audio, screen, root or network access.
 */
public final class PrivacyAndSourcesActivity extends Activity {
    private final int textColor = Color.rgb(211, 201, 229);
    private final int muted = Color.rgb(167, 156, 191);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("浮译 · 权限、数据去向与源码");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 13, 30));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(34));
        scroll.addView(root);

        add(root, "🔒 谁会接触音频与字幕？", 25, Color.WHITE);
        add(root, "可直接让其他人看这页，并打开下面的 GitHub 源码自行检查。"
            + "这不是第三方安全审计或“永不上传”的保证："
            + "实际数据是否上传取决于你选择的 ASR 与翻译引擎。", 14, textColor);

        SharedPreferences prefs = getSharedPreferences("floating_translator", MODE_PRIVATE);
        String asr = prefs.getString("asr_mode", TranslationService.ASR_SYSTEM);
        String translation = prefs.getString("engine_id", TranslationRouter.AUTO);
        int input = prefs.getInt("input_mode", 0);
        String audio = input == 0 ? "系统内部音频（MediaProjection，需要系统授权）"
            : input == 1 ? "麦克风（需要录音权限）"
            : input == 2 ? "ROOT/Shizuku 通话 PCM（高权限实验）"
            : "ROOT 直播 PCM（高权限实验）";
        OfflineAsrModelCatalog.Model local = OfflineAsrModelCatalog.find(this, asr);
        String asrLabel = local != null ? local.name + " · 本地 sherpa-onnx"
            : asr.equals(TranslationService.ASR_SYSTEM) ? "安卓系统语音服务（是否联网由系统服务决定）"
            : asr.equals(TranslationService.ASR_VOSK) ? "本地 Vosk（离线运行）"
            : "已配置的第三方在线语音识别 API（请看具体服务商）";
        add(root, "当前已保存的配置", 19, Color.WHITE);
        add(root, "音频来源：" + audio
            + "\nASR：" + asrLabel
            + "\n翻译：" + translationLabel(translation)
            + "\nOCR：" + (prefs.getBoolean("enable_ocr", false) ? "已开启" : "关闭")
            + "\n这些是已保存的选项，正在运行的服务可能仍是启动前的配置。", 14, textColor);

        add(root, "① 音频采集权限", 19, Color.WHITE);
        add(root, "• 系统内部声音：Android 要求一次屏幕共享/录制授权。"
            + "在关闭 OCR 的直播翻译模式，程序的目标是只消费音频 PCM，不主动抓视频帧。"
            + "\n• 麦克风：必须由系统授权 RECORD_AUDIO。"
            + "\n• ROOT/Shizuku：拥有更高权限，可能触及音频设备，需对设备所有人和通话参与者取得同意。"
            + "\n• 悬浮窗：用于显示字幕。"
            + "\n• 无障碍全屏翻译：是独立功能，可读取可访问文字、并在对应选项下使用截图。"
            + "不需要全屏翻译时可以不启用无障碍。", 14, textColor);

        add(root, "② 数据去向（不要混淆语音识别和文字翻译）", 19, Color.WHITE);
        add(root, "• 本地 sherpa-onnx / Vosk：识别计算在手机内进行，导入模型不上传。"
            + "\n• 安卓系统 SpeechRecognizer：由手机上安装的识别服务实现，"
            + "它可能调用联网识别；浮译不能代替系统保证离线。"
            + "\n• Groq、Cloudflare 等在线 ASR：需要把音频片段提交给你明确选择的服务商。"
            + "\n• Azure、百度、阿里云等在线翻译：把识别后的文字提交给服务商进行翻译。"
            + "\n• ML Kit 离线翻译：翻译在本机执行，首次下载语言包可能联网。"
            + "\n• 检查更新、下载模型等主动动作也会联网，不能将“已使用本地模型”"
            + "理解为整个 APP 绝不联网。", 14, textColor);

        add(root, "③ 如何自行检查和减少权限", 19, Color.WHITE);
        add(root, "开始翻译前手动选择声音来源、识别模型与翻译引擎；"
            + "禁用不需要的全屏翻译无障碍权限；"
            + "不使用 ROOT 时不要授予 ROOT 权限；"
            + "使用 Android 系统权限管理核对录音、悬浮窗、屏幕授权；"
            + "也可以用本机网络抓包工具核实实际连接的域名。"
            + "不应在未经他人知情同意的情况下录制或转写他人的通话。", 14, textColor);

        Button share = new Button(this);
        share.setText("📤 分享权限/数据说明（不包含任何个人配置）");
        share.setTextColor(Color.WHITE);
        share.setAllCaps(false);
        share.setBackgroundResource(R.drawable.button_secondary);
        share.setOnClickListener(v -> {
            String message = "浮译 · 权限与数据去向说明\\n"
                + "1. 安卓系统内部音频需要系统共享授权，但关闭 OCR 时音频字幕功能不主动采画面。\\n"
                + "2. 本地 ASR 模型在手机运行；在线 ASR 则向选中的供应商发送语音。\\n"
                + "3. 微软 Azure 等在线翻译需向供应商发送识别后的文字；ML Kit 离线翻译计算在本机。\\n"
                + "4. ROOT/无障碍全屏翻译是高权限可选功能，应由设备所有人知情启用。\\n"
                + "源代码：https://github.com/dulo11/fanyireal-time";
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, message);
            startActivity(Intent.createChooser(shareIntent, "分享浮译权限说明"));
        });
        root.addView(share, new LinearLayout.LayoutParams(-1, -2));

        button(root, "🔎 查看浮译完整源代码",
            "https://github.com/dulo11/fanyireal-time");
        button(root, "📘 Android 系统语音识别的公开接口",
            ModelSourceLinks.speechRecognizerUrl(TranslationService.ASR_SYSTEM));
        button(root, "📘 Android 内部音频采集文档",
            "https://developer.android.com/media/platform/av-capture");
        button(root, "📦 sherpa-onnx 模型索引 / Android 支持",
            "https://k2-fsa.github.io/sherpa/onnx/pretrained_models/");
        button(root, "🎤 Groq 语音识别官方接口",
            ModelSourceLinks.speechRecognizerUrl(TranslationService.ASR_GROQ_LARGE));
        button(root, "🎤 Cloudflare 语音识别模型",
            ModelSourceLinks.speechRecognizerUrl(TranslationService.ASR_CLOUDFLARE));
        button(root, "🌐 微软 Azure Translator 官方文档",
            ModelSourceLinks.translatorUrl(TranslationRouter.AZURE));
        Button models = new Button(this);
        models.setText("＋ 自行导入本地 ASR 模型（无需上传）");
        models.setTextColor(Color.WHITE);
        models.setAllCaps(false);
        models.setBackgroundResource(R.drawable.button_secondary);
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelManagerActivity.class)));
        root.addView(models, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private static String translationLabel(String id) {
        for (int i = 0; i < TranslationRouter.ENGINE_IDS.length; i++) {
            if (TranslationRouter.ENGINE_IDS[i].equals(id)) return TranslationRouter.ENGINE_LABELS[i];
        }
        return id;
    }

    private void add(LinearLayout parent, String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, dp(9), 0, dp(9));
        parent.addView(text);
    }

    private void button(LinearLayout parent, String name, String url) {
        Button button = new Button(this);
        button.setText(name + "\n" + url);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.button_secondary);
        button.setOnClickListener(v -> ModelSourceLinks.open(this, url));
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
