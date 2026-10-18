// ==UserScript==
// @name         浮译 Lite · 轻量网页翻译
// @namespace    https://github.com/dulo11/fanyireal-time
// @version      0.1.0
// @description  Quetta / Titanium：加载后视区翻译、仅译文/双语、可拖动隐藏、站点排除和自动限速。
// @match        http://*/*
// @match        https://*/*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @connect      translate.googleapis.com
// @updateURL    https://raw.githubusercontent.com/dulo11/fanyireal-time/feature/browser-translator-v1.3-signed-update/userscript/Fuyi-Lite.user.js
// @downloadURL  https://raw.githubusercontent.com/dulo11/fanyireal-time/feature/browser-translator-v1.3-signed-update/userscript/Fuyi-Lite.user.js
// @license      MIT
// ==/UserScript==
(() => {
  'use strict';
  if (window.top !== window || window.__FuyiLite__) return;
  window.__FuyiLite__ = true;
  const KEY = 'fuyiLiteSettingsV1';
  const defaults = {
    enabled: true, auto: true, mode: 'translated', target: 'zh-CN',
    visible: true, size: 44, opacity: 0.85, snap: true, position: null, exclude: {}
  };
  function stored() {
    try {
      const v = GM_getValue(KEY, {});
      return v && typeof v === 'object' ? v : {};
    } catch { return {}; }
  }
  let config = { ...defaults, ...stored() };
  const save = () => { try { GM_setValue(KEY, config); } catch {} };
  let started = false, paused = false, opened = false, busy = false, generation = 0;
  let cooldownUntil = 0, count = 0, failures = 0;
  let queue = [], queueSet = new Set(), known = new WeakSet();
  let translations = new Map(), cache = new Map();
  let observer = null, visibilityObserver = null, notice = '网页加载后启动';
  let fab = null, panel = null, status = null;
  let drag = null, suppressClick = 0, timer = null, windowStart = 0, mutationCount = 0;
  const skipped = 'script,style,noscript,template,iframe,svg,canvas,math,code,pre,kbd,input,textarea,select,option,button,a,nav,header,footer,[contenteditable],[translate="no"],.notranslate,[data-fuyi-owned]';
  const candidate = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,td,th';
  const languages = [['zh-CN','简体中文'],['zh-TW','繁體中文'],['en','English'],
    ['ja','日本語'],['ko','한국어'],['vi','Tiếng Việt'],['th','ไทย'],
    ['ms','Bahasa Melayu'],['fil','Filipino'],['fr','Français'],['de','Deutsch']];
  const active = () => started && config.enabled && config.auto && !paused &&
    config.exclude?.[location.hostname] !== true;
  const note = v => {
    notice = v || '';
    if (status) status.textContent = notice || ('已翻译 ' + translations.size + ' 段 · 请求 ' + count);
  };
  const unsafe = e => {
    try { return !e || Boolean(e.closest(skipped)); } catch { return true; }
  };
  function valid(t) {
    const s = String(t || '').trim();
    return s.length >= 3 && s.length <= 650 && /[\p{L}]/u.test(s) &&
      !/^(https?:\/\/|www\.)\S+$/i.test(s) &&
      !(config.target.startsWith('zh') && /^[\p{Script=Han}\s\d\p{P}\p{S}]+$/u.test(s));
  }
  function add(node) {
    if (!active() || !node || node.nodeType !== 3 || !node.isConnected ||
        translations.has(node) || queueSet.has(node) || queue.length >= 130) return;
    if (unsafe(node.parentElement) || !valid(node.nodeValue)) return;
    queueSet.add(node);
    queue.push({ node, original: node.nodeValue });
    schedule();
  }
  function inspect(element) {
    if (!element?.isConnected || known.has(element) || unsafe(element)) return;
    known.add(element);
    for (const child of element.childNodes) if (child.nodeType === 3) add(child);
  }
  function register(element) {
    if (!element || known.has(element) || unsafe(element)) return;
    if (visibilityObserver) visibilityObserver.observe(element);
    else inspect(element);
  }
  function scan(root = document, limit = 320) {
    if (!active() || !root) return;
    let n = 0;
    if (root.nodeType === 1 && root.matches?.(candidate)) { register(root); n++; }
    if (root.querySelectorAll) for (const element of root.querySelectorAll(candidate)) {
      register(element);
      if (++n >= limit) break;
    }
  }
  function sendGoogle(text, rev) {
    return new Promise((resolve, reject) => {
      if (typeof GM_xmlhttpRequest !== 'function') {
        reject(new Error('油猴跨域请求权限不可用')); return;
      }
      const url = 'https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' +
        encodeURIComponent(config.target) + '&dt=t&q=' + encodeURIComponent(text);
      GM_xmlhttpRequest({
        method: 'GET', url, timeout: 8500,
        onload(r) {
          try {
            if (rev !== generation) { resolve(null); return; }
            if (r.status === 429) throw new Error('Google Web 429 限流');
            if (r.status < 200 || r.status >= 300) throw new Error('接口 HTTP ' + r.status);
            const parsed = JSON.parse(r.responseText);
            resolve((parsed[0] || []).map(part => part?.[0] || '').join('').trim() || null);
          } catch (e) { reject(e); }
        },
        onerror() { reject(new Error('网络错误')); },
        ontimeout() { reject(new Error('翻译超时')); }
      });
    });
  }
  function apply(node, original, translated) {
    if (!active() || !node.isConnected || node.nodeValue !== original ||
        !translated || translated === original.trim()) return;
    const left = original.match(/^\s*/)?.[0] || '';
    const right = original.match(/\s*$/)?.[0] || '';
    const rendered = left + translated + right;
    const rec = { original, rendered, extra: null };
    if (config.mode === 'bilingual') {
      const extra = document.createElement('span');
      extra.dataset.fuyiOwned = '1';
      extra.setAttribute('translate', 'no');
      extra.style.cssText = 'color:#6750e8!important;font:inherit!important;opacity:.9!important';
      extra.textContent = ' [' + translated + ']';
      node.parentNode?.insertBefore(extra, node.nextSibling);
      rec.extra = extra;
    } else node.nodeValue = rendered;
    translations.set(node, rec);
    if (translations.size % 5 === 0) note('');
  }
  function drain() {
    if (!active() || busy) return;
    if (Date.now() < cooldownUntil) { schedule(cooldownUntil - Date.now() + 30); return; }
    if (count >= 180) { note('本页已到安全翻译上限，可通过菜单重新启动'); return; }
    let job = null;
    while (queue.length) {
      const next = queue.shift();
      queueSet.delete(next.node);
      if (next.node.isConnected && next.node.nodeValue === next.original && !translations.has(next.node)) {
        job = next; break;
      }
    }
    if (!job) return;
    const rev = generation;
    const key = config.target + '|' + job.original.trim();
    if (cache.has(key)) {
      apply(job.node, job.original, cache.get(key));
      schedule(60); return;
    }
    busy = true; count++;
    sendGoogle(job.original.trim(), rev).then(result => {
      if (rev !== generation || !result) return;
      cache.set(key, result);
      if (cache.size > 400) cache.delete(cache.keys().next().value);
      apply(job.node, job.original, result);
      failures = 0;
    }).catch(error => {
      if (rev !== generation) return;
      failures++;
      cooldownUntil = Date.now() + (failures >= 2 ? 60000 : 12000);
      note('翻译暂缓：' + error.message);
    }).finally(() => {
      busy = false; schedule(failures ? 12000 : 400);
    });
  }
  function schedule(ms = 150) {
    if (timer !== null || !active()) return;
    timer = setTimeout(() => { timer = null; drain(); }, Math.max(ms, 20));
  }
  function clearPending() {
    generation++; queue = []; queueSet.clear();
    clearTimeout(timer); timer = null;
  }
  function restore() {
    clearPending();
    for (const [node, v] of translations) {
      v.extra?.remove();
      if (node.isConnected && node.nodeValue === v.rendered) node.nodeValue = v.original;
    }
    translations.clear(); known = new WeakSet();
    visibilityObserver?.disconnect();
    note('原文已恢复');
  }
  function updateConfig(patch, restart = false) {
    const old = config;
    config = { ...config, ...patch }; save();
    if (old.mode !== config.mode || old.target !== config.target) restore();
    if (!config.enabled || !config.auto || config.exclude?.[location.hostname] === true) clearPending();
    if (restart && active()) {
      paused = false; known = new WeakSet(); visibilityObserver?.disconnect();
      failures = 0; cooldownUntil = 0; count = 0; scan(document, 500);
    }
    redraw();
  }
  const node = (tag, text) => {
    const e = document.createElement(tag);
    e.dataset.fuyiOwned = '1'; e.setAttribute('translate', 'no');
    if (text != null) e.textContent = text;
    return e;
  };
  function place(x, y) {
    if (!fab) return;
    const size = Number(config.size) || 44;
    fab.style.left = Math.max(6, Math.min(innerWidth - size - 6, x)) + 'px';
    fab.style.top = Math.max(6, Math.min(innerHeight - size - 6, y)) + 'px';
    if (opened) placePanel();
  }
  function placePanel() {
    if (!opened || !panel) return;
    const b = fab.getBoundingClientRect(), width = Math.min(308, innerWidth - 20);
    panel.style.width = width + 'px';
    const height = panel.offsetHeight || 340;
    panel.style.left = Math.max(8, Math.min(innerWidth - width - 8,
      b.left > innerWidth / 2 ? b.right - width : b.left)) + 'px';
    panel.style.top = Math.max(8, Math.min(innerHeight - height - 8,
      b.top - height > 8 ? b.top - height - 8 : b.bottom + 8)) + 'px';
  }
  function open(force) {
    opened = typeof force === 'boolean' ? force : !opened;
    if (panel) panel.style.display = opened ? 'block' : 'none';
    if (opened) { redraw(); placePanel(); }
  }
  const css = 'box-sizing:border-box!important;font:14px/1.45 system-ui,Arial!important;';
  function button(text, action) {
    const el = node('button', text);
    el.type = 'button';
    el.style.cssText = css +
      'width:100%!important;border:1px solid #ddd!important;border-radius:8px!important;' +
      'background:#f4f1ff!important;color:#462e91!important;min-height:34px!important;margin:3px 0!important;';
    el.addEventListener('click', e => { e.stopPropagation(); action(); });
    panel.appendChild(el); return el;
  }
  function selectRow(label, options, current, action) {
    const wrap = node('label');
    wrap.style.cssText = css + 'display:block!important;margin:6px 0!important;';
    wrap.append(node('span', label));
    const select = node('select');
    select.style.cssText = css + 'display:block!important;width:100%!important;padding:5px!important;';
    for (const [value, title] of options) {
      const op = node('option', title); op.value = value; select.appendChild(op);
    }
    select.value = current;
    select.addEventListener('change', () => action(select.value));
    wrap.appendChild(select); panel.appendChild(wrap);
    return select;
  }
  function redraw() {
    if (!fab) return;
    fab.style.display = config.visible ? 'grid' : 'none';
    fab.style.opacity = String(config.opacity);
    fab.style.width = fab.style.height = (Number(config.size) || 44) + 'px';
    if (!config.visible) open(false);
    if (status) status.textContent = notice || ('已翻译 ' + translations.size + ' 段 · 请求 ' + count);
  }
  function buildUi() {
    if (!document.documentElement) return;
    fab = node('button', '译'); fab.type = 'button';
    fab.style.cssText = 'position:fixed!important;z-index:2147483646!important;' +
      'display:grid!important;place-items:center!important;background:#6750e8!important;color:#fff!important;' +
      'border:0!important;border-radius:14px!important;box-shadow:0 6px 20px #0005!important;' +
      'font:800 19px Arial!important;touch-action:none!important;user-select:none!important;';
    panel = node('section');
    panel.style.cssText = css + 'position:fixed!important;display:none;z-index:2147483647!important;' +
      'max-height:78vh!important;overflow:auto!important;background:white!important;color:#222!important;' +
      'padding:12px!important;border-radius:14px!important;border:1px solid #ddd!important;' +
      'box-shadow:0 10px 30px #0005!important;touch-action:pan-y!important;';
    panel.append(node('strong', '浮译 Lite（油猴版）'));
    status = node('div', '准备翻译…');
    status.style.cssText = 'margin:8px 0!important;font-size:12px!important;color:#777!important;';
    panel.append(status);
    button('启用/关闭自动翻译', () => updateConfig({ auto: !config.auto }, true));
    button('暂停/继续翻译', () => {
      paused = !paused; if (paused) clearPending(); else { known = new WeakSet(); scan(document, 500); }
      note(paused ? '翻译已暂停' : '翻译继续');
    });
    selectRow('显示模式', [['translated','仅译文'],['bilingual','原文 + 译文']],
      config.mode, value => updateConfig({ mode: value }, true));
    selectRow('翻译为', languages, config.target,
      value => updateConfig({ target: value }, true));
    selectRow('悬浮球大小', [['36','小'],['44','中'],['54','大']],
      String(config.size), value => updateConfig({ size: Number(value) }));
    selectRow('悬浮球透明度', [['0.35','35%'],['0.6','60%'],['0.85','85%'],['1','100%']],
      String(config.opacity), value => updateConfig({ opacity: Number(value) }));
    button('自动贴边：' + (config.snap ? '开' : '关'), () => updateConfig({ snap: !config.snap }));
    button('此网站禁止 / 恢复自动翻译', () => {
      const exclude = { ...config.exclude, [location.hostname]: !config.exclude?.[location.hostname] };
      updateConfig({ exclude }, true);
      note(exclude[location.hostname] ? '当前网站已排除' : '当前网站已恢复');
    });
    button('重新扫描本页', () => {
      known = new WeakSet(); count = 0; cooldownUntil = 0; failures = 0;
      visibilityObserver?.disconnect(); scan(document, 500);
    });
    button('恢复网页原文', restore);
    button('隐藏悬浮球（油猴菜单恢复）', () => updateConfig({ visible: false }));
    button('关闭小窗口', () => open(false));
    document.documentElement.append(fab, panel);
    const p = config.position;
    place(Number.isFinite(p?.x) ? p.x : innerWidth - config.size - 16,
      Number.isFinite(p?.y) ? p.y : innerHeight - config.size - 90);
    redraw();
    fab.addEventListener('touchstart', e => {
      const t = e.touches?.[0];
      if (t) { const b = fab.getBoundingClientRect(); drag = { id: t.identifier,
        sx: t.clientX, sy: t.clientY, x: b.left, y: b.top, moved: false }; }
    }, { passive: true });
    fab.addEventListener('touchmove', e => {
      if (!drag) return;
      const t = [...(e.changedTouches || [])].find(t => t.identifier === drag.id);
      if (!t) return;
      if (Math.hypot(t.clientX - drag.sx, t.clientY - drag.sy) > 6) drag.moved = true;
      if (drag.moved) {
        if (e.cancelable) e.preventDefault();
        place(drag.x + t.clientX - drag.sx, drag.y + t.clientY - drag.sy);
      }
    }, { passive: false });
    fab.addEventListener('touchend', e => {
      if (!drag || ![...(e.changedTouches || [])].some(t => t.identifier === drag.id)) return;
      const moved = drag.moved; drag = null; suppressClick = Date.now() + 650;
      if (!moved) open();
      else {
        const r = fab.getBoundingClientRect();
        place(config.snap ? (r.left < innerWidth / 2 ? 6 : innerWidth - config.size - 6) : r.left, r.top);
        const p = fab.getBoundingClientRect();
        config.position = { x: p.left, y: p.top }; save();
      }
    }, { passive: true });
    fab.addEventListener('touchcancel', () => { drag = null; }, { passive: true });
    fab.addEventListener('click', () => { if (Date.now() >= suppressClick) open(); });
    fab.addEventListener('pointerdown', e => {
      if (e.pointerType === 'touch') return;
      const r = fab.getBoundingClientRect();
      drag = { pointer: true, id: e.pointerId, sx: e.clientX, sy: e.clientY,
        x: r.left, y: r.top, moved: false };
      try { fab.setPointerCapture?.(e.pointerId); } catch {}
    });
    fab.addEventListener('pointermove', e => {
      if (!drag?.pointer || drag.id !== e.pointerId) return;
      if (Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) > 6) drag.moved = true;
      if (drag.moved) place(drag.x + e.clientX - drag.sx, drag.y + e.clientY - drag.sy);
    });
    fab.addEventListener('pointerup', e => {
      if (!drag?.pointer || drag.id !== e.pointerId) return;
      const moved = drag.moved; drag = null;
      if (moved) {
        suppressClick = Date.now() + 650;
        const p = fab.getBoundingClientRect();
        place(config.snap ? (p.left < innerWidth / 2 ? 6 : innerWidth - config.size - 6) : p.left, p.top);
        const r = fab.getBoundingClientRect();
        config.position = { x: r.left, y: r.top }; save();
      }
    });
    window.addEventListener('resize', () => {
      const r = fab.getBoundingClientRect(); place(r.left, r.top);
    }, { passive: true });
  }
  function start() {
    if (started) return;
    started = true;
    buildUi();
    if ('IntersectionObserver' in window) {
      visibilityObserver = new IntersectionObserver(entries => {
        if (!active()) return;
        for (const entry of entries) if (entry.isIntersecting) {
          visibilityObserver.unobserve(entry.target); inspect(entry.target);
        }
      }, { rootMargin: '360px 0px' });
    }
    scan(document, 500);
    observer = new MutationObserver(changes => {
      if (!active()) return;
      const now = Date.now();
      if (now - windowStart > 1000) { windowStart = now; mutationCount = 0; }
      mutationCount += changes.length;
      if (mutationCount > 120) { cooldownUntil = now + 4500; return; }
      let visited = 0;
      for (const change of changes) for (const e of change.addedNodes) {
        if (e.nodeType === 1 && !unsafe(e)) {
          scan(e, 30);
          if (++visited > 20) return;
        }
      }
    });
    observer.observe(document.documentElement, { subtree: true, childList: true });
    note('轻量翻译已就绪');
  }
  // 限制初始化时机：先让站点完成首屏加载，不抢占 React/Vue 挂载阶段。
  async function ready() {
    if (document.readyState !== 'complete') {
      await Promise.race([
        new Promise(resolve => window.addEventListener('load', resolve, { once: true })),
        new Promise(resolve => setTimeout(resolve, 9000))
      ]);
    }
    await new Promise(resolve => setTimeout(resolve, 900));
    start();
  }
  if (typeof GM_registerMenuCommand === 'function') {
    GM_registerMenuCommand('浮译 Lite：显示/隐藏悬浮球', () => updateConfig({ visible: !config.visible }));
    GM_registerMenuCommand('浮译 Lite：切换自动翻译', () => updateConfig({ auto: !config.auto }, true));
    GM_registerMenuCommand('浮译 Lite：恢复原文', restore);
  }
  window.addEventListener('keydown', e => {
    if (e.altKey && e.shiftKey && e.key.toLowerCase() === 't') updateConfig({ visible: !config.visible });
  });
  void ready().catch(e => { console.warn('[浮译 Lite]', e); note('加载失败：' + e.message); });
})();