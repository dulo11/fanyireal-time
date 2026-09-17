(() => {
  if (window.__FLOATING_TRANSLATOR_LOADED__) return;
  window.__FLOATING_TRANSLATOR_LOADED__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    sourceLang: "auto",
    targetLang: "zh-CN",
    displayMode: "translated",
    siteRules: {}
  };

  const BLOCK_TAGS = new Set([
    "P", "DIV", "LI", "ARTICLE", "SECTION", "HEADER", "FOOTER", "MAIN",
    "ASIDE", "TD", "TH", "H1", "H2", "H3", "H4", "H5", "H6", "BLOCKQUOTE"
  ]);

  const SKIP_SELECTOR = [
    "script", "style", "noscript", "code", "pre", "textarea", "input", "select", "option",
    "svg", "canvas", "math", "[contenteditable='true']", "[contenteditable='']",
    ".ft-translation-inline", ".ft-selection-bubble", "[data-ft-owned='1']"
  ].join(",");

  const state = {
    settings: { ...DEFAULTS },
    active: false,
    queue: new Set(),
    processing: false,
    flushTimer: null,
    nodeState: new WeakMap(),
    trackedNodes: new Set(),
    generation: 0,
    observer: null,
    started: false
  };

  function normalizeLang(lang) {
    if (!lang) return "";
    const value = String(lang).trim().toLowerCase().replace("_", "-");
    if (value.startsWith("zh")) {
      if (value.includes("tw") || value.includes("hk") || value.includes("hant")) return "zh-hant";
      return "zh-hans";
    }
    return value.split("-")[0];
  }

  function pageLanguage() {
    return normalizeLang(document.documentElement?.lang || document.body?.getAttribute("lang") || "");
  }

  function siteRule() {
    try {
      return state.settings.siteRules?.[location.hostname] || "default";
    } catch {
      return "default";
    }
  }

  function shouldTranslatePage() {
    if (!state.settings.enabled) return false;
    const rule = siteRule();
    if (rule === "never") return false;
    if (rule === "always") return true;
    if (!state.settings.autoTranslate) return false;

    if (state.settings.sourceLang !== "auto") return true;
    const current = pageLanguage();
    const target = normalizeLang(state.settings.targetLang);
    if (current && target && current === target) return false;
    return true;
  }

  function splitWhitespace(value) {
    const match = String(value ?? "").match(/^(\s*)([\s\S]*?)(\s*)$/);
    return {
      prefix: match?.[1] || "",
      core: match?.[2] || "",
      suffix: match?.[3] || ""
    };
  }

  function hasLetters(text) {
    if (!text || text.trim().length < 2) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(text.trim())) return false;
    if (/^[\d\s\p{P}\p{S}_]+$/u.test(text)) return false;
    return /[\p{L}\p{M}]/u.test(text);
  }

  function isEligibleTextNode(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !node.isConnected) return false;
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIP_SELECTOR)) return false;
    const { core } = splitWhitespace(node.nodeValue);
    if (!hasLetters(core)) return false;
    return true;
  }

  function collectTextNodes(root) {
    if (!root) return [];
    if (root.nodeType === Node.TEXT_NODE) return isEligibleTextNode(root) ? [root] : [];
    if (![Node.ELEMENT_NODE, Node.DOCUMENT_FRAGMENT_NODE, Node.DOCUMENT_NODE].includes(root.nodeType)) return [];

    if (root.nodeType === Node.ELEMENT_NODE && root.matches?.(SKIP_SELECTOR)) return [];

    const result = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        return isEligibleTextNode(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    while (walker.nextNode()) result.push(walker.currentNode);
    return result;
  }

  function nodePriority(node) {
    const parent = node.parentElement;
    if (!parent) return Number.MAX_SAFE_INTEGER;
    const rect = parent.getBoundingClientRect();
    if (rect.bottom >= -300 && rect.top <= innerHeight + 500) return 0;
    if (rect.top > innerHeight) return rect.top - innerHeight;
    return Math.abs(rect.bottom);
  }

  function enqueueNode(node) {
    if (!state.active || !isEligibleTextNode(node)) return;
    const previous = state.nodeState.get(node);
    if (previous) {
      const current = node.nodeValue;
      if (current === previous.renderedFull || current === previous.originalFull) return;
      previous.translationEl?.remove();
      state.nodeState.delete(node);
      state.trackedNodes.delete(node);
    }
    state.queue.add(node);
    scheduleFlush();
  }

  function scan(root = document.body) {
    if (!state.active || !root) return;
    for (const node of collectTextNodes(root)) enqueueNode(node);
  }

  function scheduleFlush(delay = 120) {
    clearTimeout(state.flushTimer);
    state.flushTimer = setTimeout(processQueue, delay);
  }

  async function processQueue() {
    if (state.processing || !state.active || state.queue.size === 0) return;
    state.processing = true;
    const generation = state.generation;

    try {
      const candidates = [...state.queue]
        .filter(isEligibleTextNode)
        .sort((a, b) => nodePriority(a) - nodePriority(b))
        .slice(0, 24);

      for (const node of candidates) state.queue.delete(node);
      if (!candidates.length) return;

      const payload = [];
      const nodes = [];
      for (const node of candidates) {
        const parts = splitWhitespace(node.nodeValue);
        if (!hasLetters(parts.core)) continue;
        payload.push(parts.core);
        nodes.push({ node, parts, originalFull: node.nodeValue });
      }
      if (!payload.length) return;

      const response = await chrome.runtime.sendMessage({
        type: "FT_TRANSLATE",
        texts: payload,
        options: {
          sourceLang: state.settings.sourceLang,
          targetLang: state.settings.targetLang
        }
      });

      if (generation !== state.generation || !state.active) return;
      if (!response?.ok) throw new Error(response?.error || "翻译失败");

      nodes.forEach((entry, index) => {
        const translated = response.translations?.[index];
        if (typeof translated !== "string" || !entry.node.isConnected) return;
        applyTranslation(entry.node, entry.parts, entry.originalFull, translated);
      });
    } catch (error) {
      console.warn("[FloatingTranslator]", error);
    } finally {
      state.processing = false;
      if (state.active && state.queue.size) scheduleFlush(80);
    }
  }

  function applyTranslation(node, parts, originalFull, translatedCore) {
    const renderedFull = `${parts.prefix}${translatedCore}${parts.suffix}`;
    const record = {
      originalFull,
      translatedCore,
      renderedFull,
      translationEl: null
    };

    if (state.settings.displayMode === "bilingual") {
      const span = document.createElement("span");
      span.className = "ft-translation-inline";
      span.dataset.ftOwned = "1";
      span.translate = false;
      span.textContent = translatedCore;
      if (BLOCK_TAGS.has(node.parentElement?.tagName)) span.dataset.ftBlock = "1";
      node.parentNode?.insertBefore(span, node.nextSibling);
      record.translationEl = span;
    } else if (state.settings.displayMode === "translated") {
      node.nodeValue = renderedFull;
    }

    state.nodeState.set(node, record);
    state.trackedNodes.add(node);
  }

  function restorePage() {
    state.generation++;
    state.queue.clear();
    for (const node of [...state.trackedNodes]) {
      const record = state.nodeState.get(node);
      if (!record) continue;
      record.translationEl?.remove();
      if (node.isConnected && node.nodeValue === record.renderedFull) {
        node.nodeValue = record.originalFull;
      }
      state.nodeState.delete(node);
    }
    state.trackedNodes.clear();
  }

  async function loadSettings({ rescan = true } = {}) {
    const next = await chrome.storage.sync.get(DEFAULTS);
    const materiallyChanged = ["sourceLang", "targetLang", "displayMode", "enabled", "autoTranslate"]
      .some(key => state.settings[key] !== next[key]) || JSON.stringify(state.settings.siteRules) !== JSON.stringify(next.siteRules);

    if (materiallyChanged) restorePage();
    state.settings = { ...DEFAULTS, ...next };
    state.active = shouldTranslatePage();

    if (rescan && state.active) {
      scan(document.body);
    }
  }

  function handleMutations(mutations) {
    if (!state.active) return;
    for (const mutation of mutations) {
      if (mutation.type === "characterData") {
        const node = mutation.target;
        const record = state.nodeState.get(node);
        if (record && (node.nodeValue === record.renderedFull || node.nodeValue === record.originalFull)) continue;
        enqueueNode(node);
        continue;
      }

      for (const added of mutation.addedNodes) {
        if (added.nodeType === Node.ELEMENT_NODE && added.dataset?.ftOwned === "1") continue;
        scan(added);
      }
    }
  }

  function startObserver() {
    if (state.observer || !document.documentElement) return;
    state.observer = new MutationObserver(handleMutations);
    state.observer.observe(document.documentElement, {
      subtree: true,
      childList: true,
      characterData: true
    });
  }

  function showSelectionBubble(original, translated, isError = false) {
    document.querySelectorAll(".ft-selection-bubble").forEach(el => el.remove());
    const bubble = document.createElement("div");
    bubble.className = `ft-selection-bubble${isError ? " ft-error" : ""}`;
    bubble.dataset.ftOwned = "1";
    bubble.innerHTML = "";

    const translatedEl = document.createElement("div");
    translatedEl.className = "ft-selection-result";
    translatedEl.textContent = translated;
    bubble.appendChild(translatedEl);

    const originalEl = document.createElement("div");
    originalEl.className = "ft-selection-original";
    originalEl.textContent = original;
    bubble.appendChild(originalEl);

    document.documentElement.appendChild(bubble);
    const selection = window.getSelection();
    let rect = null;
    try {
      if (selection?.rangeCount) rect = selection.getRangeAt(0).getBoundingClientRect();
    } catch {}

    const top = rect ? Math.min(innerHeight - 120, Math.max(10, rect.bottom + 8)) : 20;
    const left = rect ? Math.min(innerWidth - 320, Math.max(10, rect.left)) : 20;
    bubble.style.top = `${top}px`;
    bubble.style.left = `${left}px`;

    setTimeout(() => bubble.classList.add("ft-visible"), 0);
    setTimeout(() => bubble.remove(), 9000);
  }

  async function translateFocusedInput(event) {
    if (!(event.altKey && event.key === "Enter")) return;
    const el = event.target;
    const isTextInput = el instanceof HTMLTextAreaElement ||
      (el instanceof HTMLInputElement && ["text", "search", "email", "url", "tel"].includes(el.type));
    const simpleEditable = el?.isContentEditable && el.children.length === 0;
    if (!isTextInput && !simpleEditable) return;

    const text = isTextInput ? el.value : el.textContent;
    if (!text?.trim()) return;
    event.preventDefault();
    event.stopPropagation();

    try {
      const response = await chrome.runtime.sendMessage({
        type: "FT_TRANSLATE",
        texts: [text],
        options: {
          sourceLang: state.settings.sourceLang,
          targetLang: state.settings.targetLang
        }
      });
      if (!response?.ok) throw new Error(response?.error || "翻译失败");
      const translated = response.translations?.[0] || text;
      if (isTextInput) {
        const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value");
        descriptor?.set ? descriptor.set.call(el, translated) : (el.value = translated);
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
      } else {
        el.textContent = translated;
        el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: translated }));
      }
    } catch (error) {
      showSelectionBubble(text, `输入框翻译失败：${error?.message || error}`, true);
    }
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type === "FT_REFRESH_SETTINGS") {
      loadSettings().then(() => sendResponse({ ok: true, active: state.active }));
      return true;
    }
    if (message?.type === "FT_TRANSLATE_NOW") {
      state.active = true;
      restorePage();
      state.active = true;
      scan(document.body);
      sendResponse({ ok: true });
      return false;
    }
    if (message?.type === "FT_RESTORE_PAGE") {
      state.active = false;
      restorePage();
      sendResponse({ ok: true });
      return false;
    }
    if (message?.type === "FT_SHOW_SELECTION_TRANSLATION") {
      showSelectionBubble(message.original || "", message.translated || "", Boolean(message.error));
      return false;
    }
    if (message?.type === "FT_GET_PAGE_STATE") {
      sendResponse({
        ok: true,
        active: state.active,
        host: location.hostname,
        pageLang: pageLanguage(),
        queued: state.queue.size
      });
      return false;
    }
    return false;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "sync") return;
    if (Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  });

  document.addEventListener("keydown", translateFocusedInput, true);

  async function init() {
    if (state.started) return;
    state.started = true;
    startObserver();
    await loadSettings({ rescan: false });
    if (state.active) scan(document.body);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init, { once: true });
  } else {
    init();
  }
})();
