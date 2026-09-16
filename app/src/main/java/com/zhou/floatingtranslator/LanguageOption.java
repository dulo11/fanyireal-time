package com.zhou.floatingtranslator;

/** A translation language plus the locale requested from Android speech recognition. */
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

    // Complete ML Kit on-device translation list (59 languages).
    // Speech recognition availability depends on the recognition service installed on the phone.
    public static final LanguageOption[] ALL = new LanguageOption[] {
        new LanguageOption("中文（普通话）", "zh-CN", "zh"),
        new LanguageOption("英语", "en-US", "en"),
        new LanguageOption("日语", "ja-JP", "ja"),
        new LanguageOption("越南语", "vi-VN", "vi"),
        new LanguageOption("菲律宾语", "fil-PH", "tl"),
        new LanguageOption("马来语", "ms-MY", "ms"),
        new LanguageOption("韩语", "ko-KR", "ko"),
        new LanguageOption("法语", "fr-FR", "fr"),
        new LanguageOption("德语", "de-DE", "de"),
        new LanguageOption("西班牙语", "es-ES", "es"),
        new LanguageOption("葡萄牙语", "pt-BR", "pt"),
        new LanguageOption("俄语", "ru-RU", "ru"),
        new LanguageOption("阿拉伯语", "ar-SA", "ar"),
        new LanguageOption("南非荷兰语", "af-ZA", "af"),
        new LanguageOption("阿尔巴尼亚语", "sq-AL", "sq"),
        new LanguageOption("白俄罗斯语", "be-BY", "be"),
        new LanguageOption("保加利亚语", "bg-BG", "bg"),
        new LanguageOption("孟加拉语", "bn-BD", "bn"),
        new LanguageOption("加泰罗尼亚语", "ca-ES", "ca"),
        new LanguageOption("捷克语", "cs-CZ", "cs"),
        new LanguageOption("威尔士语", "cy-GB", "cy"),
        new LanguageOption("丹麦语", "da-DK", "da"),
        new LanguageOption("希腊语", "el-GR", "el"),
        new LanguageOption("世界语", "eo", "eo"),
        new LanguageOption("爱沙尼亚语", "et-EE", "et"),
        new LanguageOption("波斯语", "fa-IR", "fa"),
        new LanguageOption("芬兰语", "fi-FI", "fi"),
        new LanguageOption("爱尔兰语", "ga-IE", "ga"),
        new LanguageOption("加利西亚语", "gl-ES", "gl"),
        new LanguageOption("古吉拉特语", "gu-IN", "gu"),
        new LanguageOption("希伯来语", "he-IL", "he"),
        new LanguageOption("印地语", "hi-IN", "hi"),
        new LanguageOption("克罗地亚语", "hr-HR", "hr"),
        new LanguageOption("海地克里奥尔语", "ht-HT", "ht"),
        new LanguageOption("匈牙利语", "hu-HU", "hu"),
        new LanguageOption("印度尼西亚语", "id-ID", "id"),
        new LanguageOption("冰岛语", "is-IS", "is"),
        new LanguageOption("意大利语", "it-IT", "it"),
        new LanguageOption("格鲁吉亚语", "ka-GE", "ka"),
        new LanguageOption("卡纳达语", "kn-IN", "kn"),
        new LanguageOption("立陶宛语", "lt-LT", "lt"),
        new LanguageOption("拉脱维亚语", "lv-LV", "lv"),
        new LanguageOption("马其顿语", "mk-MK", "mk"),
        new LanguageOption("马拉地语", "mr-IN", "mr"),
        new LanguageOption("马耳他语", "mt-MT", "mt"),
        new LanguageOption("荷兰语", "nl-NL", "nl"),
        new LanguageOption("挪威语", "no-NO", "no"),
        new LanguageOption("波兰语", "pl-PL", "pl"),
        new LanguageOption("罗马尼亚语", "ro-RO", "ro"),
        new LanguageOption("斯洛伐克语", "sk-SK", "sk"),
        new LanguageOption("斯洛文尼亚语", "sl-SI", "sl"),
        new LanguageOption("瑞典语", "sv-SE", "sv"),
        new LanguageOption("斯瓦希里语", "sw-KE", "sw"),
        new LanguageOption("泰米尔语", "ta-IN", "ta"),
        new LanguageOption("泰卢固语", "te-IN", "te"),
        new LanguageOption("泰语", "th-TH", "th"),
        new LanguageOption("土耳其语", "tr-TR", "tr"),
        new LanguageOption("乌克兰语", "uk-UA", "uk"),
        new LanguageOption("乌尔都语", "ur-PK", "ur")
    };
}
