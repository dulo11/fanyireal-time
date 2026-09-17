(() => {
  if (globalThis.FTLanguage) return;

  const normalizeLang = value => {
    const lang = String(value || "").trim().toLowerCase().replace(/_/g, "-");
    if (!lang) return "";
    if (lang.startsWith("zh")) {
      return /(tw|hk|mo|hant)/.test(lang) ? "zh-hant" : "zh-hans";
    }
    if (lang === "fil" || lang === "tl") return "fil";
    return lang.split("-")[0];
  };

  function detect(text) {
    const value = String(text || "").trim();
    if (!value) return { lang: "", confidence: 0, ambiguous: true };

    if (/[ぁ-ゟ゠-ヿ]/u.test(value)) return { lang: "ja", confidence: 1, ambiguous: false };
    if (/[가-힣]/u.test(value)) return { lang: "ko", confidence: 1, ambiguous: false };
    if (/[ก-๿]/u.test(value)) return { lang: "th", confidence: 1, ambiguous: false };
    if (/[؀-ۿ]/u.test(value)) return { lang: "ar", confidence: 1, ambiguous: false };
    if (/[Ѐ-ӿ]/u.test(value)) return { lang: "ru", confidence: 0.98, ambiguous: false };
    if (/[ऀ-ॿ]/u.test(value)) return { lang: "hi", confidence: 0.98, ambiguous: false };

    const vi = value.match(/[ăâđêôơưĂÂĐÊÔƠƯàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]/giu)?.length || 0;
    if (vi >= 1) return { lang: "vi", confidence: Math.min(0.98, 0.72 + vi * 0.04), ambiguous: false };

    const han = value.match(/[一-鿿]/gu)?.length || 0;
    const kana = value.match(/[ぁ-ゟ゠-ヿ]/gu)?.length || 0;
    if (han > 0 && kana === 0) {
      return { lang: "han", confidence: 0.5, ambiguous: true };
    }

    const latin = value.match(/[A-Za-zÀ-ÖØ-öø-ÿ]/g)?.length || 0;
    const letters = value.match(/[\p{L}\p{M}]/gu)?.length || 0;
    if (letters >= 2 && latin / Math.max(letters, 1) >= 0.72) {
      return { lang: "latin", confidence: 0.7, ambiguous: true };
    }

    return { lang: "", confidence: 0, ambiguous: true };
  }

  function isConfidentSameLanguage(text, targetLang, pageLang = "") {
    const target = normalizeLang(targetLang);
    const page = normalizeLang(pageLang);
    const result = detect(text);

    if (!target) return false;
    if (result.lang === "han") {
      if (target === "zh-hans" || target === "zh-hant") {
        return page.startsWith("zh") && result.confidence >= 0.45;
      }
      return false;
    }

    if (result.lang === "latin") {
      return Boolean(page && page === target && result.confidence >= 0.65);
    }

    return !result.ambiguous && normalizeLang(result.lang) === target && result.confidence >= 0.75;
  }

  function shouldTranslateText(text, { sourceLang = "auto", targetLang = "", pageLang = "" } = {}) {
    const trimmed = String(text || "").trim();
    if (!trimmed) return false;
    if (sourceLang && sourceLang !== "auto") return normalizeLang(sourceLang) !== normalizeLang(targetLang);
    return !isConfidentSameLanguage(trimmed, targetLang, pageLang);
  }

  globalThis.FTLanguage = Object.freeze({ normalizeLang, detect, isConfidentSameLanguage, shouldTranslateText });
})();
