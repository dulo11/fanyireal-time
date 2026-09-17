(() => {
  if (globalThis.FTGlossary) return;

  const MAX_ENTRIES = 300;

  function normalizeEntries(raw) {
    const source = Array.isArray(raw) ? raw : [];
    const seen = new Set();
    const entries = [];
    for (const item of source) {
      const from = String(item?.source ?? item?.from ?? "").trim();
      const to = String(item?.target ?? item?.to ?? "").trim();
      if (!from || !to) continue;
      const key = from;
      if (seen.has(key)) continue;
      seen.add(key);
      entries.push({ source: from, target: to });
      if (entries.length >= MAX_ENTRIES) break;
    }
    return entries.sort((a, b) => b.source.length - a.source.length);
  }

  function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function planText(text, rawEntries, caseSensitive = false) {
    const value = String(text ?? "");
    const entries = normalizeEntries(rawEntries);
    if (!value || !entries.length) return [{ type: "translate", text: value }];

    const lookup = new Map();
    for (const entry of entries) {
      const key = caseSensitive ? entry.source : entry.source.toLocaleLowerCase();
      if (!lookup.has(key)) lookup.set(key, entry);
    }

    let regex;
    try {
      regex = new RegExp(entries.map(entry => escapeRegex(entry.source)).join("|"), caseSensitive ? "gu" : "giu");
    } catch {
      return [{ type: "translate", text: value }];
    }

    const plan = [];
    let cursor = 0;
    let match;
    while ((match = regex.exec(value))) {
      const index = match.index;
      if (index > cursor) plan.push({ type: "translate", text: value.slice(cursor, index) });
      const matched = match[0];
      const key = caseSensitive ? matched : matched.toLocaleLowerCase();
      const entry = lookup.get(key);
      if (entry) plan.push({ type: "fixed", text: entry.target, source: matched });
      else plan.push({ type: "translate", text: matched });
      cursor = index + matched.length;
      if (matched.length === 0) regex.lastIndex += 1;
    }
    if (cursor < value.length) plan.push({ type: "translate", text: value.slice(cursor) });
    return plan.length ? plan : [{ type: "translate", text: value }];
  }

  function hasFixedTerms(plan) {
    return Array.isArray(plan) && plan.some(segment => segment?.type === "fixed");
  }

  function renderPlan(plan, translatedSegments = []) {
    let cursor = 0;
    return (Array.isArray(plan) ? plan : []).map(segment => {
      if (segment?.type === "fixed" || segment?.type === "literal") return String(segment.text ?? "");
      const value = translatedSegments[cursor++];
      return value == null ? String(segment?.text ?? "") : String(value);
    }).join("");
  }

  globalThis.FTGlossary = Object.freeze({ normalizeEntries, planText, hasFixedTerms, renderPlan });
})();