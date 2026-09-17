const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));
const firefox = JSON.parse(read('compat/firefox/manifest.json'));

for (const m of [manifest, firefox]) {
  const scripts = m.content_scripts?.[0]?.js || [];
  assert.ok(scripts.includes('content/floating-panel-bridge.js'));
  assert.ok(scripts.includes('content/floating-panel.js'));
  assert.ok(scripts.indexOf('content/floating-panel-bridge.js') < scripts.indexOf('content/floating-panel.js'));
}

const panel = read('content/floating-panel.js');
for (const token of ['ft-floating-panel-host', '自动持续翻译网页', '翻译整页', '补扫遗漏', '恢复原文', '打开完整设置', 'pointermove', 'floatingPanelPositionV1']) {
  assert.match(panel, new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
}
assert.match(panel, /attachShadow\(\{ mode: ["']open["'] \}\)/);
assert.match(panel, /window\.top !== window/);

const bridge = read('content/floating-panel-bridge.js');
assert.match(bridge, /FT_FLOATING_PAGE_ACTION/);
assert.match(bridge, /ft-floating-state/);

const runtime = read('background/runtime-extras.js');
for (const token of ['FT_FLOATING_PAGE_ACTION', 'pause-toggle', 'FT_GET_PAGE_STATE', 'FT_SET_PAUSED', 'FT_TRANSLATE_NOW', 'FT_RESCAN_PAGE', 'FT_RESTORE_PAGE']) {
  assert.match(runtime, new RegExp(token));
}

console.log('floating panel tests passed');