(() => {
  if (globalThis.__FT_GLOSSARY_RUNTIME__) return;
  globalThis.__FT_GLOSSARY_RUNTIME__ = true;

  const baseTranslateBatch = globalThis.translateBatch;
  const glossary = globalThis.FTGlossary;
  if (typeof baseTranslateBatch !== "function" || !glossary) return;

  const GLOSSARY_DEFAULTS = {
    glossaryEnabled: true,
    glossaryCaseSensitive: false,
    glossaryEntries: []
  };

  function hasLetters(text) {
    return /[\p{L}\p{M}]/u.test(String(text || ""));
  }

  async function getGlossaryConfig() {
    const local = await chrome.storage.local.get(GLOSSARY_DEFAULTS);
    return {
      enabled: local.glossaryEnabled !== false,
      caseSensitive: Boolean(local.glossaryCaseSensitive),
      entries: glossary.normalizeEntries(local.glossaryEntries)
    };
  }

  async function wrappedTranslateBatch(texts, options = {}) {
    const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
    if (!clean.length) return [];

    const config = await getGlossaryConfig();
    if (!config.enabled || !config.entries.length) return baseTranslateBatch(clean, options);

    const plans = clean.map(text => glossary.planText(text, config.entries, config.caseSensitive));
    if (!plans.some(glossary.hasFixedTerms)) return baseTranslateBatch(clean, options);

    const work = [];
    const workIndex = new Map();
    plans.forEach((plan, textIndex) => {
      plan.forEach((segment, segmentIndex) => {
        if (segment.type !== "translate") return;
        if (!segment.text || !hasLetters(segment.text)) {
          segment.type = "literal";
          return;
        }
        workIndex.set(`${textIndex}:${segmentIndex}`, work.length);
        work.push(segment.text);
      });
    });

    const translated = work.length ? await baseTranslateBatch(work, options) : [];
    return plans.map((plan, textIndex) => {
      const translatedSegments = [];
      plan.forEach((segment, segmentIndex) => {
        if (segment.type !== "translate") return;
        const index = workIndex.get(`${textIndex}:${segmentIndex}`);
        translatedSegments.push(index == null ? segment.text : (translated[index] ?? segment.text));
      });
      return glossary.renderPlan(plan, translatedSegments);
    });
  }

  // service-worker.js 通过经典 worker 脚本加载，顶层函数绑定与 globalThis 属性相连。
  // 在这里替换后，正文、输入框、属性、Shadow DOM 和右键翻译都会统一走术语表。
  globalThis.translateBatch = wrappedTranslateBatch;
})();