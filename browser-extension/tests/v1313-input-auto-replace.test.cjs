const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const base = path.resolve(__dirname, "..");
const read = rel => fs.readFileSync(path.join(base, rel), "utf8");
const content = read("content/v02-enhancements.js");
const popup = read("popup/popup.js");
const html = read("popup/popup.html");
const floating = read("content/floating-panel.js");
const manifest = JSON.parse(read("manifest.json"));
const firefox = JSON.parse(read("compat/firefox/manifest.json"));
assert.equal(manifest.version, "1.3.14");
assert.equal(firefox.version, "1.3.14");

for (const id of ["inputAutoReplace", "inputAutoReplaceDelay", "siteInputAutoReplace"]) {
  assert.match(html, new RegExp('id="' + id + '"'));
  assert.ok(popup.includes('"' + id + '"'), id + " missing popup handler");
}
assert.match(floating, /id="inputAutoReplace"/);
assert.match(floating, /saveSync\(\{ inputAutoReplace: event\.target\.checked \}\)/);
assert.match(content, /inputAutoReplace: false/);
assert.match(content, /function canAutoReplace/);
assert.match(content, /function showAutoReplaceUndo/);
assert.match(content, /撤销自动替换/);
assert.match(content, /readEditable\(anchor\)\.trim\(\) === translated\.trim\(\)/);
assert.match(content, /lastTrustedInputAt/);
assert.match(content, /event\.isTrusted/);
assert.match(content, /compositionstart/);
assert.match(content, /compositionend/);
assert.match(content, /record\.lastAutoReplaceText === text/);
// The original replace-input button must remain functional.
assert.match(content, /replace\.textContent = "替换输入框"/);

const start = content.indexOf("  function autoReplaceEnabled() {");
const end = content.indexOf("\n  function showAutoReplaceUndo(", start);
assert.ok(start >= 0 && end > start);
class Input { constructor(type = "text") { this.type = type; this.isConnected = true; } }
const state = {
  settings: {
    enabled: true,
    chatMode: true,
    inputPreview: true,
    inputAutoReplace: false,
    siteInputAutoReplace: {}
  },
  composingInputs: new WeakSet(),
  mutedInputs: new WeakSet()
};
const ctx = { state, location: { hostname: "example.com" }, HTMLInputElement: Input,
  isEditable: () => true, isExcluded: () => false, Date };
vm.runInNewContext(content.slice(start, end), ctx);
const input = new Input();
const text = "你好世界";
const rec = { lastTrustedText: text, lastTrustedInputAt: Date.now() };
assert.equal(ctx.autoReplaceEnabled(), false, "Off by default");
assert.equal(ctx.canAutoReplace(input, rec, text), false, "Never replace unless opted-in");

state.settings.siteInputAutoReplace["example.com"] = "on";
assert.equal(ctx.autoReplaceEnabled(), true, "Per-site on overrides global off");
assert.equal(ctx.canAutoReplace(input, rec, text), true);
assert.equal(ctx.canAutoReplace(input, rec, "你好"), false, "Must not replace stale user edits");
state.composingInputs.add(input);
assert.equal(ctx.canAutoReplace(input, rec, text), false, "Never replace during IME composition");
state.composingInputs.delete(input);
rec.lastTrustedInputAt = Date.now() - 20000;
assert.equal(ctx.canAutoReplace(input, rec, text), false, "Old input is no longer trusted for auto-replace");
rec.lastTrustedInputAt = Date.now();
assert.equal(ctx.canAutoReplace(new Input("email"), rec, text), false, "Email must not auto-translate");
state.settings.siteInputAutoReplace["example.com"] = "off";
state.settings.inputAutoReplace = true;
assert.equal(ctx.autoReplaceEnabled(), false, "Per-site off overrides global on");
delete state.settings.siteInputAutoReplace["example.com"];
assert.equal(ctx.autoReplaceEnabled(), true, "Global on applies when site follows global");
state.settings.inputPreview = false;
assert.equal(ctx.autoReplaceEnabled(), false, "Respect disabled input preview");
console.log("v1.3.14 opt-in safe input auto-replace tests passed");