// ==UserScript==
// @name         FloatingTranslator UserScript
// @namespace    https://github.com/dulo11/fanyireal-time
// @version      0.6.0
// @description  持续翻译网页、动态内容和聊天输入；适配 X浏览器、Tampermonkey、Violentmonkey。
// @author       dulo11
// @match        http://*/*
// @match        https://*/*
// @run-at       document-idle
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_setClipboard
// @connect      api.cognitive.microsofttranslator.com
// @connect      translate.googleapis.com
// ==/UserScript==

(() => {
  'use strict';

  if (window.__FLOATING_TRANSLATOR_USERSCRIPT__) return;
  window.__FLOATING_TRANSLATOR_USERSCRIPT__ = true;

  const VERSION = '0.6.0';
  const SETTINGS_KEY = 'ft_userscript_settings_v06';
  const DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    provider: 'azure',
    fallbackGoogle: true,
    sourceLang: 'auto',
    targetLang: 'zh-CN',
    displayMode: 'translated',
    azureKey: '',
    azureRegion: '',
    inputPreview: true,
    inputSourceLang: 'auto',
    inputTargetLang: 'en',
    inputDelay: 600,
    siteRules: {}
  };

  const LANGUAGES = [
    ['auto', '自动识别'], ['zh-CN', '简体中文'], ['zh-TW', '繁體中文'], ['en', 'English'],
    ['ja', '日本語'], ['ko', '한국어'], ['vi', 'Tiếng Việt'], ['th', 'ไทย'], ['ms', 'Bahasa Melayu'],
    ['id', 'Bahasa Indonesia'], ['fil', 'Filipino'], ['fr', 'Français'], ['de', 'Deutsch'], ['es', 'Español'],
    ['pt', 'Português'], ['ru', 'Русский'], ['ar', 'العربية'], ['hi', 'हिन्दी'], ['it', 'Italiano'], ['tr', 'Türkçe']
  ];

  const SKIP_SELECTOR = [
    'script', 'style', 'noscript', 'code', 'pre', 'textarea', 'input', 'select', 'option',
    'svg', 'canvas', 'math', '[contenteditable="true"]', '[contenteditable=""]',
    '[data-ft-us-owned="1"]', '.ft-us-bilingual', '.ft-us-panel', '.ft-us-input-preview'
  ].join(',');

  const state = {
    settings: { ...DEFAULTS },
    queue: new Set(),
    processing: false,
    flushTimer: null,
    nodeState: new WeakMap(),
    trackedNodes: new Set(),
    observer: null,
    generation: 0,
    panel: null,
    button: null,
    inputStates: new WeakMap(),
    inputPreview: null,
    memoryCache: new Map()
  };

  function gmGet(key, fallback) {
    try {
      if (typeof GM_getValue === 'function') return GM_getValue(key, fallback);
      const raw = localStorage.getItem(key);
      return raw == null ? fallback : JSON.parse(raw);
    } catch {
      return fallback;
    }
  }

  function gmSet(key, value) {
    try {
      if (typeof GM_setValue === 'function') return GM_setValue(key, value);
      localStorage.setItem(key, JSON.stringify(value));
    } catch {}
  }

  function loadSettings() {
    const saved = gmGet(SETTINGS_KEY, {});
    state.settings = { ...DEFAULTS, ...(saved && typeof saved === 'object' ? saved : {}) };
    if (state.settings.provider !== 'google-web') state.settings.provider = 'azure';
  }

  function saveSettings(patch = {}) {
    state.settings = { ...state.settings, ...patch };
    gmSet(SETTINGS_KEY, state.settings);
  }

  function normalizeLang(lang) {
    const value = String(lang || '').trim().toLowerCase().replace('_', '-');
    if (!value) return '';
    if (value.startsWith('zh')) {
      return value.includes('tw') || value.includes('hk') || value.includes('hant') ? 'zh-hant' : 'zh-hans';
    }
    return value.split('-')[0];
  }

  function azureLang(lang) {
    const value = normalizeLang(lang);
    if (value === 'zh-hans') return 'zh-Hans';
    if (value === 'zh-hant') return 'zh-Hant';
    return lang === 'auto' ? '' : lang;
  }

  function googleLang(lang) {
    const value = normalizeLang(lang);
    if (value === 'zh-hans') return 'zh-CN';
    if (value === 'zh-hant') return 'zh-TW';
    return lang || 'auto';
  }

  function strongLanguage(text) {
    const value = String(text || '').trim();
    if (!value) return '';
    if (/[ぁ-ゟ゠-ヿ]/u.test(value)) return 'ja';
    if (/[가-힣]/u.test(value)) return 'ko';
    if (/[฀-๿]/u.test(value)) return 'th';
    if (/[؀-ۿ]/u.test(value)) return 'ar';
    if (/[Ѐ-ӿ]/u.test(value)) return 'ru';
    if (/[ăâđêôơưĂÂĐÊÔƠƯàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]/iu.test(value)) return 'vi';
    // 纯汉字在中日文之间有歧义，不在这里强判为中文。
    return '';
  }

  function siteRule() {
    return state.settings.siteRules?.[location.hostname] || 'default';
  }

  function translationEnabled() {
    if (!state.settings.enabled) return false;
    const rule = siteRule();
    if (rule === 'never') return false;
    if (rule === 'always') return true;
    return Boolean(state.settings.autoTranslate);
  }

  function splitWhitespace(value) {
    const match = String(value ?? '').match(/^(\s*)([\s\S]*?)(\s*)$/);
    return { prefix: match?.[1] || '', core: match?.[2] || '', suffix: match?.[3] || '' };
  }

  function hasLetters(text) {
    const value = String(text || '').trim();
    if (value.length < 2) return false;
    if (/^(https?:\/\/|www\.)\S+$/i.test(value)) return false;
    if (/^[\d\s\p{P}\p{S}_]+$/u.test(value)) return false;
    return /[\p{L}\p{M}]/u.test(value);
  }

  function isEligibleTextNode(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !node.isConnected) return false;
    const parent = node.parentElement;
    if (!parent || parent.closest(SKIP_SELECTOR)) return false;
    const { core } = splitWhitespace(node.nodeValue);
    if (!hasLetters(core)) return false;
    const detected = strongLanguage(core);
    if (detected && detected === normalizeLang(state.settings.targetLang)) return false;
    return true;
  }

  function collectTextNodes(root) {
    if (!root) return [];
    if (root.nodeType === Node.TEXT_NODE) return isEligibleTextNode(root) ? [root] : [];
    if (![Node.ELEMENT_NODE, Node.DOCUMENT_NODE, Node.DOCUMENT_FRAGMENT_NODE].includes(root.nodeType)) return [];
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
    const rect = node.parentElement?.getBoundingClientRect();
    if (!rect) return Number.MAX_SAFE_INTEGER;
    if (rect.bottom >= -250 && rect.top <= innerHeight + 450) return 0;
    if (rect.top > innerHeight) return rect.top - innerHeight;
    return Math.abs(rect.bottom);
  }

  function cacheKey(provider, sourceLang, targetLang, text) {
    return `${provider}\u0000${sourceLang}\u0000${targetLang}\u0000${text}`;
  }

  function openCacheDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open('floating-translator-userscript-cache', 1);
      req.onupgradeneeded = () => {
        if (!req.result.objectStoreNames.contains('translations')) req.result.createObjectStore('translations');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function cacheGet(key) {
    if (state.memoryCache.has(key)) return state.memoryCache.get(key);
    try {
      const db = await openCacheDb();
      const value = await new Promise((resolve, reject) => {
        const req = db.transaction('translations', 'readonly').objectStore('translations').get(key);
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => reject(req.error);
      });
      if (typeof value === 'string') state.memoryCache.set(key, value);
      return value;
    } catch {
      return null;
    }
  }

  async function cacheSet(key, value) {
    state.memoryCache.set(key, value);
    try {
      const db = await openCacheDb();
      await new Promise((resolve, reject) => {
        const tx = db.transaction('translations', 'readwrite');
        tx.objectStore('translations').put(value, key);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
      });
    } catch {}
  }

  function gmRequest(details) {
    return new Promise((resolve, reject) => {
      const fn = typeof GM_xmlhttpRequest === 'function' ? GM_xmlhttpRequest : null;
      if (!fn) {
        fetch(details.url, {
          method: details.method || 'GET',
          headers: details.headers,
          body: details.data
        }).then(async response => {
          const text = await response.text();
          resolve({ status: response.status, responseText: text });
        }).catch(reject);
        return;
      }
      fn({
        ...details,
        onload: resolve,
        onerror: error => reject(new Error(error?.error || '网络请求失败')),
        ontimeout: () => reject(new Error('网络请求超时'))
      });
    });
  }

  async function azureTranslateMany(texts, sourceLang, targetLang) {
    if (!state.settings.azureKey) throw new Error('尚未设置 Azure Translator Key');
    const params = new URLSearchParams({ 'api-version': '3.0', to: azureLang(targetLang) });
    if (sourceLang && sourceLang !== 'auto') params.set('from', azureLang(sourceLang));
    const headers = {
      'Content-Type': 'application/json',
      'Ocp-Apim-Subscription-Key': state.settings.azureKey
    };
    if (state.settings.azureRegion) headers['Ocp-Apim-Subscription-Region'] = state.settings.azureRegion;
    const response = await gmRequest({
      method: 'POST',
      url: `https://api.cognitive.microsofttranslator.com/translate?${params.toString()}`,
      headers,
      data: JSON.stringify(texts.map(text => ({ text }))),
      timeout: 20000
    });
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`Azure 翻译失败：HTTP ${response.status}`);
    }
    const data = JSON.parse(response.responseText || '[]');
    if (!Array.isArray(data) || data.length !== texts.length) throw new Error('Azure 返回条数异常');
    return data.map((item, i) => item?.translations?.[0]?.text ?? texts[i]);
  }

  async function googleTranslateOne(text, sourceLang, targetLang) {
    const params = new URLSearchParams({
      client: 'gtx', sl: googleLang(sourceLang || 'auto'), tl: googleLang(targetLang), dt: 't', q: text
    });
    const response = await gmRequest({
      method: 'GET',
      url: `https://translate.googleapis.com/translate_a/single?${params.toString()}`,
      timeout: 20000
    });
    if (response.status < 200 || response.status >= 300) throw new Error(`Google Web 翻译失败：HTTP ${response.status}`);
    const data = JSON.parse(response.responseText || 'null');
    return Array.isArray(data?.[0]) ? data[0].map(item => item?.[0] || '').join('') || text : text;
  }

  function makeBatches(items, maxItems = 40, maxChars = 16000) {
    const batches = [];
    let current = [];
    let chars = 0;
    for (const item of items) {
      if (current.length && (current.length >= maxItems || chars + item.text.length > maxChars)) {
        batches.push(current); current = []; chars = 0;
      }
      current.push(item); chars += item.text.length;
    }
    if (current.length) batches.push(current);
    return batches;
  }

  async function translateTexts(texts, sourceLang, targetLang) {
    const provider = state.settings.provider === 'google-web' ? 'google-web' : 'azure';
    const results = new Array(texts.length);
    const unique = new Map();
    texts.forEach((text, index) => {
      const key = String(text);
      if (!unique.has(key)) unique.set(key, []);
      unique.get(key).push(index);
    });

    const misses = [];
    await Promise.all([...unique.entries()].map(async ([text, indexes]) => {
      const key = cacheKey(provider, sourceLang, targetLang, text);
      const cached = await cacheGet(key);
      if (typeof cached === 'string') indexes.forEach(i => { results[i] = cached; });
      else misses.push({ text, indexes, key });
    }));

    for (const batch of makeBatches(misses)) {
      let translated;
      try {
        if (provider === 'azure') translated = await azureTranslateMany(batch.map(item => item.text), sourceLang, targetLang);
        else translated = await Promise.all(batch.map(item => googleTranslateOne(item.text, sourceLang, targetLang)));
      } catch (error) {
        if (provider !== 'azure' || !state.settings.fallbackGoogle) throw error;
        translated = await Promise.all(batch.map(item => googleTranslateOne(item.text, sourceLang, targetLang)));
      }
      await Promise.all(batch.map(async (item, i) => {
        const value = translated[i] ?? item.text;
        item.indexes.forEach(index => { results[index] = value; });
        await cacheSet(item.key, value);
      }));
    }
    return results;
  }

  function enqueue(node) {
    if (!translationEnabled() || !isEligibleTextNode(node)) return;
    const previous = state.nodeState.get(node);
    if (previous) {
      if (node.nodeValue === previous.rendered || node.nodeValue === previous.original) return;
      previous.translationEl?.remove();
      state.nodeState.delete(node);
      state.trackedNodes.delete(node);
    }
    state.queue.add(node);
    scheduleFlush();
  }

  function scan(root = document.body) {
    if (!translationEnabled() || !root) return;
    for (const node of collectTextNodes(root)) enqueue(node);
  }

  function scheduleFlush(delay = 120) {
    clearTimeout(state.flushTimer);
    state.flushTimer = setTimeout(processQueue, delay);
  }

  async function processQueue() {
    if (state.processing || !translationEnabled() || !state.queue.size) return;
    state.processing = true;
    const generation = state.generation;
    try {
      const candidates = [...state.queue].filter(isEligibleTextNode).sort((a, b) => nodePriority(a) - nodePriority(b)).slice(0, 30);
      candidates.forEach(node => state.queue.delete(node));
      const entries = candidates.map(node => ({ node, parts: splitWhitespace(node.nodeValue), original: node.nodeValue }))
        .filter(entry => hasLetters(entry.parts.core));
      if (!entries.length) return;
      const translations = await translateTexts(entries.map(entry => entry.parts.core), state.settings.sourceLang, state.settings.targetLang);
      if (generation !== state.generation || !translationEnabled()) return;
      entries.forEach((entry, index) => applyTranslation(entry, translations[index]));
    } catch (error) {
      console.warn('[FloatingTranslator UserScript]', error);
      showToast(error?.message || String(error));
    } finally {
      state.processing = false;
      if (translationEnabled() && state.queue.size) scheduleFlush(90);
    }
  }

  function applyTranslation(entry, translatedCore) {
    if (!entry.node.isConnected || typeof translatedCore !== 'string') return;
    const rendered = `${entry.parts.prefix}${translatedCore}${entry.parts.suffix}`;
    const record = { original: entry.original, rendered, translationEl: null };
    if (state.settings.displayMode === 'bilingual') {
      const span = document.createElement('span');
      span.className = 'ft-us-bilingual';
      span.dataset.ftUsOwned = '1';
      span.textContent = translatedCore;
      entry.node.parentNode?.insertBefore(span, entry.node.nextSibling);
      record.translationEl = span;
    } else {
      entry.node.nodeValue = rendered;
    }
    state.nodeState.set(entry.node, record);
    state.trackedNodes.add(entry.node);
  }

  function restorePage() {
    state.generation++;
    state.queue.clear();
    for (const node of [...state.trackedNodes]) {
      const record = state.nodeState.get(node);
      if (!record) continue;
      record.translationEl?.remove();
      if (node.isConnected && node.nodeValue === record.rendered) node.nodeValue = record.original;
      state.nodeState.delete(node);
    }
    state.trackedNodes.clear();
  }

  function startObserver() {
    if (state.observer || !document.documentElement) return;
    state.observer = new MutationObserver(mutations => {
      if (!translationEnabled()) return;
      for (const mutation of mutations) {
        if (mutation.type === 'characterData') enqueue(mutation.target);
        for (const added of mutation.addedNodes || []) {
          if (added.nodeType === Node.ELEMENT_NODE && added.dataset?.ftUsOwned === '1') continue;
          scan(added);
        }
      }
    });
    state.observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
  }

  function isEditable(el) {
    if (el instanceof HTMLTextAreaElement) return !el.disabled && !el.readOnly;
    if (el instanceof HTMLInputElement) return ['text', 'search', 'email', 'url', 'tel'].includes(el.type) && !el.disabled && !el.readOnly;
    return Boolean(el?.isContentEditable && el.children.length === 0);
  }

  function readEditable(el) {
    return el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ? el.value : el.textContent || '';
  }

  function writeEditable(el, value) {
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
      descriptor?.set ? descriptor.set.call(el, value) : (el.value = value);
    } else {
      el.textContent = value;
    }
    el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function removeInputPreview() {
    state.inputPreview?.remove();
    state.inputPreview = null;
  }

  function showInputPreview(anchor, original, translated, error = false) {
    removeInputPreview();
    const panel = document.createElement('div');
    panel.className = 'ft-us-input-preview';
    panel.dataset.ftUsOwned = '1';
    panel.innerHTML = `<div class="ft-us-preview-text"></div><div class="ft-us-preview-actions"></div>`;
    panel.querySelector('.ft-us-preview-text').textContent = translated;
    const actions = panel.querySelector('.ft-us-preview-actions');
    if (!error) {
      const replace = document.createElement('button'); replace.textContent = '替换输入框';
      replace.onclick = () => { writeEditable(anchor, translated); removeInputPreview(); anchor.focus(); };
      const copy = document.createElement('button'); copy.textContent = '复制';
      copy.onclick = () => {
        try {
          if (typeof GM_setClipboard === 'function') GM_setClipboard(translated);
          else navigator.clipboard?.writeText(translated);
          copy.textContent = '已复制';
        } catch { copy.textContent = '复制失败'; }
      };
      const close = document.createElement('button'); close.textContent = '关闭'; close.onclick = removeInputPreview;
      actions.append(replace, copy, close);
    }
    const rect = anchor.getBoundingClientRect();
    panel.style.left = `${Math.max(8, Math.min(innerWidth - 328, rect.left))}px`;
    panel.style.top = `${Math.min(innerHeight - 150, Math.max(8, rect.bottom + 8))}px`;
    document.documentElement.appendChild(panel);
    state.inputPreview = panel;
  }

  function scheduleInputPreview(el) {
    if (!state.settings.enabled || !state.settings.inputPreview || !isEditable(el)) return;
    const text = readEditable(el).trim();
    let item = state.inputStates.get(el);
    if (!item) { item = { seq: 0, timer: null }; state.inputStates.set(el, item); }
    clearTimeout(item.timer);
    if (text.length < 2) return removeInputPreview();
    const seq = ++item.seq;
    item.timer = setTimeout(async () => {
      const current = readEditable(el).trim();
      if (seq !== item.seq || current !== text || !el.isConnected) return;
      try {
        const [translated] = await translateTexts([current], state.settings.inputSourceLang, state.settings.inputTargetLang);
        if (seq === item.seq && document.activeElement === el) showInputPreview(el, current, translated);
      } catch (error) {
        if (seq === item.seq) showInputPreview(el, current, `输入翻译失败：${error?.message || error}`, true);
      }
    }, Math.max(300, Number(state.settings.inputDelay) || 600));
  }

  function languageOptions(selected, allowAuto = true) {
    return LANGUAGES.filter(([code]) => allowAuto || code !== 'auto')
      .map(([code, label]) => `<option value="${code}"${code === selected ? ' selected' : ''}>${label}</option>`).join('');
  }

  function createButton() {
    if (state.button) return;
    const button = document.createElement('button');
    button.className = 'ft-us-fab';
    button.dataset.ftUsOwned = '1';
    button.textContent = 'FT';
    button.title = `FloatingTranslator ${VERSION}`;
    button.onclick = () => togglePanel();
    document.documentElement.appendChild(button);
    state.button = button;
  }

  function togglePanel(force) {
    if (state.panel) {
      if (force === true) return;
      state.panel.remove(); state.panel = null; return;
    }
    const s = state.settings;
    const panel = document.createElement('div');
    panel.className = 'ft-us-panel'; panel.dataset.ftUsOwned = '1';
    panel.innerHTML = `
      <div class="ft-us-title"><b>FloatingTranslator v${VERSION}</b><button data-x>×</button></div>
      <label><span>启用</span><input data-k="enabled" type="checkbox" ${s.enabled ? 'checked' : ''}></label>
      <label><span>主引擎</span><select data-k="provider"><option value="azure" ${s.provider === 'azure' ? 'selected' : ''}>Azure</option><option value="google-web" ${s.provider === 'google-web' ? 'selected' : ''}>Google Web</option></select></label>
      <label><span>网页源语言</span><select data-k="sourceLang">${languageOptions(s.sourceLang, true)}</select></label>
      <label><span>网页目标</span><select data-k="targetLang">${languageOptions(s.targetLang, false)}</select></label>
      <label><span>显示</span><select data-k="displayMode"><option value="translated" ${s.displayMode === 'translated' ? 'selected' : ''}>仅译文</option><option value="bilingual" ${s.displayMode === 'bilingual' ? 'selected' : ''}>双语</option></select></label>
      <label><span>Azure Key</span><input data-k="azureKey" type="password" value="${escapeHtml(s.azureKey)}" placeholder="Microsoft Translator Key"></label>
      <label><span>Azure Region</span><input data-k="azureRegion" value="${escapeHtml(s.azureRegion)}" placeholder="按资源填写"></label>
      <label><span>Azure失败回退</span><input data-k="fallbackGoogle" type="checkbox" ${s.fallbackGoogle ? 'checked' : ''}></label>
      <label><span>输入预翻译</span><input data-k="inputPreview" type="checkbox" ${s.inputPreview ? 'checked' : ''}></label>
      <label><span>输入目标</span><select data-k="inputTargetLang">${languageOptions(s.inputTargetLang, false)}</select></label>
      <label><span>此网站</span><select data-k="siteRule"><option value="default" ${siteRule() === 'default' ? 'selected' : ''}>默认</option><option value="always" ${siteRule() === 'always' ? 'selected' : ''}>始终翻译</option><option value="never" ${siteRule() === 'never' ? 'selected' : ''}>永不翻译</option></select></label>
      <div class="ft-us-actions"><button data-save>保存并应用</button><button data-now>翻译当前页</button><button data-restore>恢复原文</button></div>
    `;
    panel.querySelector('[data-x]').onclick = () => togglePanel();
    panel.querySelector('[data-save]').onclick = () => {
      const patch = {};
      panel.querySelectorAll('[data-k]').forEach(el => {
        const key = el.dataset.k;
        if (key === 'siteRule') return;
        patch[key] = el.type === 'checkbox' ? el.checked : el.value;
      });
      const rule = panel.querySelector('[data-k="siteRule"]').value;
      patch.siteRules = { ...(state.settings.siteRules || {}), [location.hostname]: rule };
      restorePage(); saveSettings(patch); togglePanel();
      if (translationEnabled()) scan(document.body);
      showToast('设置已保存');
    };
    panel.querySelector('[data-now]').onclick = () => { restorePage(); scan(document.body); showToast('已重新扫描当前页'); };
    panel.querySelector('[data-restore]').onclick = () => { restorePage(); showToast('已恢复原文'); };
    document.documentElement.appendChild(panel);
    state.panel = panel;
  }

  function escapeHtml(value) {
    return String(value || '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  function showToast(text) {
    document.querySelectorAll('.ft-us-toast').forEach(el => el.remove());
    const toast = document.createElement('div');
    toast.className = 'ft-us-toast'; toast.dataset.ftUsOwned = '1'; toast.textContent = text;
    document.documentElement.appendChild(toast);
    setTimeout(() => toast.remove(), 2800);
  }

  function addStyles() {
    const style = document.createElement('style');
    style.dataset.ftUsOwned = '1';
    style.textContent = `
      .ft-us-fab{position:fixed!important;right:16px!important;bottom:86px!important;z-index:2147483646!important;width:44px!important;height:44px!important;border:0!important;border-radius:50%!important;background:#111827!important;color:#fff!important;font:700 14px/44px system-ui!important;box-shadow:0 6px 22px #0005!important;padding:0!important}
      .ft-us-panel{position:fixed!important;right:12px!important;bottom:142px!important;z-index:2147483647!important;width:min(360px,calc(100vw - 24px))!important;max-height:78vh!important;overflow:auto!important;background:#fff!important;color:#111827!important;border:1px solid #d1d5db!important;border-radius:14px!important;padding:12px!important;box-shadow:0 14px 48px #0005!important;font:14px/1.4 system-ui!important}
      .ft-us-title{display:flex!important;align-items:center!important;justify-content:space-between!important;margin-bottom:8px!important}.ft-us-title button{font-size:22px!important;background:none!important;border:0!important}
      .ft-us-panel label{display:grid!important;grid-template-columns:105px 1fr!important;gap:8px!important;align-items:center!important;margin:8px 0!important}.ft-us-panel input,.ft-us-panel select{min-width:0!important;width:100%!important;box-sizing:border-box!important;padding:7px!important;border:1px solid #cbd5e1!important;border-radius:8px!important;background:#fff!important;color:#111827!important}
      .ft-us-panel input[type=checkbox]{width:20px!important;height:20px!important}.ft-us-actions{display:flex!important;gap:6px!important;flex-wrap:wrap!important;margin-top:10px!important}.ft-us-actions button,.ft-us-input-preview button{padding:7px 10px!important;border:1px solid #cbd5e1!important;border-radius:8px!important;background:#f8fafc!important;color:#111827!important}
      .ft-us-bilingual{display:block!important;margin-top:3px!important;padding-left:6px!important;border-left:2px solid #94a3b8!important;opacity:.92!important}.ft-us-input-preview{position:fixed!important;z-index:2147483647!important;width:min(320px,calc(100vw - 16px))!important;background:#fff!important;color:#111827!important;border:1px solid #cbd5e1!important;border-radius:12px!important;padding:10px!important;box-shadow:0 10px 32px #0004!important;font:14px/1.4 system-ui!important}.ft-us-preview-actions{display:flex!important;gap:6px!important;margin-top:8px!important}
      .ft-us-toast{position:fixed!important;left:50%!important;bottom:30px!important;transform:translateX(-50%)!important;z-index:2147483647!important;background:#111827!important;color:white!important;padding:8px 14px!important;border-radius:999px!important;font:13px system-ui!important;box-shadow:0 6px 22px #0005!important}
    `;
    document.documentElement.appendChild(style);
  }

  function registerMenu() {
    if (typeof GM_registerMenuCommand !== 'function') return;
    GM_registerMenuCommand('FloatingTranslator：设置', () => togglePanel(true));
    GM_registerMenuCommand('FloatingTranslator：翻译当前页', () => { restorePage(); scan(document.body); });
    GM_registerMenuCommand('FloatingTranslator：恢复原文', restorePage);
  }

  function bindInputPreview() {
    document.addEventListener('input', event => {
      const el = event.target;
      if (isEditable(el)) scheduleInputPreview(el);
    }, true);
    document.addEventListener('focusin', event => {
      const el = event.target;
      if (isEditable(el)) scheduleInputPreview(el);
    }, true);
    document.addEventListener('focusout', event => {
      if (state.inputPreview?.contains(event.relatedTarget)) return;
      setTimeout(() => { if (!state.inputPreview?.matches(':hover')) removeInputPreview(); }, 180);
    }, true);
  }

  function init() {
    loadSettings();
    addStyles();
    createButton();
    registerMenu();
    bindInputPreview();
    startObserver();
    if (translationEnabled()) scan(document.body);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
