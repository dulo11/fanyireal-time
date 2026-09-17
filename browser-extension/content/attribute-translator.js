(() => {
  if (window.__FLOATING_TRANSLATOR_ATTRIBUTES__) return;
  window.__FLOATING_TRANSLATOR_ATTRIBUTES__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    sourceLang: "auto",
    targetLang: "zh-CN",
    siteRules: {},
    skipTargetLanguage: true
  };

  const ATTRIBUTES = ["placeholder", "title", "aria-label", "alt"];
  const SELECTOR = ATTRIBUTES.map(attr => `[${attr}]`).concat([
    "input[type='button'][value]",
    "input[type='submit'][value]",
    "input[type='reset'][value]"
  ]).join(",");

  let settings = { ...DEFAULTS };
  let paused = false;
  let observer = null;
  let flushTimer = null;
  let processing = false;
  const queue = new Map();
  const records = new WeakMap();
  const retries = new WeakMap();

  const helper = () => globalThis.FTLanguage;

  function siteRule() {
    try { return settings.siteRules?.[location.hostname] || "default"; }
    catch { return "default"; }
  }

  function enabled() {
    if (!settings.enabled || paused) return false;
    const rule = siteRule();
    if (rule === "never") return false;
    return rule === "always" || Boolean(settings.autoTranslate);
  }

  function attributeNames(element) {
    const names = ATTRIBUTES.filter(attr => element.hasAttribute(attr));
    if (element instanceof HTMLInputElement && ["button", "submit", "reset"].includes(element.type) && element.hasAttribute("value")) {
      names.push("value");
    }
    return names;
  }

  function getRecordMap(element) {
    let map = records.get(element);
    if (!map) {
      map = new Map();
      records.set(element, map);
    }
    return map;
  }

  function retryMapFor(element) {
    let map = retries.get(element);
    if (!map) {
      map = new Map();
      retries.set(element, map);
    }
    return map;
  }

  function isEligible(element, attr) {
    if (!enabled() || !element?.isConnected || element.dataset?.ftOwned === "1") return false;
    const value = String(element.getAttribute(attr) || "").trim();
    if (value.length < 2 || !/[\p{L}\p{M}]/u.test(value)) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(value)) return false;

    const record = records.get(element)?.get(attr);
    if (record && (value === record.rendered || value === record.original)) return false;

    const api = helper();
    if (api && settings.skipTargetLanguage && settings.sourceLang === "auto") {
      const pageLang = document.documentElement?.lang || "";
      if (!api.shouldTranslateText(value, { sourceLang: "auto", targetLang: settings.targetLang, pageLang })) return false;
    }
    return true;
  }

  function queueKey(element, attr) {
    if (!element.dataset.ftAttrId) element.dataset.ftAttrId = crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
    return `${element.dataset.ftAttrId}:${attr}`;
  }

  function enqueue(element, attr) {
    if (!isEligible(element, attr)) return;
    queue.set(queueKey(element, attr), { element, attr, original: element.getAttribute(attr) || "" });
    scheduleFlush();
  }

  function scan(root = document) {
    if (!enabled() || !root) return;
    const inspect = element => {
      if (!(element instanceof Element)) return;
      for (const attr of attributeNames(element)) enqueue(element, attr);
    };
    if (root instanceof Element && root.matches?.(SELECTOR)) inspect(root);
    if (root.querySelectorAll) for (const element of root.querySelectorAll(SELECTOR)) inspect(element);
  }

  function scheduleFlush(delay = 120) {
    if (!enabled()) return;
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flush, delay);
  }

  function requeueFailed(item) {
    if (!item.element?.isConnected) return;
    const map = retryMapFor(item.element);
    const count = (map.get(item.attr) || 0) + 1;
    map.set(item.attr, count);
    if (count > 3) return;
    setTimeout(() => enqueue(item.element, item.attr), Math.min(5000, 550 * (2 ** (count - 1))));
  }

  async function flush() {
    if (processing || !enabled() || !queue.size) return;
    processing = true;
    const items = [...queue.values()].slice(0, 40);
    for (const item of items) queue.delete(queueKey(item.element, item.attr));

    try {
      const response = await chrome.runtime.sendMessage({
        type: "FT_TRANSLATE_DETAILED",
        texts: items.map(item => item.original),
        options: { sourceLang: settings.sourceLang, targetLang: settings.targetLang }
      });
      if (!response?.ok) throw new Error(response?.error || "属性文字翻译失败");
      if (paused) {
        items.forEach(item => queue.set(queueKey(item.element, item.attr), item));
        return;
      }

      items.forEach((item, index) => {
        if (!item.element?.isConnected) return;
        const current = item.element.getAttribute(item.attr) || "";
        if (current !== item.original) return;
        const translated = response.translations?.[index];
        if (typeof translated !== "string") return requeueFailed(item);
        const map = getRecordMap(item.element);
        map.set(item.attr, { original: item.original, rendered: translated });
        retryMapFor(item.element).delete(item.attr);
        item.element.setAttribute(item.attr, translated);
      });
    } catch (error) {
      items.forEach(requeueFailed);
      console.warn("[FloatingTranslator attributes]", error);
    } finally {
      processing = false;
      if (queue.size && enabled()) scheduleFlush(60);
    }
  }

  function restoreAll() {
    for (const element of document.querySelectorAll(`[data-ft-attr-id]`)) {
      const map = records.get(element);
      if (!map) continue;
      for (const [attr, record] of map) {
        if ((element.getAttribute(attr) || "") === record.rendered) element.setAttribute(attr, record.original);
      }
      records.delete(element);
      retries.delete(element);
      delete element.dataset.ftAttrId;
    }
    queue.clear();
  }

  function startObserver() {
    if (observer || !document.documentElement) return;
    observer = new MutationObserver(mutations => {
      if (!enabled()) return;
      for (const mutation of mutations) {
        if (mutation.type === "attributes") {
          const element = mutation.target;
          const attr = mutation.attributeName;
          const record = records.get(element)?.get(attr);
          const current = element.getAttribute(attr) || "";
          if (record && current === record.rendered) continue;
          if (record && current !== record.original) records.get(element)?.delete(attr);
          enqueue(element, attr);
          continue;
        }
        for (const added of mutation.addedNodes) if (added.nodeType === Node.ELEMENT_NODE) scan(added);
      }
    });
    observer.observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: [...ATTRIBUTES, "value"]
    });
  }

  async function loadSettings() {
    const previous = settings;
    settings = { ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) };
    const changed = ["enabled", "autoTranslate", "sourceLang", "targetLang", "skipTargetLanguage"]
      .some(key => previous[key] !== settings[key]) || JSON.stringify(previous.siteRules) !== JSON.stringify(settings.siteRules);
    if (changed) restoreAll();
    if (enabled()) scan(document);
  }

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type === "FT_SET_PAUSED") {
      paused = Boolean(message.paused);
      if (!paused) scan(document);
      return false;
    }
    if (message?.type === "FT_TRANSLATE_NOW") {
      paused = false;
      restoreAll();
      setTimeout(() => scan(document), 80);
      return false;
    }
    if (message?.type === "FT_RESCAN_PAGE") {
      if (!paused) scan(document);
      return false;
    }
    if (message?.type === "FT_RESTORE_PAGE") {
      paused = false;
      restoreAll();
      return false;
    }
    if (message?.type === "FT_REFRESH_SETTINGS") {
      loadSettings();
      return false;
    }
    return false;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  });

  async function init() {
    await loadSettings();
    startObserver();
    scan(document);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true });
  else init();
})();