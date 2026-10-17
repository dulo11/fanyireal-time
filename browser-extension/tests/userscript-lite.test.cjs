const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../userscript/Fuyi-Lite.user.js'), 'utf8');

assert.match(source, /@grant\s+GM_xmlhttpRequest/);
assert.match(source, /@grant\s+GM_getValue/);
assert.match(source, /@grant\s+GM_setValue/);
assert.match(source, /@grant\s+GM_registerMenuCommand/);
assert.match(source, /@connect\s+translate\.googleapis\.com/);
assert.match(source, /@run-at\s+document-idle/);
assert.match(source, /@updateURL/);
assert.doesNotMatch(source, /chrome\.runtime|browser\.runtime|document\.write/);
for (const code of ['bilingual', 'translated', 'cooldownUntil', 'IntersectionObserver',
  'MutationObserver', 'setPointerCapture', 'touchstart', 'touchmove',
  'restore', 'exclude', 'visible', 'size', 'opacity', 'snap']) {
  assert.ok(source.includes(code), 'Missing feature ' + code);
}
new vm.Script(source, { filename: 'Fuyi-Lite.user.js' });
let menu = {};
const w = { addEventListener() {} };
w.top = w;
const ctx = {
  window: w,
  document: { readyState: 'loading' },
  GM_getValue: () => ({}), GM_setValue() {},
  GM_registerMenuCommand: (label, action) => { menu[label] = action; },
  setTimeout() {},
  console,
  location: { hostname: 'example.com' }
};
vm.runInNewContext(source, ctx);
assert.ok(menu['浮译 Lite：显示/隐藏悬浮球'], 'Missing restore control when floating button is hidden');
assert.ok(menu['浮译 Lite：切换自动翻译']);
console.log('Fuyi Lite userscript contract and syntax passed');