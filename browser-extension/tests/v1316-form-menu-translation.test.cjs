const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const read = f => fs.readFileSync(path.join(__dirname, "..", f), "utf8");
const core = read("content/form-menu-translator.js");
const popup = read("popup/popup.js"), popupHtml = read("popup/popup.html");
const floating = read("content/floating-panel.js");
const chat = read("content/v02-enhancements.js");
const chrom = JSON.parse(read("manifest.json"));
const firefox = JSON.parse(read("compat/firefox/manifest.json"));

for (const m of [chrom, firefox]) {
  assert.equal(m.version, "1.3.16");
  assert.ok(m.content_scripts[0].js.includes("content/form-menu-translator.js"));
  assert.equal(m.content_scripts[0].all_frames, false);
}
for (const id of ["formMenuEnabled", "formMenuMode"]) {
  assert.ok(popupHtml.includes('id="' + id + '"'));
  assert.ok(popup.includes('"' + id + '"'));
  assert.ok(floating.includes('id="' + id + '"'));
}
assert.match(chat, /shadowHost\?\.closest/);
assert.match(core, /formMenuEnabled: true/);
assert.match(core, /formMenuMode: "bilingual"/);
assert.match(core, /"translated">仅中文/);
assert.match(core, /"bilingual">英文＋中文/);
assert.match(core, /"FT_TRANSLATE_DETAILED"/);
assert.match(core, /kind: "custom"/);
assert.match(core, /kind: "native"/);
assert.match(core, /"中译选项"/);
assert.match(core, /selectedIndex = index/);
assert.match(core, /dispatchEvent\(new Event\("change"/);
assert.ok(!core.includes("option.textContent ="), "Must not rewrite original option label");
assert.ok(!core.includes("option.value ="), "Must not rewrite submitted option values");
assert.ok(!core.includes("input.value ="), "Must not rewrite user inputs");

function extract(from, to) {
  const a = core.indexOf("  function " + from);
  const b = core.indexOf("  function " + to, a + 1);
  assert.ok(a > 0 && b > a, "Source helper: " + from);
  return core.slice(a, b);
}

const selected = [];
const first = { text: "Individual", value: "real-person", disabled: false };
const second = { text: "Company", value: "legal-entity", disabled: false };
const native = {
  isConnected: true,
  disabled: false,
  options: [first, second],
  selectedIndex: 0,
  focus() {},
  dispatchEvent(event) { selected.push(event.type); }
};
const sandbox = { Array, Event: class { constructor(type, options) { this.type = type; this.bubbles = options?.bubbles; } } };
vm.createContext(sandbox);
vm.runInContext(extract("selectNative(", "renderRows("), sandbox);
sandbox.selectNative(native, second);
assert.equal(native.selectedIndex, 1);
assert.deepEqual(selected, ["input", "change"]);
assert.equal(second.value, "legal-entity", "A translated choice MUST retain original submitted value");
sandbox.selectNative(native, { text: "Missing", value: "evil" });
assert.equal(native.selectedIndex, 1, "Unknown option must not be added");

const cache = new Map();
const calls = [];
const translateCtx = {
  settings: { sourceLang: "auto", targetLang: "zh-CN" }, cache,
  chrome: { runtime: { sendMessage: async req => {
    calls.push(req);
    return { ok: true, translations: req.texts.map(text => "译：" + text) };
  }} },
  Promise, Error, setTimeout, Map, String
};
vm.createContext(translateCtx);
const begin = core.indexOf("  async function translateTexts(");
const end = core.indexOf("  function selectNative(", begin);
assert.ok(begin >= 0 && end > begin);
vm.runInContext(core.slice(begin, end), translateCtx);
(async () => {
  const one = await translateCtx.translateTexts(["Individual", "Company"], "zh-CN");
  assert.deepEqual(Array.from(one), ["译：Individual", "译：Company"]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].type, "FT_TRANSLATE_DETAILED");
  assert.equal(calls[0].options.targetLang, "zh-CN");
  await translateCtx.translateTexts(["Individual", "Company"], "zh-CN");
  assert.equal(calls.length, 1, "Repeated options should not call API");
  await translateCtx.translateTexts(["Individual"], "vi");
  assert.equal(calls.length, 2, "Different target language must have distinct cache");
  console.log("v1.3.16 bilingual dynamic and native select feature tests passed");
})().catch(e => { console.error(e); process.exitCode = 1; });