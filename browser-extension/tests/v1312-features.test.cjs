const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const read = x => fs.readFileSync(path.join(__dirname, "..", x), "utf8");
const manifest = JSON.parse(read("manifest.json"));
const firefox = JSON.parse(read("compat/firefox/manifest.json"));
const core = read("content/content.js");
const popup = read("popup/popup.js");
const html = read("popup/popup.html");
const bg = read("background/service-worker.js");
const pool = read("background/provider-pool.js");
const select = read("content/selection-tools.js");

for (const m of [manifest, firefox]) {
  assert.equal(m.version, "1.3.14");
  assert.ok(m.content_scripts[0].js.includes("content/selection-tools.js"));
  assert.equal(m.content_scripts[0].all_frames, false, "mobile background-safe injection stays top-level");
}

assert.match(core, /visiblePriority: true/);
assert.match(core, /function nearViewport\(node\)/);
assert.match(core, /deferredNodes: new Set\(\)/);
assert.match(core, /function updateVisibleQueue\(\)/);
assert.match(core, /document\.addEventListener|window\.addEventListener\("scroll"/);
assert.match(core, /state\.currentRequestId/);
assert.match(core, /cancelCurrentRequest\(\)/);
assert.match(core, /entry\.node\.nodeValue === entry\.originalFull/);
assert.match(core, /"FT_CLEAR_QUEUE"/);
assert.match(core, /PerformanceObserver/);
assert.match(core, /longTaskCount >= 6/);
assert.match(core, /siteDisplayModes/);
assert.match(core, /bilingualFontScale/);
assert.match(bg, /FT_CANCEL_TRANSLATION/);
assert.match(bg, /ftActiveTranslationJobs\.set/);
assert.match(bg, /__signal: controller\.signal/);
assert.match(bg, /init\.signal\.addEventListener\("abort"/);
assert.match(pool, /options\.__signal/);
assert.match(pool, /init\.signal\.addEventListener\("abort"/);
for (const id of ["visiblePriority", "siteDisplayMode", "bilingualColor", "bilingualFontScale", "inputQuickTranslate", "copyDiagnostics", "clearQueue", "pageDiagnostics"]) {
  assert.ok(html.includes('id="' + id + '"'), "Missing popup feature: " + id);
  assert.ok(popup.includes('"' + id + '"'), "Missing popup handler: " + id);
}
assert.match(select, /selectionchange/);
assert.match(select, /pointerup/);
assert.match(select, /inputQuickTranslate/);
assert.match(select, /translateSelection/);
assert.ok(!/skipChinese|autoSkipChinese/i.test(core), "Do not add requested-excluded auto Chinese skip switch");

const extract = (start, end) => {
  const a = core.indexOf(start), b = core.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, "Missing helper " + start);
  return core.slice(a, b);
};
const state = { settings: { visiblePriority: true, siteDisplayModes: { "example.org": "bilingual" }, displayMode: "translated" } };
const ctx = {
  state,
  location: { hostname: "example.org" },
  innerHeight: 700,
  Number,
  Array,
  String,
  Error
};
vm.runInNewContext(extract("  function effectiveDisplayMode()", "\n  function shouldTranslatePage()"), ctx);
assert.equal(ctx.effectiveDisplayMode(), "bilingual");
const visible = { parentElement: { getBoundingClientRect: () => ({ top: 400, bottom: 480 }) } };
const far = { parentElement: { getBoundingClientRect: () => ({ top: 10000, bottom: 10100 }) } };
assert.equal(ctx.nearViewport(visible), true);
assert.equal(ctx.nearViewport(far), false);
state.settings.visiblePriority = false;
assert.equal(ctx.nearViewport(far), true, "Users can turn off viewport-only scan");
console.log("v1.3.14 feature wiring and viewport queue tests passed");
