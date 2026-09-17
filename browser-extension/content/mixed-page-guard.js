(() => {
  if (window.__FLOATING_TRANSLATOR_MIXED_GUARD__) return;
  window.__FLOATING_TRANSLATOR_MIXED_GUARD__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    sourceLang: "auto",
    targetLang: "zh-CN",
    skipTargetLanguage: true,
    siteRules: {}
  };

  const SKIP_SELECTOR = [
    "script", "style", "noscript", "code", "pre", "textarea", "input", "select", "option",
    "svg", "canvas", "math", "[contenteditable='true']", "[contenteditable='']",
    ".ft-translation-inline", ".ft-selection-bubble", ".ft-input-preview", "[data-ft-owned='1']"
  ].join(",");

  let settings = { ...DEFAULTS };
  let lastForcedUrl = "";
  let timer = null;

  const helper = () => globalThis.FTLanguage;

  function siteRule() {
    try { return settings.siteRules?.[location.hostname] || "default"; }
    catch { return "default"; }
  }

  function eligibleToProbe() {
    const api = helper();
    if (!api || !settings.enabled || !settings.autoTranslate) return false;
    const rule = siteRule();
    if (rule === "never" || rule === "always") return false;
    if (settings.sourceLang !== "auto" || settings.skipTargetLanguage === false) return false;

    const page = api.normalizeLang(document.documentElement?.lang || "");
    const target = api.normalizeLang(settings.targetLang);
    if (!page || !target || page !== target) return false;

    // 拉丁语系之间无法仅靠字符脚本可靠地区分，所以这里不自动强制整页。
    // 非拉丁目标（中文/日文/韩文/泰文/阿拉伯文等）可以可靠发现明显的外语片段。
    if (api.isLatinScriptLanguage?.(target)) return false;
    return true;
  }

  function hasForeignTextSample() {
    const api = helper();
    if (!api || !document.body) return false;
    const target = settings.targetLang;
    const page = document.documentElement?.lang || "";
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);

    let checked = 0;
    let foreignNodes = 0;
    let foreignChars = 0;

    while (walker.nextNode() && checked < 220) {
      const node = walker.currentNode;
      const parent = node.parentElement;
      if (!parent || parent.closest(SKIP_SELECTOR)) continue;
      const text = String(node.nodeValue || "").trim();
      if (text.length < 2 || !/[\p{L}\p{M}]/u.test(text)) continue;
      if (/^(https?:\/\/|www\.)\S+$/i.test(text)) continue;
      checked++;

      if (!api.shouldTranslateText(text, { sourceLang: "auto", targetLang: target, pageLang: page })) continue;
      const detected = api.detect(text);
      const clearlyDifferentScript = !detected.ambiguous || detected.lang === "latin";
      if (!clearlyDifferentScript) continue;

      foreignNodes++;
      foreignChars += text.length;
      if (foreignNodes >= 2 || foreignChars >= 12) return true;
    }

    return false;
  }

  async function probeAndForce() {
    clearTimeout(timer);
    timer = null;
    if (!eligibleToProbe()) return;
    if (lastForcedUrl === location.href) return;
    if (!hasForeignTextSample()) return;

    lastForcedUrl = location.href;
    try {
      const response = await chrome.runtime.sendMessage({
        type: "FT_FORCE_PAGE_TRANSLATION",
        reason: "mixed-language-page"
      });
      if (!response?.ok) lastForcedUrl = "";
    } catch {
      lastForcedUrl = "";
    }
  }

  function scheduleProbe(delay = 450) {
    clearTimeout(timer);
    timer = setTimeout(probeAndForce, delay);
  }

  async function loadSettings() {
    settings = { ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) };
    lastForcedUrl = "";
    scheduleProbe(250);
  }

  window.addEventListener("ft-route-change", () => {
    lastForcedUrl = "";
    scheduleProbe(350);
  }, true);

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") scheduleProbe(400);
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", loadSettings, { once: true });
  } else {
    loadSettings();
  }
})();