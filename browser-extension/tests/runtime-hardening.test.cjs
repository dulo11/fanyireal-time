const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));

assert.equal(manifest.version, '0.9.0');
assert.equal(manifest.background.service_worker, 'background/main.js');

const scripts = manifest.content_scripts?.[0]?.js || [];
assert.deepEqual(scripts.slice(0, 6), [
  'shared/language-core.js',
  'content/site-exclusions.js',
  'content/content.js',
  'content/v02-enhancements.js',
  'content/mixed-page-guard.js',
  'content/attribute-translator.js'
]);

for (const rel of scripts) {
  assert.equal(fs.existsSync(path.join(root, rel)), true, `${rel} must exist`);
}

const backgroundMain = read('background/main.js');
assert.match(backgroundMain, /glossary-core\.js/);
assert.match(backgroundMain, /service-worker\.js/);
assert.match(backgroundMain, /glossary-runtime\.js/);
assert.match(backgroundMain, /cache-stats\.js/);
assert.match(backgroundMain, /runtime-extras\.js/);

const glossaryRuntime = read('background/glossary-runtime.js');
assert.match(glossaryRuntime, /glossaryEntries/);
assert.match(glossaryRuntime, /wrappedTranslateBatch/);
assert.match(glossaryRuntime, /globalThis\.translateBatch/);

const serviceWorker = read('background/service-worker.js');
assert.match(serviceWorker, /FT_TRANSLATE_DETAILED/);
assert.match(serviceWorker, /FT_USAGE_STATS/);
assert.match(serviceWorker, /FT_PRUNE_CACHE/);
assert.match(serviceWorker, /cacheMaxEntries/);
assert.match(serviceWorker, /cacheTtlDays/);
assert.match(serviceWorker, /recordUsage/);

const runtimeExtras = read('background/runtime-extras.js');
assert.match(runtimeExtras, /FT_FORCE_PAGE_TRANSLATION/);
assert.match(runtimeExtras, /FT_DIAGNOSTICS/);

const cacheStats = read('background/cache-stats.js');
assert.match(cacheStats, /FT_CACHE_STATS/);

const content = read('content/content.js');
assert.match(content, /retryFailedEntries/);
assert.match(content, /FT_SET_PAUSED/);
assert.match(content, /FT_TRANSLATE_DETAILED/);
assert.match(content, /protectedRestores/);
assert.match(content, /restoreProtectedNode/);
assert.match(content, /FTSiteExclusions/);
assert.match(content, /ft-exclusions-changed/);
assert.match(content, /scheduleSafetyScan/);
assert.match(content, /ft-route-change/);

const exclusions = read('content/site-exclusions.js');
assert.match(exclusions, /FT_PICK_EXCLUSION/);
assert.match(exclusions, /FT_GET_EXCLUSIONS/);
assert.match(exclusions, /FT_CLEAR_SITE_EXCLUSIONS/);
assert.match(exclusions, /selectorFor/);
assert.match(exclusions, /data-ft-no-translate/);

const attributes = read('content/attribute-translator.js');
assert.match(attributes, /placeholder/);
assert.match(attributes, /aria-label/);
assert.match(attributes, /FT_TRANSLATE_DETAILED/);
assert.match(attributes, /FT_SET_PAUSED/);
assert.match(attributes, /FTSiteExclusions/);
assert.match(attributes, /current === record\.original/);

const enhancer = read('content/v02-enhancements.js');
assert.match(enhancer, /inputStates:\s*new WeakMap\(\)/);
assert.match(enhancer, /mutedInputs:\s*new WeakSet\(\)/);
assert.match(enhancer, /swapInputLanguages/);
assert.match(enhancer, /仅复制译文/);
assert.match(enhancer, /本次输入暂停预览/);
assert.match(enhancer, /shadowProtectedRestores/);
assert.match(enhancer, /FTSiteExclusions/);
assert.match(enhancer, /whatsapp/);
assert.match(enhancer, /telegram/);
assert.match(enhancer, /discord/);

const mixedGuard = read('content/mixed-page-guard.js');
assert.match(mixedGuard, /mixed-language-page/);
assert.match(mixedGuard, /FT_FORCE_PAGE_TRANSLATION/);

const popup = read('popup/popup.js');
assert.match(popup, /pauseResume/);
assert.match(popup, /FT_SET_PAUSED/);
assert.match(popup, /FT_PICK_EXCLUSION/);
assert.match(popup, /FT_CLEAR_SITE_EXCLUSIONS/);
assert.match(popup, /swapInputLang/);
assert.match(popup, /protectedRestores/);

const options = read('options/options.js');
assert.match(options, /FT_USAGE_STATS/);
assert.match(options, /cacheMaxEntries/);
assert.match(options, /cacheTtlDays/);
assert.match(options, /glossaryEntries/);
assert.match(options, /parseGlossaryText/);

console.log('runtime hardening tests passed');
