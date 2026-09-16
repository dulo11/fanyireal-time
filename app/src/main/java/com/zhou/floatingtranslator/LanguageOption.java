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

    // Complete ML Kit on-device translation list (59 base languages) plus regional speech variants.
    // Regional variants reuse the same translation model but request a more appropriate ASR locale.
    // Existing 59 entries stay in the original order so saved source/target indexes remain compatible.
    public static final LanguageOption[] ALL = new LanguageOption[] {
        new LanguageOption("中文（普通话）", "zh-CN", "zh"),
        new LanguageOption("英语（美国）", "en-US", "en"),
        new LanguageOption("日语（日本）", "ja-JP", "ja"),
        new LanguageOption("越南语（越南）", "vi-VN", "vi"),
        new LanguageOption("菲律宾语（菲律宾）", "fil-PH", "tl"),
        new LanguageOption("马来语（马来西亚）", "ms-MY", "ms"),
        new LanguageOption("韩语（韩国）", "ko-KR", "ko"),
        new LanguageOption("法语（法国）", "fr-FR", "fr"),
        new LanguageOption("德语（德国）", "de-DE", "de"),
        new LanguageOption("西班牙语（西班牙）", "es-ES", "es"),
        new LanguageOption("葡萄牙语（巴西）", "pt-BR", "pt"),
        new LanguageOption("俄语（俄罗斯）", "ru-RU", "ru"),
        new LanguageOption("阿拉伯语（沙特）", "ar-SA", "ar"),
        new LanguageOption("南非荷兰语（南非）", "af-ZA", "af"),
        new LanguageOption("阿尔巴尼亚语（阿尔巴尼亚）", "sq-AL", "sq"),
        new LanguageOption("白俄罗斯语（白俄罗斯）", "be-BY", "be"),
        new LanguageOption("保加利亚语（保加利亚）", "bg-BG", "bg"),
        new LanguageOption("孟加拉语（孟加拉国）", "bn-BD", "bn"),
        new LanguageOption("加泰罗尼亚语（西班牙）", "ca-ES", "ca"),
        new LanguageOption("捷克语（捷克）", "cs-CZ", "cs"),
        new LanguageOption("威尔士语（英国）", "cy-GB", "cy"),
        new LanguageOption("丹麦语（丹麦）", "da-DK", "da"),
        new LanguageOption("希腊语（希腊）", "el-GR", "el"),
        new LanguageOption("世界语", "eo", "eo"),
        new LanguageOption("爱沙尼亚语（爱沙尼亚）", "et-EE", "et"),
        new LanguageOption("波斯语（伊朗）", "fa-IR", "fa"),
        new LanguageOption("芬兰语（芬兰）", "fi-FI", "fi"),
        new LanguageOption("爱尔兰语（爱尔兰）", "ga-IE", "ga"),
        new LanguageOption("加利西亚语（西班牙）", "gl-ES", "gl"),
        new LanguageOption("古吉拉特语（印度）", "gu-IN", "gu"),
        new LanguageOption("希伯来语（以色列）", "he-IL", "he"),
        new LanguageOption("印地语（印度）", "hi-IN", "hi"),
        new LanguageOption("克罗地亚语（克罗地亚）", "hr-HR", "hr"),
        new LanguageOption("海地克里奥尔语（海地）", "ht-HT", "ht"),
        new LanguageOption("匈牙利语（匈牙利）", "hu-HU", "hu"),
        new LanguageOption("印度尼西亚语（印尼）", "id-ID", "id"),
        new LanguageOption("冰岛语（冰岛）", "is-IS", "is"),
        new LanguageOption("意大利语（意大利）", "it-IT", "it"),
        new LanguageOption("格鲁吉亚语（格鲁吉亚）", "ka-GE", "ka"),
        new LanguageOption("卡纳达语（印度）", "kn-IN", "kn"),
        new LanguageOption("立陶宛语（立陶宛）", "lt-LT", "lt"),
        new LanguageOption("拉脱维亚语（拉脱维亚）", "lv-LV", "lv"),
        new LanguageOption("马其顿语（北马其顿）", "mk-MK", "mk"),
        new LanguageOption("马拉地语（印度）", "mr-IN", "mr"),
        new LanguageOption("马耳他语（马耳他）", "mt-MT", "mt"),
        new LanguageOption("荷兰语（荷兰）", "nl-NL", "nl"),
        new LanguageOption("挪威语（挪威）", "no-NO", "no"),
        new LanguageOption("波兰语（波兰）", "pl-PL", "pl"),
        new LanguageOption("罗马尼亚语（罗马尼亚）", "ro-RO", "ro"),
        new LanguageOption("斯洛伐克语（斯洛伐克）", "sk-SK", "sk"),
        new LanguageOption("斯洛文尼亚语（斯洛文尼亚）", "sl-SI", "sl"),
        new LanguageOption("瑞典语（瑞典）", "sv-SE", "sv"),
        new LanguageOption("斯瓦希里语（肯尼亚）", "sw-KE", "sw"),
        new LanguageOption("泰米尔语（印度）", "ta-IN", "ta"),
        new LanguageOption("泰卢固语（印度）", "te-IN", "te"),
        new LanguageOption("泰语（泰国）", "th-TH", "th"),
        new LanguageOption("土耳其语（土耳其）", "tr-TR", "tr"),
        new LanguageOption("乌克兰语（乌克兰）", "uk-UA", "uk"),
        new LanguageOption("乌尔都语（巴基斯坦）", "ur-PK", "ur"),

        // Regional speech/locale variants. Translation model is shared with the base language.
        new LanguageOption("英语（英国）", "en-GB", "en"),
        new LanguageOption("英语（澳大利亚）", "en-AU", "en"),
        new LanguageOption("英语（加拿大）", "en-CA", "en"),
        new LanguageOption("英语（印度）", "en-IN", "en"),
        new LanguageOption("英语（新加坡）", "en-SG", "en"),
        new LanguageOption("英语（新西兰）", "en-NZ", "en"),
        new LanguageOption("英语（菲律宾）", "en-PH", "en"),
        new LanguageOption("法语（加拿大）", "fr-CA", "fr"),
        new LanguageOption("法语（比利时）", "fr-BE", "fr"),
        new LanguageOption("法语（瑞士）", "fr-CH", "fr"),
        new LanguageOption("德语（奥地利）", "de-AT", "de"),
        new LanguageOption("德语（瑞士）", "de-CH", "de"),
        new LanguageOption("西班牙语（墨西哥）", "es-MX", "es"),
        new LanguageOption("西班牙语（美国）", "es-US", "es"),
        new LanguageOption("西班牙语（阿根廷）", "es-AR", "es"),
        new LanguageOption("西班牙语（哥伦比亚）", "es-CO", "es"),
        new LanguageOption("西班牙语（智利）", "es-CL", "es"),
        new LanguageOption("葡萄牙语（葡萄牙）", "pt-PT", "pt"),
        new LanguageOption("阿拉伯语（埃及）", "ar-EG", "ar"),
        new LanguageOption("阿拉伯语（阿联酋）", "ar-AE", "ar"),
        new LanguageOption("阿拉伯语（卡塔尔）", "ar-QA", "ar"),
        new LanguageOption("阿拉伯语（科威特）", "ar-KW", "ar"),
        new LanguageOption("阿拉伯语（伊拉克）", "ar-IQ", "ar"),
        new LanguageOption("阿拉伯语（约旦）", "ar-JO", "ar"),
        new LanguageOption("阿拉伯语（黎巴嫩）", "ar-LB", "ar"),
        new LanguageOption("阿拉伯语（摩洛哥）", "ar-MA", "ar"),
        new LanguageOption("荷兰语（比利时）", "nl-BE", "nl"),
        new LanguageOption("意大利语（瑞士）", "it-CH", "it"),
        new LanguageOption("斯瓦希里语（坦桑尼亚）", "sw-TZ", "sw"),
        new LanguageOption("孟加拉语（印度）", "bn-IN", "bn"),
        new LanguageOption("泰米尔语（斯里兰卡）", "ta-LK", "ta"),
        new LanguageOption("乌尔都语（印度）", "ur-IN", "ur")
    };
}
