package com.zhou.floatingtranslator;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

/** Transitional localizer for legacy screens that still contain hard-coded Chinese labels. */
public final class UiLocalizer {
    private UiLocalizer() {}

    public static void apply(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root != null) walk(activity, root);
    }

    private static void walk(Activity a, View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence value = t.getText();
            if (value != null) {
                int id = idFor(value.toString());
                if (id != 0) t.setText(a.getString(id));
            }
            if (v instanceof EditText) {
                CharSequence hint = ((EditText) v).getHint();
                if (hint != null) {
                    int id = idFor(hint.toString());
                    if (id != 0) ((EditText) v).setHint(a.getString(id));
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) walk(a, group.getChildAt(i));
        }
    }

    private static int idFor(String s) {
        switch (s) {
            case "浮译": return R.string.app_name;
            case "实时翻译控制台": return R.string.ui_realtime_title;
            case "全屏翻译": return R.string.ui_screen_title;
            case "离线模型中心": return R.string.ui_models_title;
            case "翻译引擎 / API 配置": return R.string.ui_api_title;
            case "翻译历史": return R.string.ui_history_title;
            case "当前状态 · 运行内存实时刷新": return R.string.ui_current_status;
            case "实时翻译": return R.string.ui_realtime;
            case "▶ 打开实时翻译控制台": return R.string.ui_open_realtime;
            case "📦 离线模型中心": return R.string.ui_models;
            case "工具": return R.string.ui_tools;
            case "🌐 全屏翻译": return R.string.ui_screen;
            case "📝 历史": return R.string.ui_history;
            case "☎ ROOT 通话": return R.string.ui_root;
            case "🎚 ASR 精度": return R.string.ui_asr;
            case "⚙ 翻译引擎 / API 设置": return R.string.ui_api;
            case "⬆ 检查更新 / ↩ 稳定回滚": return R.string.ui_update;
            case "最近翻译": return R.string.ui_recent;
            case "复制最近译文": return R.string.ui_copy;
            case "声音与识别": return R.string.ui_audio;
            case "翻译语言": return R.string.ui_translation_language;
            case "字幕与显示": return R.string.ui_subtitles;
            case "开始前准备": return R.string.ui_prepare;
            case "状态 / 最近翻译": return R.string.ui_status_recent;
            case "无障碍服务": return R.string.ui_accessibility;
            case "翻译方式": return R.string.ui_translation_mode;
            case "语言与翻译引擎": return R.string.ui_language_engine;
            case "怎么用": return R.string.ui_how_to_use;
            case "高精度离线 ASR": return R.string.ui_offline_asr;
            case "Vosk / ML Kit 按语言管理": return R.string.ui_model_by_language;
            case "默认翻译引擎": return R.string.ui_default_engine;
            default: return 0;
        }
    }
}
