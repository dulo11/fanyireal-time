(() => {
  if (window.__FLOATING_TRANSLATOR_LOADED__) return;
  window.__FLOATING_TRANSLATOR_LOADED__ = true;

  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    performanceSafeMode: true,
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

  const BLOCK_TAGS = new Set([
    "P", "DIV", "LI", "ARTICLE", "SECTION", "HEADER", "FOOTER", "MAIN", "ASIDE",
    "TD", "TH", "H1", "H2", "H3", "H4", "H5", "H6", "BLOCKQUOTE"
  ]);

  const SKIP_SELECTOR = [
    "script", "style", "noscript", "code", "pre", "textarea", "input", "select", "option",
    "svg", "canvas", "math", "[contenteditable='true']", "[contenteditable='']",
    ".ft-translation-inline", ".ft-selection-bubble", ".ft-input-preview", "[data-ft-owned='1']"
  ].join(",");

  const state = {
    settings: { ...DEFAULTS },
    active: false,
    paused: false,
    queue: new Set(),
    processing: false,
    flushTimer: null,
    retryTimer: null,
    nodeState: new WeakMap(),
    nodeRetries: new WeakMap(),
    consecutiveBatchErrors: 0,
    trackedNodes: new Set(),
    generation: 0,
    observer: null,
    started: false,
    processed: 0,
    failed: 0,
    retried: 0,
    protectedRestores: 0,
    lastError: "",
    scanTokens: new Set(),
    pendingTreeRoots: [],
    safetyScanTimer: null,
    mutationWindowStart: 0,
    mutationWindowCount: 0,
    cooldownUntil: 0,
    recoveryTimer: null,
    readyForTranslation: false,
    safeMutationTimer: null,
    safeMutationRoots: new Set(),
    safeMutationLastFlush: 0
  };

  const lang = () => globalThis.FTLanguage;
  const exclusions = () => globalThis.FTSiteExclusions;

  function isExcludedElement(element) {
    try { return Boolean(exclusions()?.isExcluded?.(element)); }
    catch { return false; }
  }

  function pageLanguage() {
    return lang()?.normalizeLang(document.documentElement?.lang || document.body?.getAttribute("lang") || "") || "";
  }

  function siteRule() {
    try { return state.settings.siteRules?.[location.hostname] || "default"; }
    catch { return "default"; }
  }

  function shouldTranslatePage() {
    if (!state.settings.enabled) return false;
    const rule = siteRule();
    if (rule === "never") return false;
    if (rule === "always") return true;
    if (!state.settings.autoTranslate) return false;
    if (state.settings.sourceLang !== "auto") return true;
    const page = pageLanguage();
    const target = lang()?.normalizeLang(state.settings.targetLang) || "";
    return !(page && target && page === target);
  }

  function splitWhitespace(value) {
    const match = String(value ?? "").match(/^(\s*)([\s\S]*?)(\s*)$/);
    return { prefix: match?.[1] || "", core: match?.[2] || "", suffix: match?.[3] || "" };
  }

  function hasLetters(text) {
    if (!text || text.trim().length < 2) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(text.trim())) return false;
    if (/^[\d\s\p{P}\p{S}_]+$/u.test(text)) return false;
    return /[\p{L}\p{M}]/u.test(text);
  }

  function shouldSkipTarget(text) {
    if (!state.settings.skipTargetLanguage || state.settings.sourceLang !== "auto") return false;
    const helper = lang();
    if (!helper) return false;
    return !helper.shouldTranslateText(text, {
      sourceLang: "auto",
      targetLang: state.settings.targetLang,
      pageLang: pageLanguage()
    });
  }

  function isEligibleTextNode(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !node.isConnected) return false;
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIP_SELECTOR) || isExcludedElement(parent)) return false;
    if (state.settings.performanceSafeMode !== false &&
        parent.closest('button,a,nav,form,label,[role="button"],[role="link"],[aria-live],[data-testid*="editor"],[data-testid*="input"]')) return false;
    const { core } = splitWhitespace(node.nodeValue);
    if (!hasLetters(core) || shouldSkipTarget(core)) return false;
    return true;
  }

  function nodePriority(node) {
    const parent = node.parentElement;
    if (!parent) return Number.MAX_SAFE_INTEGER;
    if (state.settings.chatMode && parent.closest("[data-ft-chat-feed='1']")) return -100;
    const rect = parent.getBoundingClientRect();
    if (rect.bottom >= -400 && rect.top <= innerHeight + 700) return Math.abs(rect.top) / 1000;
    if (rect.top > innerHeight) return 10 + (rect.top - innerHeight);
    return 10 + Math.abs(rect.bottom);
  }

  function createTranslationSpan(node, translatedCore) {
    const span = document.createElement("span");
    span.className = "ft-translation-inline";
    span.dataset.ftOwned = "1";
    span.translate = false;
    span.textContent = translatedCore;
    if (BLOCK_TAGS.has(node.parentElement?.tagName)) span.dataset.ftBlock = "1";
    return span;
  }

  function restoreProtectedNode(node, record) {
    if (!record || !node?.isConnected || !state.active || state.paused || isExcludedElement(node.parentElement)) return false;
    if (node.nodeValue !== record.originalFull) return false;
    // 安全模式尊重站点框架的 DOM 管理，不会抢写已经被 React/Vue 恢复的文本。
    if (state.settings.performanceSafeMode !== false) return false;
    // React/Vue 可将译文恢复为原文；反复抢写会与站点 DOM 更新形成死循环。
    const now = Date.now();
    if (!record.restoreWindowStart || now - record.restoreWindowStart > 30000) {
      record.restoreWindowStart = now;
      record.restoreAttempts = 0;
    }
    if (++record.restoreAttempts > 3) return false;

    if (state.settings.displayMode === "bilingual") {
      if (!record.translationEl?.isConnected) {
        const span = record.translationEl || createTranslationSpan(node, record.translatedCore);
        record.translationEl = span;
        node.parentNode?.insertBefore(span, node.nextSibling);
        state.protectedRestores++;
        return true;
      }
      return false;
    }

    node.nodeValue = record.renderedFull;
    state.protectedRestores++;
    return true;
  }

  function enqueueNode(node) {
    if (!state.active || state.paused || !isEligibleTextNode(node)) return;
    const previous = state.nodeState.get(node);
    if (previous) {
      const current = node.nodeValue;
      if (current === previous.renderedFull) return;
      if (current === previous.originalFull) {
        restoreProtectedNode(node, previous);
        return;
      }
      previous.translationEl?.remove();
      state.nodeState.delete(node);
      state.trackedNodes.delete(node);
    }
    if (state.queue.size >= 1000) return; // 有界队列，避免动态网页持续渲染时堆积
    state.queue.add(node);
    scheduleFlush();
  }

  function scheduleTreeScan(root = document.body) {
    if (!state.active || state.paused || !root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      enqueueNode(root);
      return;
    }
    if (![Node.ELEMENT_NODE, Node.DOCUMENT_NODE, Node.DOCUMENT_FRAGMENT_NODE].includes(root.nodeType)) return;
    if (root.nodeType === Node.ELEMENT_NODE && (root.matches?.(SKIP_SELECTOR) || isExcludedElement(root))) return;

    if (state.scanTokens.size >= (state.settings.performanceSafeMode !== false ? 2 : 6)) {
      // 排队等待空闲，不丢掉 MutationObserver 新增的后续内容。
      if (state.pendingTreeRoots.length < 16 && !state.pendingTreeRoots.includes(root)) state.pendingTreeRoots.push(root);
      return;
    }
    const token = { cancelled: false };
    const completeScan = () => {
      state.scanTokens.delete(token);
      if (!state.active || state.paused || !state.pendingTreeRoots.length) return;
      const next = state.pendingTreeRoots.shift();
      setTimeout(() => scheduleTreeScan(next), 50);
    };
    state.scanTokens.add(token);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);

    const step = deadline => {
      if (token.cancelled || !state.active || state.paused) {
        completeScan();
        return;
      }
      let count = 0;
      while (count < 120 && (!deadline || deadline.timeRemaining() > 2)) {
        const node = walker.nextNode();
        if (!node) {
          completeScan();
          return;
        }
        if (isEligibleTextNode(node)) enqueueNode(node);
        count++;
      }
      if (typeof requestIdleCallback === "function") requestIdleCallback(step, { timeout: 250 });
      else setTimeout(() => step(null), 12);
    };

    if (typeof requestIdleCallback === "function") requestIdleCallback(step, { timeout: 250 });
    else setTimeout(() => step(null), 0);
  }

  function cancelScans() {
    for (const token of state.scanTokens) token.cancelled = true;
    state.scanTokens.clear();
    state.pendingTreeRoots.length = 0;
  }

  function scheduleFlush(delay = 90) {
    if (state.paused || !state.active || state.flushTimer !== null) return;
    // 原实现每添加一个文字节点都会重置计时器：长页面可能持续推迟队列。
    state.flushTimer = setTimeout(() => {
      state.flushTimer = null;
      void processQueue();
    }, delay);
  }

  function takeBatch() {
    const candidates = [...state.queue].filter(isEligibleTextNode).sort((a, b) => nodePriority(a) - nodePriority(b));
    const batch = [];
    let chars = 0;
    for (const node of candidates) {
      const core = splitWhitespace(node.nodeValue).core;
      if (batch.length >= 8 || (batch.length && chars + core.length > 2400)) break;
      batch.push(node);
      chars += core.length;
    }
    for (const node of batch) state.queue.delete(node);
    return batch;
  }

  function retryFailedEntries(entries) {
    let maxRetry = 0;
    for (const entry of entries) {
      if (!entry.node?.isConnected || !isEligibleTextNode(entry.node)) continue;
      const retries = (state.nodeRetries.get(entry.node) || 0) + 1;
      state.nodeRetries.set(entry.node, retries);
      if (retries <= 1) {
        state.queue.add(entry.node);
        state.retried++;
        maxRetry = Math.max(maxRetry, retries);
      }
    }
    if (!state.queue.size || state.paused) return;
    clearTimeout(state.retryTimer);
    state.retryTimer = setTimeout(() => {
      state.retryTimer = null;
      scheduleFlush(0);
    }, Math.min(8000, 650 * (2 ** Math.max(0, maxRetry - 1))));
  }

  function pauseForSafety(reason) {
    state.lastError = reason;
    setPaused(true);
    try {
      window.dispatchEvent(new CustomEvent("ft-translation-safety-pause", { detail: { reason } }));
    } catch {}
  }

  async function sendTranslationWithDeadline(message, timeoutMs = 18000) {
    let timer = null;
    try {
      return await Promise.race([
        chrome.runtime.sendMessage(message),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error("翻译请求超过18秒，已自动暂停本页；请检查引擎或网络后再继续")), timeoutMs);
        })
      ]);
    } finally {
      if (timer !== null) clearTimeout(timer);
    }
  }

  async function processQueue() {
    if (state.processing || !state.active || state.paused || state.queue.size === 0) return;
    state.processing = true;
    const generation = state.generation;
    let entries = [];

    try {
      const candidates = takeBatch();
      if (!candidates.length) return;
      entries = candidates.map(node => {
        const parts = splitWhitespace(node.nodeValue);
        return { node, parts, originalFull: node.nodeValue };
      }).filter(entry => hasLetters(entry.parts.core));
      if (!entries.length) return;

      const response = await sendTranslationWithDeadline({
        type: "FT_TRANSLATE_DETAILED",
        texts: entries.map(entry => entry.parts.core),
        options: { sourceLang: state.settings.sourceLang, targetLang: state.settings.targetLang }
      });

      if (generation !== state.generation || !state.active) return;
      if (state.paused) {
        for (const entry of entries) if (entry.node?.isConnected) state.queue.add(entry.node);
        return;
      }
      if (!response?.ok) throw new Error(response?.error || "翻译失败");

      const failedEntries = [];
      let firstError = "";
      entries.forEach((entry, index) => {
        const translated = response.translations?.[index];
        const itemError = response.errors?.[index];
        if (typeof translated === "string" && entry.node.isConnected && !isExcludedElement(entry.node.parentElement)) {
          applyTranslation(entry.node, entry.parts, entry.originalFull, translated);
          state.nodeRetries.delete(entry.node);
          state.processed++;
          return;
        }
        if (isExcludedElement(entry.node?.parentElement)) return;
        failedEntries.push(entry);
        firstError ||= String(itemError || "该文本翻译失败");
      });

      if (failedEntries.length) {
        state.failed += failedEntries.length;
        state.lastError = firstError;
        state.consecutiveBatchErrors++;
        if (state.consecutiveBatchErrors >= 2) {
          pauseForSafety(`连续翻译失败，已暂停本页：${firstError}`);
        } else {
          retryFailedEntries(failedEntries);
        }
      } else {
        state.consecutiveBatchErrors = 0;
        state.lastError = "";
      }
    } catch (error) {
      if (generation !== state.generation || state.paused) return;
      state.failed += Math.max(1, entries.length);
      const reason = String(error?.message || error);
      state.lastError = reason;
      state.consecutiveBatchErrors++;
      if (reason.includes("超过18秒") || state.consecutiveBatchErrors >= 2) {
        pauseForSafety(reason);
      } else {
        retryFailedEntries(entries);
      }
      console.warn("[FloatingTranslator]", error);
    } finally {
      state.processing = false;
      if (state.active && !state.paused && state.queue.size && !state.retryTimer) scheduleFlush(50);
    }
  }

  function applyTranslation(node, parts, originalFull, translatedCore) {
    const renderedFull = `${parts.prefix}${translatedCore}${parts.suffix}`;
    const record = { originalFull, translatedCore, renderedFull, translationEl: null };
    if (state.settings.displayMode === "bilingual") {
      const span = createTranslationSpan(node, translatedCore);
      node.parentNode?.insertBefore(span, node.nextSibling);
      record.translationEl = span;
    } else {
      node.nodeValue = renderedFull;
    }
    state.nodeState.set(node, record);
    state.trackedNodes.add(node);
  }

  function restorePage() {
    state.generation++;
    cancelScans();
    clearTimeout(state.flushTimer);
    state.flushTimer = null;
    clearTimeout(state.retryTimer);
    state.retryTimer = null;
    clearTimeout(state.recoveryTimer);
    state.recoveryTimer = null;
    clearTimeout(state.safeMutationTimer);
    state.safeMutationTimer = null;
    state.safeMutationRoots.clear();
    state.queue.clear();
    for (const node of [...state.trackedNodes]) {
      const record = state.nodeState.get(node);
      if (!record) continue;
      record.translationEl?.remove();
      if (node.isConnected && node.nodeValue === record.renderedFull) node.nodeValue = record.originalFull;
      state.nodeState.delete(node);
    }
    state.trackedNodes.clear();
    state.processed = 0;
    state.failed = 0;
    state.retried = 0;
    state.protectedRestores = 0;
    state.lastError = "";
    state.consecutiveBatchErrors = 0;
  }

  async function loadSettings({ rescan = true } = {}) {
    const next = { ...DEFAULTS, ...(await chrome.storage.sync.get(DEFAULTS)) };
    const materiallyChanged = [
      "sourceLang", "targetLang", "displayMode", "enabled", "autoTranslate", "skipTargetLanguage", "performanceSafeMode"
    ].some(key => state.settings[key] !== next[key]) || JSON.stringify(state.settings.siteRules) !== JSON.stringify(next.siteRules);

    if (materiallyChanged) restorePage();
    state.settings = next;
    state.active = shouldTranslatePage();
    if (rescan && state.readyForTranslation && state.active && !state.paused) scheduleTreeScan(document.body);
  }

  function handleMutations(mutations) {
    if (!state.active || state.paused) return;
    const now = Date.now();
    if (state.settings.performanceSafeMode !== false) {
      // 安卓轻量模式：绝不在 MutationObserver 回调中遍历整页，也不抢写 characterData。
      // 只收集新出现的 DOM 子树，合并为低频、延迟的增量扫描。
      for (const mutation of mutations.slice(0, 24)) {
        if (mutation.type !== "childList") continue;
        // 不使用 Array.from(NodeList)：超大 DOM 更新时可能分配数千元素。
        const addedNodes = mutation.addedNodes || [];
        for (let i = 0; i < Math.min(addedNodes.length, 16); i++) {
          const added = addedNodes[i];
          if (added.nodeType !== Node.ELEMENT_NODE && added.nodeType !== Node.TEXT_NODE) continue;
          if (added.nodeType === Node.ELEMENT_NODE &&
              (added.dataset?.ftOwned === "1" || added.matches?.(SKIP_SELECTOR))) continue;
          if (state.safeMutationRoots.size < 8) state.safeMutationRoots.add(added);
        }
      }
      if (state.safeMutationRoots.size && state.safeMutationTimer === null) {
        const delay = Math.max(1000, 2000 - (now - state.safeMutationLastFlush));
        state.safeMutationTimer = setTimeout(() => {
          state.safeMutationTimer = null;
          state.safeMutationLastFlush = Date.now();
          const roots = [...state.safeMutationRoots];
          state.safeMutationRoots.clear();
          if (state.active && !state.paused) {
            // 初始页面扫描已经进行，后续仅扫描新子树，不重扫整个 document。
            for (const root of roots) scheduleTreeScan(root);
          }
        }, delay);
      }
      return;
    }
    if (now < state.cooldownUntil) return;
    if (now - state.mutationWindowStart >= 1000) {
      state.mutationWindowStart = now;
      state.mutationWindowCount = 0;
    }
    state.mutationWindowCount += mutations.length;
    // React/Vue 等站点正在批量渲染时，主动让出主线程，不参与 DOM 更新竞争。
    if (state.mutationWindowCount > 180) {
      state.cooldownUntil = now + 4000;
      cancelScans();
      clearTimeout(state.flushTimer);
      state.flushTimer = null;
      state.queue.clear();
      if (state.recoveryTimer !== null) clearTimeout(state.recoveryTimer);
      state.recoveryTimer = setTimeout(() => {
        state.recoveryTimer = null;
        if (state.active && !state.paused) scheduleTreeScan(document.body);
      }, 4300);
      return;
    }
    for (const mutation of mutations.slice(0, 80)) {
      if (mutation.type === "characterData") {
        const node = mutation.target;
        const record = state.nodeState.get(node);
        if (record) {
          if (node.nodeValue === record.renderedFull) continue;
          if (node.nodeValue === record.originalFull) {
            restoreProtectedNode(node, record);
            continue;
          }
        }
        enqueueNode(node);
        continue;
      }
      for (const added of mutation.addedNodes) {
        if (added.nodeType === Node.ELEMENT_NODE && added.dataset?.ftOwned === "1") continue;
        scheduleTreeScan(added);
      }
    }
  }

  function startObserver() {
    if (state.observer || !document.documentElement) return;
    state.observer = new MutationObserver(handleMutations);
    state.observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
  }

  function showSelectionBubble(original, translated, isError = false) {
    document.querySelectorAll(".ft-selection-bubble").forEach(el => el.remove());
    const bubble = document.createElement("div");
    bubble.className = `ft-selection-bubble${isError ? " ft-error" : ""}`;
    bubble.dataset.ftOwned = "1";
    const translatedEl = document.createElement("div");
    translatedEl.className = "ft-selection-result";
    translatedEl.textContent = translated;
    const originalEl = document.createElement("div");
    originalEl.className = "ft-selection-original";
    originalEl.textContent = original;
    bubble.append(translatedEl, originalEl);
    document.documentElement.appendChild(bubble);

    let rect = null;
    try {
      const selection = window.getSelection();
      if (selection?.rangeCount) rect = selection.getRangeAt(0).getBoundingClientRect();
    } catch {}
    bubble.style.top = `${rect ? Math.min(innerHeight - 120, Math.max(10, rect.bottom + 8)) : 20}px`;
    bubble.style.left = `${rect ? Math.min(innerWidth - 320, Math.max(10, rect.left)) : 20}px`;
    setTimeout(() => bubble.classList.add("ft-visible"), 0);
    setTimeout(() => bubble.remove(), 9000);
  }

  async function translateFocusedInput(event) {
    if (!(event.altKey && event.key === "Enter")) return;
    const el = event.target;
    const isTextInput = el instanceof HTMLTextAreaElement || (el instanceof HTMLInputElement && ["text", "search", "email", "url", "tel"].includes(el.type));
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
          sourceLang: state.settings.chatMode ? state.settings.inputSourceLang : state.settings.sourceLang,
          targetLang: state.settings.chatMode ? state.settings.inputTargetLang : state.settings.targetLang
        }
      });
      if (!response?.ok) throw new Error(response?.error || "翻译失败");
      const translated = response.translations?.[0] || text;
      if (isTextInput) {
        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
        if (descriptor?.set) descriptor.set.call(el, translated); else el.value = translated;
        el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: translated }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
      } else {
        el.textContent = translated;
        el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: translated }));
      }
    } catch (error) {
      showSelectionBubble(text, `输入框翻译失败：${error?.message || error}`, true);
    }
  }

  function handleRouteRescan() {
    if (!state.active || state.paused) return;
    state.generation++;
    cancelScans();
    clearTimeout(state.flushTimer);
    state.flushTimer = null;
    clearTimeout(state.retryTimer);
    state.retryTimer = null;
    state.queue.clear();
    setTimeout(() => scheduleTreeScan(document.body), 100);
  }

  function scheduleSafetyScan(delay = 250) {
    clearTimeout(state.safetyScanTimer);
    state.safetyScanTimer = setTimeout(() => {
      if (state.active && !state.paused && document.visibilityState === "visible") scheduleTreeScan(document.body);
    }, delay);
  }

  function setPaused(paused) {
    state.paused = Boolean(paused);
    if (state.paused) {
      clearTimeout(state.safeMutationTimer);
      state.safeMutationTimer = null;
      state.safeMutationRoots.clear();
      cancelScans();
      clearTimeout(state.flushTimer);
      state.flushTimer = null;
      clearTimeout(state.retryTimer);
      state.retryTimer = null;
      return;
    }
    state.consecutiveBatchErrors = 0;
    state.lastError = "";
    if (state.active) {
      scheduleTreeScan(document.body);
      if (state.queue.size) scheduleFlush(0);
    }
  }

  function handleExclusionsChanged() {
    const wasPaused = state.paused;
    restorePage();
    state.paused = wasPaused;
    state.active = shouldTranslatePage();
    if (state.active && !state.paused) scheduleTreeScan(document.body);
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message?.type === "FT_REFRESH_SETTINGS") {
      loadSettings().then(() => sendResponse({ ok: true, active: state.active, paused: state.paused }));
      return true;
    }
    if (message?.type === "FT_TRANSLATE_NOW") {
      restorePage();
      state.paused = false;
      state.active = true;
      scheduleTreeScan(document.body);
      sendResponse({ ok: true });
      return false;
    }
    if (message?.type === "FT_RESCAN_PAGE") {
      handleRouteRescan();
      sendResponse({ ok: true, paused: state.paused });
      return false;
    }
    if (message?.type === "FT_SET_PAUSED") {
      setPaused(Boolean(message.paused));
      sendResponse({ ok: true, paused: state.paused, queued: state.queue.size });
      return false;
    }
    if (message?.type === "FT_RESTORE_PAGE") {
      state.active = false;
      state.paused = false;
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
        paused: state.paused,
        host: location.hostname,
        pageLang: pageLanguage(),
        queued: state.queue.size,
        processing: state.processing,
        processed: state.processed,
        failed: state.failed,
        retried: state.retried,
        protectedRestores: state.protectedRestores,
        lastError: state.lastError
      });
      return false;
    }
    return false;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && Object.keys(changes).some(key => key in DEFAULTS)) loadSettings();
  });
  document.addEventListener("keydown", translateFocusedInput, true);
  window.addEventListener("ft-route-change", handleRouteRescan, true);
  window.addEventListener("ft-exclusions-changed", handleExclusionsChanged, true);
  window.addEventListener("focus", () => scheduleSafetyScan(350), { passive: true });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") scheduleSafetyScan(350);
  });

  async function init() {
    if (state.started) return;
    state.started = true;
    try { await exclusions()?.ready; } catch {}
    await globalThis.FTSiteStartup?.wait?.();
    await loadSettings({ rescan: false });
    state.readyForTranslation = true;
    startObserver();
    if (state.active && !state.paused) scheduleTreeScan(document.body);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true });
  else init();
})();