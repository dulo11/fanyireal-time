const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

const manifest = JSON.parse(read('manifest.json'));
assert.match(manifest.version, /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/);
assert.equal(manifest.name, '浮译');

const optionHtml = read('options/options.html');
for (const id of ['glossaryEnabled', 'glossaryCaseSensitive', 'glossaryText', 'glossaryStatus', 'copyDiagnostics', 'exportSettings', 'importSettings', 'includeAzureKey']) {
  assert.match(optionHtml, new RegExp(`id=["']${id}["']`));
}

const popupHtml = read('popup/popup.html');
for (const id of ['swapInputLang', 'pickExclusion', 'clearExclusions', 'exclusionCount', 'runtimeRoute']) {
  assert.match(popupHtml, new RegExp(`id=["']${id}["']`));
}

const main = read('background/main.js');
const order = [
  '../shared/crypto-lite.js',
  '../shared/glossary-core.js',
  'service-worker.js',
  'provider-pool.js',
  'glossary-runtime.js',
  'runtime-telemetry.js',
  'cache-stats.js',
  'runtime-extras.js'
];
let previous = -1;
for (const item of order) {
  const index = main.indexOf(item);
  assert.ok(index > previous, `${item} must load in the expected order`);
  previous = index;
}

const content = read('content/content.js');
assert.match(content, /current === previous\.originalFull/);
assert.match(content, /restoreProtectedNode/);

const attributes = read('content/attribute-translator.js');
assert.match(attributes, /current === record\.original/);
assert.match(attributes, /record\.rendered/);

const siteInput = read('content/site-input-profile.js');
assert.match(siteInput, /siteInputLanguagesV1/);
assert.match(siteInput, /chrome\.storage\.sync\.set/);

console.log('current inherited feature contract tests passed');
