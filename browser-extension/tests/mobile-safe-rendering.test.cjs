const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const read = file => fs.readFileSync(path.join(root, file), "utf8");
const core = read("content/content.js");
const attributes = read("content/attribute-translator.js");
const shadow = read("content/v02-enhancements.js");
const popup = read("popup/popup.js");
const html = read("popup/popup.html");

for (const f of ["manifest.json", "compat/firefox/manifest.json"]) {
  const manifest = JSON.parse(read(f));
  assert.equal(manifest.version, "1.3.15");
  assert.equal(manifest.content_scripts[0].all_frames, false, "Mobile must not inject 11 scripts into every iframe");
  assert.equal(manifest.content_scripts[0].match_about_blank, false);
}
assert.match(core, /performanceSafeMode: true/);
assert.match(popup, /performanceSafeMode: true/);
assert.match(popup, /saveSync\(\{ performanceSafeMode: event\.target\.checked \}\)/);
assert.match(html, /id="performanceSafeMode"/);
assert.match(attributes, /settings\.performanceSafeMode !== false/);
assert.match(shadow, /state\.settings\.performanceSafeMode !== false/);
assert.match(core, /state\.settings\.performanceSafeMode !== false\) return false;/);
assert.match(core, /state\.safeMutationRoots\.size < 8/);
assert.match(core, /state\.safeMutationTimer = setTimeout/);
assert.match(core, /mutation\.type !== "childList"/);

const start = core.indexOf("  function handleMutations(mutations) {");
const end = core.indexOf("\n  function startObserver()", start);
assert.ok(start >= 0 && end > start);
const func = core.slice(start, end);

const state = {
  active: true,
  paused: false,
  settings: { performanceSafeMode: true },
  safeMutationTimer: null,
  safeMutationRoots: new Set(),
  safeMutationLastFlush: 0
};
const processed = [];
const timers = [];
const ctx = {
  state, Date, Math, Array, Set,
  Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
  SKIP_SELECTOR: "script,style",
  setTimeout: (fn, delay) => { timers.push({ fn, delay }); return timers.length; },
  scheduleTreeScan: root => processed.push(root)
};
vm.runInNewContext(func, ctx);

// Chromium 页面中的 characterData 变化必须让网站控制，不主动抢写。
ctx.handleMutations([{ type: "characterData", target: { nodeType: 3, nodeValue: "hello" } }]);
assert.equal(processed.length, 0);
assert.equal(timers.length, 0);

// 批量添加一个主内容节点，应延迟收集并增量扫描，且最多 8 个子树。
const nodes = Array.from({ length: 20 }, (_, n) => ({
  nodeType: 1,
  dataset: {},
  matches: () => n === 0 // skip extension-owned / script subtree
}));
ctx.handleMutations([{ type: "childList", addedNodes: nodes }]);
assert.equal(processed.length, 0, "Never scan synchronous inside MutationObserver callback");
assert.equal(timers.length, 1, "Changes must be batched into one timer");
assert.equal(state.safeMutationRoots.size, 8, "Only retain up to 8 new roots");
assert.ok(timers[0].delay >= 1000);
timers[0].fn();
assert.equal(processed.length, 8);
assert.equal(state.safeMutationRoots.size, 0);
assert.equal(state.safeMutationTimer, null);

console.log("mobile safe rendering and mutation throttling tests passed");
