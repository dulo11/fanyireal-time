package com.zhou.floatingtranslator;

import com.google.mlkit.nl.translate.TranslateLanguage;

public final class LanguageOption {
    public final String label;
    public final String speechTag;
    public final String mlKitTag;

    public LanguageOption(String label, String speechTag, String mlKitTag) {
        this.label = label;
        this.speechTag = speechTag;
        this.mlKitTag = mlKitTag;
    }

    @Override public String toString() { return label; }

    public static final LanguageOption[] ALL = new LanguageOption[] {
        new LanguageOption("中文（普通话）", "zh-CN", TranslateLanguage.CHINESE),
        new LanguageOption("英语", "en-US", TranslateLanguage.ENGLISH),
        new LanguageOption("日语", "ja-JP", TranslateLanguage.JAPANESE),
        new LanguageOption("越南语", "vi-VN", TranslateLanguage.VIETNAMESE),
        new LanguageOption("菲律宾语", "fil-PH", TranslateLanguage.TAGALOG),
        new LanguageOption("马来语", "ms-MY", TranslateLanguage.MALAY)
    };
}
