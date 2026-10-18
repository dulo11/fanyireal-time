const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const read = name => fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
class Element {
  constructor() { this.style = {}; this.dataset = {}; this.listeners = {}; this.children = []; this.classes = new Set(); this.classList = { toggle: (n, on) => on ? this.classes.add(n) : this.classes.delete(n) }; }
  addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
  async emit(name, e = {}) { for (const fn of this.listeners[name] || []) await fn({ target: this, preventDefault() {}, stopPropagation() {}, ...e }); }
  appendChild(node) { this.children.push(node); }
  setAttribute() {}
  getBoundingClientRect() { return { left: 20, top: 500, right: 72, bottom: 552, width: 300, height: 400 }; }
  attachShadow() { return this.shadowRoot = makeRoot(); }
}
function makeRoot() { const els = new Map(); return { getElementById: id => { if (!els.has(id)) els.set(id, new Element()); return els.get(id); } }; }
function context() {
  const root = makeRoot();
  const win = new Element(); win.top = win;
  const document = { ...root, documentElement: new Element(), createElement: () => new Element() };
  let opened = 0;
  const chrome = {
    runtime: { sendMessage: async msg => { if (msg.type === 'FT_OPEN_OPTIONS') opened++; return { ok: true }; }, openOptionsPage: async () => { opened++; } },
    storage: { sync: { get: async d => d, set: async () => {} }, local: { get: async d => d, set: async () => {} }, onChanged: { addListener() {} } },
    tabs: { query: async () => [{ id: 1, url: 'https://example.com/' }], sendMessage: async () => ({ ok: true }) }
  };
  win.dispatchEvent = () => {};
  const ctx = { window: win, document, chrome, innerWidth: 390, innerHeight: 844, URL, console, Date, CustomEvent: class {}, requestAnimationFrame: fn => fn(), setTimeout: () => 1, setInterval: () => 1, clearTimeout() {} };
  return { ctx, document, chrome, opened: () => opened };
}
(async () => {
  const t = context();
  vm.runInNewContext(read('content/floating-panel.js'), t.ctx);
  const root = t.document.documentElement.children[0].shadowRoot;
  const fab = root.getElementById('fab'), panel = root.getElementById('panel');
  await fab.emit('click'); assert.equal(panel.style.display, 'block');
  await root.getElementById('close').emit('click'); assert.equal(panel.style.display, 'none');
  await fab.emit('click'); assert.equal(panel.style.display, 'block');
  await root.getElementById('route').emit('click'); assert.equal(t.opened(), 1);
  assert.equal(panel.style.display, 'none');
  // Old touch-only browser emits a compatibility click after touchend.
  await fab.emit('touchstart', { touches: [{ clientX: 20, clientY: 20 }] });
  await fab.emit('touchend', { changedTouches: [{ clientX: 20, clientY: 20 }] });
  await fab.emit('click'); assert.equal(panel.style.display, 'block');
  // 安卓 Chromium: 隐藏悬浮球 -> 在插件菜单重新显示 -> 再次轻触。
  // 即使浏览器支持 PointerEvent 但不生成合成 click，也必须打开小面板。
  const recovery = context();
  const localData = {};
  const storageListeners = [];
  recovery.ctx.window.PointerEvent = class {};
  recovery.chrome.storage.onChanged.addListener = fn => storageListeners.push(fn);
  recovery.chrome.storage.local.get = async defaults => ({ ...defaults, ...localData });
  recovery.chrome.storage.local.set = async patch => {
    const changes = Object.fromEntries(Object.entries(patch).map(([k, v]) => [k, { oldValue: localData[k], newValue: v }]));
    Object.assign(localData, patch);
    for (const notify of storageListeners) notify(changes, 'local');
  };
  vm.runInNewContext(read('content/floating-panel.js'), recovery.ctx);
  const recoveryRoot = recovery.document.documentElement.children[0].shadowRoot;
  const recoveryFab = recoveryRoot.getElementById('fab');
  const recoveryPanel = recoveryRoot.getElementById('panel');
  await recoveryRoot.getElementById('hide').emit('click');
  assert.equal(recoveryFab.style.display, 'none', 'hide should hide the floating icon');
  await recovery.chrome.storage.local.set({ floatingPanelVisibleV1: true });
  assert.equal(recoveryFab.style.display, 'grid', 'restore should show floating icon');
  const tap = { identifier: 9, clientX: 30, clientY: 300 };
  await recoveryFab.emit('touchstart', { touches: [tap], changedTouches: [tap] });
  await recoveryFab.emit('touchend', { changedTouches: [tap] });
  assert.equal(recoveryPanel.style.display, 'block', 'tap after restore must open panel without native click');
  await recoveryFab.emit('click');
  assert.equal(recoveryPanel.style.display, 'block', 'synthetic click must not instantly close reopened panel');

  const p = context();
  p.chrome.storage.sync.get = () => new Promise(() => {});
  vm.runInNewContext(read('popup/popup.js'), p.ctx);
  await p.document.getElementById('openOptions').emit('click');
  assert.equal(p.opened(), 1, 'settings must open while initial settings load is stuck');
  console.log('mobile UI event regression tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
