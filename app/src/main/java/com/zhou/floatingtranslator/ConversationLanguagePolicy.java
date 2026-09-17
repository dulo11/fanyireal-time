package com.zhou.floatingtranslator;

/** Deterministic language evidence and direction rules, independent of Android/ASR providers. */
final class ConversationLanguagePolicy {
    static final String AUTO = "auto", PAIR = "pair", FIXED = "fixed";
    static final String[] MODES = {AUTO, PAIR, FIXED};
    static String normalize(String value) { return AsrTranscriptGuard.normalizeTag(value); }
    static String mode(String value) { return PAIR.equals(value) || FIXED.equals(value) ? value : AUTO; }

    static final class Route {
        final String source, target, partner, reason, error;
        Route(String source, String target, String partner, String reason, String error) {
            this.source = source; this.target = target; this.partner = partner;
            this.reason = reason; this.error = error;
        }
        boolean valid() { return error.isEmpty(); }
    }

    static Route decide(String mode, String mine, String configuredPartner, String rememberedPartner,
                        String fixedInput, String text, String asr, String textLanguage, float confidence) {
        mode = mode(mode); mine = normalize(mine); configuredPartner = normalize(configuredPartner);
        rememberedPartner = normalize(rememberedPartner); fixedInput = normalize(fixedInput);
        asr = normalize(asr); textLanguage = normalize(textLanguage);
        if (FIXED.equals(mode)) {
            if (fixedInput.isEmpty()) return error("请先选择固定输入语言");
            if (!scriptCompatible(text, fixedInput)) return error("识别文字与固定输入语言不符；请检查麦克风、模型或重新说话");
            return new Route(fixedInput, mine, configuredPartner, "固定输入", "");
        }
        boolean confidentText = !textLanguage.isEmpty() && confidence >= 0.80f
            && scriptCompatible(text, textLanguage);
        boolean usefulAsr = !asr.isEmpty() && !"und".equals(asr) && scriptCompatible(text, asr);
        String language = "", reason = "";
        if (confidentText && usefulAsr && !asr.equals(textLanguage)) {
            // Don't silently relabel an utterance when two independent sources disagree.
            return error("语音标签(" + asr + ")与文字检测(" + textLanguage + ")冲突；请重说或选择固定输入语言");
        } else if (confidentText) {
            language = textLanguage; reason = usefulAsr ? "语音与文字一致" : "文字语言复核";
        } else if (usefulAsr) {
            language = asr; reason = "语音语言标签";
        }
        if (language.isEmpty()) return error("本句语言不确定，未猜测翻译方向；请多说几个词或选择固定输入语言");
        if (PAIR.equals(mode) && !language.equals(mine) && !language.equals(configuredPartner))
            return error("本句识别为 " + language + "，不在双方语言 " + mine + "/" + configuredPartner + " 中；可改为自动双向");
        String partner = PAIR.equals(mode) ? configuredPartner : rememberedPartner;
        if (partner.isEmpty() || partner.equals(mine)) partner = configuredPartner;
        if (language.equals(mine)) {
            if (partner.isEmpty() || partner.equals(mine)) return error("请将对方语言设置为不同于我的语言");
            return new Route(language, partner, partner, reason, "");
        }
        return new Route(language, mine, PAIR.equals(mode) ? configuredPartner : language, reason, "");
    }

    private static Route error(String error) { return new Route("", "", "", "", error); }

    // Script checks reject impossible labels, never equate the Latin alphabet with English.
    static boolean scriptCompatible(String text, String language) {
        if (text == null) return false;
        String lang = normalize(language);
        int latin = 0, han = 0, kana = 0, hangul = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 0x00c0 && c <= 0x024f)) latin++;
            else if (c >= 0x3400 && c <= 0x9fff) han++;
            else if (c >= 0x3040 && c <= 0x30ff) kana++;
            else if (c >= 0xac00 && c <= 0xd7af) hangul++;
            else if (Character.isLetter(c)) other++;
        }
        if (han + kana + hangul > 0 && latin + other == 0)
            return ("zh".equals(lang) || "yue".equals(lang)) ? kana == 0 && hangul == 0
                : "ja".equals(lang) ? hangul == 0 : "ko".equals(lang) && kana == 0;
        if (latin > 0 && han + kana + hangul + other == 0)
            return !("zh".equals(lang) || "ja".equals(lang) || "ko".equals(lang) || "yue".equals(lang));
        return true;
    }

    static boolean supportsAutomatic(String engine, String mine, String partner) {
        if ("vosk".equals(engine) || "reazon_ja".equals(engine) || "parakeet_ja".equals(engine)) return false;
        if ("sensevoice".equals(engine)) return senseVoice(mine) && senseVoice(partner);
        return true;
    }
    static boolean supportsFixed(String engine, String language) {
        if ("qwen3_asr_06b".equals(engine) || "omnilingual_300m".equals(engine)) return false;
        if ("sensevoice".equals(engine)) return senseVoice(language);
        if ("reazon_ja".equals(engine) || "parakeet_ja".equals(engine)) return "ja".equals(normalize(language));
        return true;
    }
    private static boolean senseVoice(String language) {
        String lang = normalize(language);
        return "zh".equals(lang) || "en".equals(lang) || "ja".equals(lang) || "ko".equals(lang) || "yue".equals(lang);
    }
}
