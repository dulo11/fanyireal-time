(() => {
  if (window.__FLOATING_TRANSLATOR_V07__) return;
  window.__FLOATING_TRANSLATOR_V07__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    sourceLang: "auto",
    targetLang: "zh-CN",
    displayMode: "translated",
    siteRules: {},
    skipTargetLanguage: true,
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

  const CHAT_ADAPTERS = [
    {
      id: "whatsapp",
      hosts: ["web.whatsapp.com"],
      composers: ["[contenteditable='true'][role='textbox']", "footer [contenteditable='true']"],
      feeds: ["[role='application']", "[data-testid='conversation-panel-messages']"]
    },
    {
      id: "telegram",
      hosts: ["web.telegram.org"],
      composers: [".input-message-input[contenteditable='true']", "[contenteditable='true'][role='textbox']"],
      feeds: [".MessageList", ".messages-container", "[class*='message-list']"]
    },
    {
      id: "discord",
      hosts: ["discord.com", "www.discord.com"],
      composers: ["[role='textbox'][contenteditable='true']", "[data-slate-editor='true']"],
      feeds: ["[data-list-id='chat-messages']", "[role='log']"]
    }
  ];

  const state = {
    settings: { ...DEFAULTS },
    pagePaused: false,
    shadowObservers: new Map(),
    shadowRecords: new WeakMap(),
    trackedShadowNodes: new Set(),
    shadowBusy: new WeakSet(),
    docObserver: null,
    inputStates: new WeakMap(),
    mutedInputs: new WeakSet(),
    activeEditable: null,
    previewEl: null,
    previewAnchor: null,
    previewInteracting: false,
    lastUrl: location.href,
    routeTimer: null,
    adapter: null,
    shadowProtectedRestores: 0
  };

  const helper = () => globalThis.FTLanguage;
  const exclusions = () => globalThis.FTSiteExclusions;
  const normalizeLang = value => helper()?.normalizeLang(value) || String(value || "").toLowerCase();
  const pageLang = () => normalizeLang(document.documentElement?.lang || "");

  function isExcluded(element) {
    try { return Boolean(exclusions()?.isExcluded?.(element)); }
    catch { return false; }
  }

  function currentSiteRule() {
    return state.settings.siteRules?.[location.hostname] || "default";
  }

  function translationEnabled() {
    if (!state.settings.enabled || state.pagePaused) return false;
    const rule = currentSiteRule();
    if (rule === "never") return false;
    if (rule === "always") return true;
    return Boolean(state.settings.autoTranslate);
  }

  function shouldSkipBecauseAlreadyTarget(text, targetLang, sourceLang = "auto") {
    if (!state.settings.skipTargetLanguage || sourceLang !== "auto") return false;
    const api = helper();
    if (!api) return false;
    return !api.shouldTranslateText(text, { sourceLang, targetLang, pageLang: pageLang() });
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
    return element.innerText || element.textContent || "";
  }

  function dispatchEditableEvents(element, value) {
    element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
    element.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function writeEditable(element, value) {
    if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
      const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor?.set) descriptor.set.call(element, value); else element.value = value;
      dispatchEditableEvents(element, value);
      return;
    }

    element.focus();
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(element);
    selection?.removeAllRanges();
    selection?.addRange(range);
    let inserted = false;
    try { inserted = document.execCommand?.("insertText", false, value) === true; } catch {}
    if (!inserted) element.textContent = value;
    selection?.removeAllRanges();
    dispatchEditableEvents(element, value);
  }

  async function copyText(value) {
    try {
      await navigator.clipboard.writeText(value);
      return true;
    } catch {
      const textarea = document.createElement("textarea");
      textarea.value = value;
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      textarea.dataset.ftOwned = "1";
      document.documentElement.appendChild(textarea);
      textarea.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch {}
      textarea.remove();
      return ok;
    }
  }

  function removePreview() {
    state.previewEl?.remove();
    state.previewEl = null;
    state.previewAnchor = null;
    state.previewInteracting = false;
  }

  function positionPreview() {
    const panel = state.previewEl;
    const anchor = state.previewAnchor;
    if (!panel || !anchor?.isConnected) return removePreview();
    const rect = anchor.getBoundingClientRect();
    const width = Math.min(440, Math.max(270, rect.width || 320), innerWidth - 16);
    let top = rect.bottom + 8;
    if (top + 210 > innerHeight) top = Math.max(8, rect.top - 218);
    const left = Math.min(innerWidth - width - 8, Math.max(8, rect.left));
    panel.style.width = `${width}px`;
    panel.style.top = `${top}px`;
    panel.style.left = `${left}px`;
  }

  async function swapInputLanguages(anchor) {
    const source = state.settings.inputSourceLang || "auto";
    const target = state.settings.inputTargetLang || "en";
    if (source === "auto") return false;
    const patch = { inputSourceLang: target, inputTargetLang: source };
    state.settings = { ...state.settings, ...patch };
    await chrome.storage.sync.set(patch);
    removePreview();
    if (anchor?.isConnected) {
      anchor.focus();
      scheduleInputPreview(anchor);
    }
    return true;
  }

  function showInputPreview(anchor, original, translated, isError = false) {
    if (state.activeEditable !== anchor) return;
    removePreview();
    const panel = document.createElement("div");
    panel.className = `ft-input-preview${isError ? " ft-error" : ""}`;
    panel.dataset.ftOwned = "1";
    panel.addEventListener("pointerdown", () => { state.previewInteracting = true; }, true);
    panel.addEventListener("pointerup", () => setTimeout(() => { state.previewInteracting = false; }, 120), true);

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
      copy.textContent = "仅复制译文";
      copy.addEventListener("click", async () => {
        copy.textContent = await copyText(translated) ? "已复制" : "复制失败";
      });

      const swap = document.createElement("button");
      swap.type = "button";
      swap.textContent = state.settings.inputSourceLang === "auto" ? "交换需指定源语言" : "交换语言";
      swap.disabled = state.settings.inputSourceLang === "auto";
      swap.addEventListener("click", async () => {
        const ok = await swapInputLanguages(anchor);
        if (!ok) swap.textContent = "请先指定源语言";
      });

      const mute = document.createElement("button");
      mute.type = "button";
      mute.textContent = "本次输入暂停预览";
      mute.addEventListener("click", () => {
        state.mutedInputs.add(anchor);
        cancelInputPreview(anchor);
        removePreview();
        anchor.focus();
      });

      const close = document.createElement("button");
      close.type = "button";
      close.textContent = "关闭";
      close.addEventListener("click", removePreview);
      actions.append(replace, copy, swap, mute, close);
      panel.appendChild(actions);
    }

    const meta = document.createElement("div");
    meta.className = "ft-input-preview-meta";
    meta.textContent = `${state.settings.inputSourceLang || "auto"} → ${state.settings.inputTargetLang || "en"} · 原文：${original}`;
    panel.appendChild(meta);

    document.documentElement.appendChild(panel);
    state.previewEl = panel;
    state.previewAnchor = anchor;
    positionPreview();
  }

  function inputStateFor(element) {
    let record = state.inputStates.get(element);
    if (!record) {
      record = { timer: null, seq: 0, lastText: "" };
      state.inputStates.set(element, record);
    }
    return record;
  }

  function cancelInputPreview(element) {
    const record = state.inputStates.get(element);
    if (!record) return;
    clearTimeout(record.timer);
    record.timer = null;
    record.seq++;
  }

  function scheduleInputPreview(element) {
    const record = inputStateFor(element);
    clearTimeout(record.timer);
    record.seq++;
    const seq = record.seq;
    state.activeEditable = element;

    if (state.mutedInputs.has(element) || isExcluded(element)) return removePreview();
    if (!state.settings.enabled || !state.settings.chatMode || !state.settings.inputPreview) return removePreview();
    const text = readEditable(element).trim();
    record.lastText = text;
    if (text.length < 2) return removePreview();
    if (shouldSkipBecauseAlreadyTarget(text, state.settings.inputTargetLang, state.settings.inputSourceLang)) return removePreview();

    record.timer = setTimeout(async () => {
      try {
        const current = readEditable(element).trim();
        if (!current || current !== text || seq !== record.seq || state.activeEditable !== element || state.mutedInputs.has(element)) return;
        const translated = await translateOne(current, state.settings.inputSourceLang || "auto", state.settings.inputTargetLang || "en");
        if (seq !== record.seq || !element.isConnected || state.activeEditable !== element || readEditable(element).trim() !== current || state.mutedInputs.has(element)) return;
        if (translated.trim() === current.trim()) return removePreview();
        showInputPreview(element, current, translated);
      } catch (error) {
        if (seq !== record.seq || state.activeEditable !== element || state.mutedInputs.has(element)) return;
        showInputPreview(element, text, `实时输入翻译失败：${error?.message || error}`, true);
      }
    }, Math.max(250, Number(state.settings.inputPreviewDelay) || 550));
  }

  function findAdapter() {
    const host = location.hostname.toLowerCase();
    return CHAT_ADAPTERS.find(adapter => adapter.hosts.some(item => host === item || host.endsWith(`.${item}`))) || null;
  }

  function markAdapterElements(root = document) {
    state.adapter = findAdapter();
    const adapter = state.adapter;
    if (!adapter || !root?.querySelectorAll) return;

    for (const selector of adapter.composers) {
      let elements = [];
      try { elements = root.querySelectorAll(selector); } catch {}
      for (const element of elements) element.dataset.ftChatComposer = "1";
    }
    for (const selector of adapter.feeds) {
      let elements = [];
      try { elements = root.querySelectorAll(selector); } catch {}
      for (const element of elements) element.dataset.ftChatFeed = "1";
    }
  }

  function createShadowSpan(node, translated) {
    const span = document.createElement("span");
    span.dataset.ftOwned = "1";
    span.className = "ft-translation-inline";
    span.textContent = translated;
    return span;
  }

  function restoreShadowRecord(node, record) {
    if (!node?.isConnected || !record || !translationEnabled() || isExcluded(node.parentElement)) return false;
    if (node.nodeValue !== record.original) return false;
    if (state.settings.displayMode === "bilingual") {
      if (!record.translationEl?.isConnected) {
        record.translationEl ||= createShadowSpan(node, record.translated || "");
        node.parentNode?.insertBefore(record.translationEl, node.nextSibling);
        state.shadowProtectedRestores++;
        return true;
      }
      return false;
    }
    node.nodeValue = record.rendered;
    state.shadowProtectedRestores++;
    return true;
  }

  function shadowEligible(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !node.isConnected) return false;
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIP) || isExcluded(parent)) return false;
    const text = node.nodeValue?.trim();
    if (!text || text.length < 2 || !/[\p{L}\p{M}]/u.test(text)) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(text)) return false;
    if (shouldSkipBecauseAlreadyTarget(text, state.settings.targetLang, state.settings.sourceLang)) return false;
    const record = state.shadowRecords.get(node);
    if (record) {
      if (node.nodeValue === record.rendered) return false;
      if (node.nodeValue === record.original) {
        restoreShadowRecord(node, record);
        return false;
      }
    }
    return true;
  }

  function collectShadowText(root, max = 80) {
    const nodes = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(node) { return shadowEligible(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT; }
    });
    while (walker.nextNode() && nodes.length < max) nodes.push(walker.currentNode);
    return nodes;
  }

  async function translateShadowRoot(root) {
    if (!translationEnabled() || state.shadowBusy.has(root)) return;
    const nodes = collectShadowText(root);
    if (!nodes.length) return;
    state.shadowBusy.add(root);
    try {
      const texts = nodes.map(node => node.nodeValue.trim());
      const response = await chrome.runtime.sendMessage({
        type: "FT_TRANSLATE_DETAILED",
        texts,
        options: { sourceLang: state.settings.sourceLang, targetLang: state.settings.targetLang }
      });
      if (!response?.ok) throw new Error(response?.error || "Shadow DOM 翻译失败");
      if (state.pagePaused) return;

      nodes.forEach((node, index) => {
        if (!node.isConnected || isExcluded(node.parentElement)) return;
        const translated = response.translations?.[index];
        if (typeof translated !== "string") return;
        const original = node.nodeValue;
        const prefix = original.match(/^\s*/)?.[0] || "";
        const suffix = original.match(/\s*$/)?.[0] || "";
        const rendered = `${prefix}${translated}${suffix}`;
        const record = { original, rendered, translated, translationEl: null };
        if (state.settings.displayMode === "bilingual") {
          const span = createShadowSpan(node, translated);
          node.parentNode?.insertBefore(span, node.nextSibling);
          record.translationEl = span;
        } else {
          node.nodeValue = rendered;
        }
        state.shadowRecords.set(node, record);
        state.trackedShadowNodes.add(node);
      });
    } catch (error) {
      console.warn("[FloatingTranslator shadow]", error);
    } finally {
      state.shadowBusy.delete(root);
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
    let timer = null;
    const observer = new MutationObserver(mutations => {
      if (state.shadowBusy.has(root)) return;
      let meaningful = false;
      for (const mutation of mutations) {
        if (mutation.type === "characterData") {
          const node = mutation.target;
          const record = state.shadowRecords.get(node);
          if (record && node.nodeValue === record.original) {
            restoreShadowRecord(node, record);
            continue;
          }
          meaningful = true;
        }
        for (const added of mutation.addedNodes || []) {
          if (added.nodeType === Node.ELEMENT_NODE && added.dataset?.ftOwned === "1") continue;
          if (added.nodeType === Node.ELEMENT_NODE || added.nodeType === Node.TEXT_NODE) meaningful = true;
          if (added.nodeType === Node.ELEMENT_NODE) discoverShadowRoots(added);
        }
      }
      if (meaningful) {
        clearTimeout(timer);
        timer = setTimeout(() => translateShadowRoot(root), 90);
      }
    });
    observer.observe(root, { subtree: true, childList: true, characterData: true });
    state.shadowObservers.set(root, observer);
    translateShadowRoot(root);
  }

  function discoverShadowRoots(root = document) {
    const inspect = element => {
      if (element?.shadowRoot && !isExcluded(element)) observeShadowRoot(element.shadowRoot);
    };
    if (root instanceof Element) inspect(root);
    if (root.querySelectorAll) for (const element of root.querySelectorAll("*")) inspect(element);
  }

  function startDocumentObserver() {
    if (state.docObserver || !document.documentElement) return;
    state.docObserver = new MutationObserver(mutations => {
      for (const mutation of mutations) {
        for (const added of mutation.addedNodes) {
          if (added.nodeType !== Node.ELEMENT_NODE) continue;
          discoverShadowRoots(added);
          markAdapterElements(added);
        }
      }
    });
    state.docObserver.observe(document.documentElement, { subtree: true, childList: true });
  }

  async function loadSettings() {
    const previous = state.settings;
    state.settings = { ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) };
    const shadowChanged = ["targetLang", "sourceLang", "displayMode", "enabled", "autoTranslate", "skipTargetLanguage"]
      .some(key => previous[key] !== state.settings[key]) || JSON.stringify(previous.siteRules) !== JSON.stringify(state.settings.siteRules);
    if (shadowChanged) {
      restoreShadowTranslations();
      if (translationEnabled()) for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
    }
    if (!state.settings.inputPreview || !state.settings.chatMode || !state.settings.enabled) removePreview();
  }

  function handleExclusionsChanged() {
    restoreShadowTranslations();
    removePreview();
    if (translationEnabled()) for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
  }

  function handleRouteChange() {
    if (location.href === state.lastUrl) return;
    state.lastUrl = location.href;
    if (state.activeEditable) cancelInputPreview(state.activeEditable);
    state.activeEditable = null;
    removePreview();
    setTimeout(() => {
      markAdapterElements(document);
      window.dispatchEvent(new CustomEvent("ft-route-change", { detail: { url: location.href } }));
      discoverShadowRoots(document);
      if (!state.pagePaused) for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
    }, 120);
  }

  function startRouteWatch() {
    window.addEventListener("popstate", handleRouteChange, true);
    window.addEventListener("hashchange", handleRouteChange, true);
    try { window.navigation?.addEventListener?.("navigate", () => setTimeout(handleRouteChange, 0)); } catch {}
    if (!state.routeTimer) state.routeTimer = setInterval(handleRouteChange, 1200);
  }

  document.addEventListener("input", event => {
    const editable = resolveEditable(event.target);
    if (editable) scheduleInputPreview(editable);
  }, true);

  document.addEventListener("focusin", event => {
    const editable = resolveEditable(event.target);
    if (!editable) return;
    if (state.activeEditable && state.activeEditable !== editable) cancelInputPreview(state.activeEditable);
    state.activeEditable = editable;
    scheduleInputPreview(editable);
  }, true);

  document.addEventListener("focusout", event => {
    const editable = resolveEditable(event.target);
    if (editable) {
      cancelInputPreview(editable);
      state.mutedInputs.delete(editable);
    }
    setTimeout(() => {
      if (state.previewInteracting) return;
      const active = resolveEditable(document.activeElement);
      if (!active) {
        state.activeEditable = null;
        removePreview();
      }
    }, 220);
  }, true);

  window.addEventListener("resize", positionPreview, { passive: true });
  window.addEventListener("scroll", positionPreview, { passive: true, capture: true });
  window.addEventListener("ft-exclusions-changed", handleExclusionsChanged, true);

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type === "FT_SET_PAUSED") {
      state.pagePaused = Boolean(message.paused);
      if (!state.pagePaused && translationEnabled()) {
        for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
      }
      return false;
    }
    if (message?.type === "FT_TRANSLATE_NOW") {
      state.pagePaused = false;
      restoreShadowTranslations();
      if (translationEnabled()) setTimeout(() => {
        for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
      }, 80);
      return false;
    }
    if (message?.type === "FT_RESCAN_PAGE") {
      if (!state.pagePaused && translationEnabled()) {
        for (const root of state.shadowObservers.keys()) translateShadowRoot(root);
      }
      return false;
    }
    if (message?.type === "FT_RESTORE_PAGE") {
      state.pagePaused = false;
      restoreShadowTranslations();
      return false;
    }
    return false;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  });

  async function init() {
    try { await exclusions()?.ready; } catch {}
    await loadSettings();
    markAdapterElements(document);
    startDocumentObserver();
    discoverShadowRoots(document);
    startRouteWatch();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true });
  else init();
})();