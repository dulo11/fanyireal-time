(() => {
  if (globalThis.__FT_GLOSSARY_RUNTIME__) return;
  globalThis.__FT_GLOSSARY_RUNTIME__ = true;

  const baseTranslateBatch = globalThis.translateBatch;
  if (typeof baseTranslateBatch !== "function" || !globalThis.FTGlossary) return;

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
      entries: FTGlossary.normalizeEntries(local.glossaryEntries)
    };
  }

  async function wrappedTranslateBatch(texts, options = {}) {
    const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
    const config = await getGlossaryConfig();
    if (!config.enabled || !config.entries.length || !clean.length) {
      return baseTranslateBatch(clean, options);
    }

    const plans = clean.map(text => FTGlossary.planText(text, config.entries, config.caseSensitive));
    if (!plans.some(FTGlossary.hasFixedTerms)) return baseTranslateBatch(clean, options);

    const work = [];
    const refs = [];
    plans.forEach((plan, textIndex) => {
      plan.forEach((segment, segmentIndex) => {
        if (segment.type !== "translate") return;
        if (!segment.text || !hasLetters(segment.text)) {
          segment.type = "literal";
          return;
        }
        refs.push({ textIndex, segmentIndex });
        work.push(segment.text);
      });
    });

    const translated = work.length ? await baseTranslateBatch(work, options) : [];
    const output = clean.map((_, textIndex) => {
      const plan = plans[textIndex];
      const translatedSegments = [];
      for (const segment of plan) {
        if (segment.type !== "translate") continue;
        const refIndex = refs.findIndex(ref => ref.textIndex === textIndex && ref.segmentIndex === plan.indexOf(segment));
        translatedSegments.push(refIndex >= 0 ? translated[refIndex] : segment.text);
      }
      return FTGlossary.renderPlan(plan, translatedSegments);
    });
    return output;
  }

  globalThis.translateBatch = wrappedTranslateBatch;
})();