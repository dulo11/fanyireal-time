(() => {
  if (window.__FLOATING_TRANSLATOR_V02__) return;
  window.__FLOATING_TRANSLATOR_V02__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    sourceLang: "auto",
    targetLang: "zh-CN",
    displayMode: "translated",
    siteRules: {},
    chatMode: true,
    inputPreview: true,
    inputSourceLang: "auto",
    inputTargetLang: "en",
    inputPreviewDelay: 550
  };

  const SKIP = [
    "script", "style", "noscript", "code", "pre", "textarea", "input", "select", "option",
    "svg", "canvas", "math", "[contenteditable='true']", "[contenteditable='']",
    ".ft-translation-inline", ".ft-selection-bubble", ".ft-input-preview", "[data-ft-owned='1']"
  ].join(",");

  const state = {
    settings: { ...DEFAULTS },
    shadowObservers: new Map(),
    shadowRecords: new WeakMap(),
    trackedShadowNodes: new Set(),
    docObserver: null,
    inputTimer: null,
    inputSeq: 0,
    previewEl: null,
    previewAnchor: null,
    lastUrl: location.href,
    urlTimer: null
  };

  const normalizeLang = value => {
    const lang = String(value || "").toLowerCase().replace("_", "-");
    if (lang.startsWith("zh")) return lang.includes("tw") || lang.includes("hk") || lang.includes("hant") ? "zh-hant" : "zh-hans";
    return lang.split("-")[0];
  };

  function detectStrongLanguage(text) {
    const value = String(text || "").trim();
    if (!value) return "";
    if (/[ぁ-ゟ゠-ヿ]/u.test(value)) return "ja";
    if (/[가-힣]/u.test(value)) return "ko";
    if (/[฀-๿]/u.test(value)) return "th";
    if (/[؀-ۿ]/u.test(value)) return "ar";
    if (/[Ѐ-ӿ]/u.test(value)) return "ru";
    if (/[一-鿿]/u.test(value)) return "zh-hans";
    if (/[ăâđêôơưĂÂĐÊÔƠƯàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]/iu.test(value)) return "vi";
    return "";
  }

  function currentSiteRule() {
    return state.settings.siteRules?.[location.hostname] || "default";
  }

  function translationEnabled() {
    if (!state.settings.enabled) return false;
    const rule = currentSiteRule();
    if (rule === "never") return false;
    if (rule === "always") return true;
    return Boolean(state.settings.autoTranslate);
  }

  function shouldSkipBecauseAlreadyTarget(text, targetLang) {
    const detected = detectStrongLanguage(text);
    if (!detected) return false;
    return detected === normalizeLang(targetLang);
  }

  async function translateOne(text, sourceLang, targetLang) {
    const response = await chrome.runtime.sendMessage({
      type: "FT_TRANSLATE",
      texts: [text],
      options: { sourceLang, targetLang }
    });
    if (!response?.ok) throw new Error(response?.error || "翻译失败");
    return response.translations?.[0] ?? text;
  }

  function isEditable(element) {
    if (!element) return false;
    if (element instanceof HTMLTextAreaElement) return !element.disabled && !element.readOnly;
    if (element instanceof HTMLInputElement) {
      return ["text", "search", "email", "url", "tel"].includes(element.type) && !element.disabled && !element.readOnly;
    }
    return Boolean(element.isContentEditable);
  }

  function resolveEditable(target) {
    if (isEditable(target)) return target;
    if (target instanceof Element) {
      const editable = target.closest("[contenteditable='true'],[contenteditable='']");
      if (isEditable(editable)) return editable;
    }
    return null;
  }

  function readEditable(element) {
    if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) return element.value;
    return element.textContent || "";
  }

  function writeEditable(element, value) {
    if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
      const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor?.set) descriptor.set.call(element, value);
      else element.value = value;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return;
    }
    element.textContent = value;
    element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
  }

  function removePreview() {
    state.previewEl?.remove();
    state.previewEl = null;
    state.previewAnchor = null;
  }

  function positionPreview() {
    const panel = state.previewEl;
    const anchor = state.previewAnchor;
    if (!panel || !anchor?.isConnected) return removePreview();
    const rect = anchor.getBoundingClientRect();
    const width = Math.min(420, Math.max(260, rect.width || 320), innerWidth - 16);
    let top = rect.bottom + 8;
    if (top + 170 > innerHeight) top = Math.max(8, rect.top - 178);
    const left = Math.min(innerWidth - width - 8, Math.max(8, rect.left));
    panel.style.width = `${width}px`;
    panel.style.top = `${top}px`;
    panel.style.left = `${left}px`;
  }

  function showInputPreview(anchor, original, translated, isError = false) {
    removePreview();
    const panel = document.createElement("div");
    panel.className = `ft-input-preview${isError ? " ft-error" : ""}`;
    panel.dataset.ftOwned = "1";

    const result = document.createElement("div");
    result.className = "ft-input-preview-result";
    result.textContent = translated;
    panel.appendChild(result);

    if (!isError) {
      const actions = document.createElement("div");
      actions.className = "ft-input-preview-actions";

      const replace = document.createElement("button");
      replace.type = "button";
      replace.textContent = "替换输入框";
      replace.addEventListener("click", () => {
        writeEditable(anchor, translated);
        removePreview();
        anchor.focus();
      });

      const copy = document.createElement("button");
      copy.type = "button";
      copy.textContent = "复制译文";
      copy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(translated);
          copy.textContent = "已复制";
        } catch {
          copy.textContent = "复制失败";
        }
      });

      const close = document.createElement("button");
      close.type = "button";
      close.textContent = "关闭";
      close.addEventListener("click", removePreview);

      actions.append(replace, copy, close);
      panel.appendChild(actions);
    }

    const meta = document.createElement("div");
    meta.className = "ft-input-preview-meta";
    meta.textContent = `原文：${original}`;
    panel.appendChild(meta);

    document.documentElement.appendChild(panel);
    state.previewEl = panel;
    state.previewAnchor = anchor;
    positionPreview();
  }

  function scheduleInputPreview(element) {
    clearTimeout(state.inputTimer);
    if (!state.settings.enabled || !state.settings.chatMode || !state.settings.inputPreview) return removePreview();
    const text = readEditable(element).trim();
    if (text.length < 2) return removePreview();
    if (shouldSkipBecauseAlreadyTarget(text, state.settings.inputTargetLang)) return removePreview();

    const seq = ++state.inputSeq;
    state.inputTimer = setTimeout(async () => {
      try {
        const current = readEditable(element).trim();
        if (!current || current !== text || seq !== state.inputSeq) return;
        const translated = await translateOne(
          current,
          state.settings.inputSourceLang || "auto",
          state.settings.inputTargetLang || "en"
        );
        if (seq !== state.inputSeq || !element.isConnected) return;
        showInputPreview(element, current, translated);
      } catch (error) {
        if (seq !== state.inputSeq) return;
        showInputPreview(element, text, `实时输入翻译失败：${error?.message || error}`, true);
      }
    }, Math.max(250, Number(state.settings.inputPreviewDelay) || 550));
  }

  function onInput(event) {
    const editable = resolveEditable(event.target);
    if (!editable) return;
    scheduleInputPreview(editable);
  }

  function shadowEligible(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !node.isConnected) return false;
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIP)) return false;
    const text = node.nodeValue?.trim();
    if (!text || text.length < 2) return false;
    if (!/[\p{L}\p{M}]/u.test(text)) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(text)) return false;
    if (shouldSkipBecauseAlreadyTarget(text, state.settings.targetLang)) return false;
    const record = state.shadowRecords.get(node);
    if (record && (node.nodeValue === record.rendered || node.nodeValue === record.original)) return false;
    return true;
  }

  function collectShadowText(root) {
    const nodes = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        return shadowEligible(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    while (walker.nextNode() && nodes.length < 40) nodes.push(walker.currentNode);
    return nodes;
  }

  async function translateShadowRoot(root) {
    if (!translationEnabled()) return;
    const nodes = collectShadowText(root);
    if (!nodes.length) return;
    const texts = nodes.map(node => node.nodeValue.trim());
    try {
      const response = await chrome.runtime.sendMessage({
        type: "FT_TRANSLATE",
        texts,
        options: {
          sourceLang: state.settings.sourceLang,
          targetLang: state.settings.targetLang
        }
      });
      if (!response?.ok) throw new Error(response?.error || "Shadow DOM 翻译失败");
      nodes.forEach((node, index) => {
        if (!node.isConnected) return;
        const translated = response.translations?.[index];
        if (typeof translated !== "string") return;
        const original = node.nodeValue;
        const prefix = original.match(/^\s*/)?.[0] || "";
        const suffix = original.match(/\s*$/)?.[0] || "";
        const rendered = `${prefix}${translated}${suffix}`;
        const record = { original, rendered, translationEl: null };
        if (state.settings.displayMode === "bilingual") {
          const span = document.createElement("span");
          span.dataset.ftOwned = "1";
          span.className = "ft-translation-inline";
          span.textContent = translated;
          node.parentNode?.insertBefore(span, node.nextSibling);
          record.translationEl = span;
        } else {
          node.nodeValue = rendered;
        }
        state.shadowRecords.set(node, record);
        state.trackedShadowNodes.add(node);
      });
    } catch (error) {
      console.warn("[FloatingTranslator v0.2 shadow]", error);
    }
  }

  function restoreShadowTranslations() {
    for (const node of [...state.trackedShadowNodes]) {
      const record = state.shadowRecords.get(node);
      if (!record) continue;
      record.translationEl?.remove();
      if (node.isConnected && node.nodeValue === record.rendered) node.nodeValue = record.original;
      state.shadowRecords.delete(node);
    }
    state.trackedShadowNodes.clear();
  }

  function observeShadowRoot(root) {
    if (!root || state.shadowObservers.has(root)) return;
    const observer = new MutationObserver(mutations => {
      for (const mutation of mutations) {
        if (mutation.type === "characterData") {
          translateShadowRoot(root);
          break;
        }
        if ([...mutation.addedNodes].some(node => node.nodeType === Node.ELEMENT_NODE || node.nodeType === Node.TEXT_NODE)) {
          discoverShadowRoots(root);
          translateShadowRoot(root);
          break;
        }
      }
    });
    observer.observe(root, { subtree: true, childList: true, characterData: true });
    state.shadowObservers.set(root, observer);
    translateShadowRoot(root);
  }

  function discoverShadowRoots(root = document) {
    const inspect = element => {
      if (element?.shadowRoot) observeShadowRoot(element.shadowRoot);
    };
    if (root instanceof Element) inspect(root);
    if (root.querySelectorAll) {
      for (const element of root.querySelectorAll("*")) inspect(element);
    }
  }

  function startDocumentObserver() {
    if (state.docObserver || !document.documentElement) return;
    state.docObserver = new MutationObserver(mutations => {
      for (const mutation of mutations) {
        for (const added of mutation.addedNodes) {
          if (added.nodeType === Node.ELEMENT_NODE) discoverShadowRoots(added);
        }
      }
    });
    state.docObserver.observe(document.documentElement, { subtree: true, childList: true });
  }

  async function loadSettings() {
    const previous = state.settings;
    state.settings = { ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) };
    const shadowChanged = previous.targetLang !== state.settings.targetLang ||
      previous.sourceLang !== state.settings.sourceLang ||
      previous.displayMode !== state.settings.displayMode ||
      previous.enabled !== state.settings.enabled ||
      previous.autoTranslate !== state.settings.autoTranslate ||
      JSON.stringify(previous.siteRules) !== JSON.stringify(state.settings.siteRules);
    if (shadowChanged) {
      restoreShadowTranslations();
      if (translationEnabled()) for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
    }
    if (!state.settings.inputPreview || !state.settings.chatMode || !state.settings.enabled) removePreview();
  }

  function onStorageChanged(changes, area) {
    if (area !== "sync") return;
    if (Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  }

  function startUrlWatch() {
    if (state.urlTimer) return;
    state.urlTimer = setInterval(() => {
      if (location.href === state.lastUrl) return;
      state.lastUrl = location.href;
      removePreview();
      setTimeout(() => {
        discoverShadowRoots(document);
        for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
      }, 300);
    }, 800);
  }

  document.addEventListener("input", onInput, true);
  document.addEventListener("focusin", event => {
    const editable = resolveEditable(event.target);
    if (editable) scheduleInputPreview(editable);
  }, true);
  document.addEventListener("focusout", event => {
    const next = event.relatedTarget;
    if (state.previewEl?.contains(next)) return;
    setTimeout(() => {
      if (!state.previewEl?.matches(":hover")) removePreview();
    }, 160);
  }, true);
  window.addEventListener("resize", positionPreview, { passive: true });
  window.addEventListener("scroll", positionPreview, { passive: true, capture: true });
  chrome.storage.onChanged.addListener(onStorageChanged);

  async function init() {
    await loadSettings();
    startDocumentObserver();
    discoverShadowRoots(document);
    startUrlWatch();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true });
  else init();
})();
