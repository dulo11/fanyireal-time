const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));

assert.equal(manifest.version, '0.7.1');
assert.equal(manifest.background.service_worker, 'background/main.js');

const scripts = manifest.content_scripts?.[0]?.js || [];
assert.deepEqual(scripts.slice(0, 4), [
  'shared/language-core.js',
  'content/content.js',
  'content/v02-enhancements.js',
  'content/mixed-page-guard.js'
]);

for (const rel of scripts) {
  assert.equal(fs.existsSync(path.join(root, rel)), true, `${rel} must exist`);
}

const backgroundMain = read('background/main.js');
assert.match(backgroundMain, /service-worker\.js/);
assert.match(backgroundMain, /cache-stats\.js/);
assert.match(backgroundMain, /runtime-extras\.js/);

const runtimeExtras = read('background/runtime-extras.js');
assert.match(runtimeExtras, /FT_FORCE_PAGE_TRANSLATION/);
assert.match(runtimeExtras, /FT_DIAGNOSTICS/);

const cacheStats = read('background/cache-stats.js');
assert.match(cacheStats, /FT_CACHE_STATS/);

const content = read('content/content.js');
assert.match(content, /retryFailedEntries/);
assert.match(content, /scheduleSafetyScan/);
assert.match(content, /ft-route-change/);

const enhancer = read('content/v02-enhancements.js');
assert.match(enhancer, /inputStates:\s*new WeakMap\(\)/);
assert.match(enhancer, /whatsapp/);
assert.match(enhancer, /telegram/);
assert.match(enhancer, /discord/);

const mixedGuard = read('content/mixed-page-guard.js');
assert.match(mixedGuard, /mixed-language-page/);
assert.match(mixedGuard, /FT_FORCE_PAGE_TRANSLATION/);

console.log('runtime hardening tests passed');
