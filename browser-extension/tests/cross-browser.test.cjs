const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

const chromium = JSON.parse(read('manifest.json'));
const firefox = JSON.parse(read('compat/firefox/manifest.json'));

assert.match(chromium.version, /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/);
assert.equal(firefox.version, chromium.version);
assert.equal(chromium.name, '浮译');
assert.equal(firefox.name, chromium.name);
assert.equal(chromium.background.service_worker, 'background/main.js');
assert.ok(Array.isArray(firefox.background.scripts));
assert.equal(firefox.background.service_worker, undefined);
assert.equal(firefox.browser_specific_settings.gecko.id, 'floatingtranslator@966405.xyz');
assert.equal(firefox.browser_specific_settings.gecko.strict_min_version, '121.0');

const expectedFirefoxBackground = [
  'compat/browser-api.js',
  'shared/crypto-lite.js',
  'shared/glossary-core.js',
  'background/service-worker.js',
  'background/provider-pool.js',
  'background/glossary-runtime.js',
  'background/runtime-telemetry.js',
  'background/cache-stats.js',
  'background/runtime-extras.js'
];
assert.deepEqual(firefox.background.scripts, expectedFirefoxBackground);

for (const rel of firefox.background.scripts) {
  assert.ok(fs.existsSync(path.join(root, rel)), `Firefox background file missing: ${rel}`);
}
for (const block of firefox.content_scripts || []) {
  for (const rel of block.js || []) assert.ok(fs.existsSync(path.join(root, rel)), `Firefox content JS missing: ${rel}`);
  for (const rel of block.css || []) assert.ok(fs.existsSync(path.join(root, rel)), `Firefox content CSS missing: ${rel}`);
}

assert.equal(firefox.content_scripts[0].js[0], 'compat/browser-api.js');
assert.match(read('compat/browser-api.js'), /moz-extension/);
assert.match(read('popup/popup.html'), /compat\/browser-api\.js/);
assert.match(read('options/options.html'), /compat\/browser-api\.js/);
assert.match(read('options/options.html'), /provider-pool-ui\.js/);

console.log('cross-browser manifest tests passed');
