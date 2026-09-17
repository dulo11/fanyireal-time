const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));

assert.equal(manifest.version, '1.0.0');
assert.equal(manifest.background.service_worker, 'background/main.js');

const scripts = manifest.content_scripts?.[0]?.js || [];
assert.deepEqual(scripts.slice(0, 7), [
  'shared/language-core.js',
  'content/site-exclusions.js',
  'content/site-input-profile.js',
  'content/content.js',
  'content/v02-enhancements.js',
  'content/mixed-page-guard.js',
  'content/attribute-translator.js'
]);
for (const rel of scripts) assert.equal(fs.existsSync(path.join(root, rel)), true, `${rel} must exist`);

const backgroundMain = read('background/main.js');
assert.match(backgroundMain, /glossary-core\.js/);
assert.match(backgroundMain, /service-worker\.js/);
assert.match(backgroundMain, /glossary-runtime\.js/);
assert.match(backgroundMain, /runtime-telemetry\.js/);
assert.match(backgroundMain, /cache-stats\.js/);
assert.match(backgroundMain, /runtime-extras\.js/);

const telemetry = read('background/runtime-telemetry.js');
assert.match(telemetry, /translationRuntimeStateV1/);
assert.match(telemetry, /actualRoute/);
assert.match(telemetry, /google-fallback/);
assert.match(telemetry, /globalThis\.translateBatch/);

const runtimeExtras = read('background/runtime-extras.js');
assert.match(runtimeExtras, /FT_DIAGNOSTICS/);
assert.match(runtimeExtras, /lastRuntime/);
assert.match(runtimeExtras, /FT_FORCE_PAGE_TRANSLATION/);

const siteProfile = read('content/site-input-profile.js');
assert.match(siteProfile, /siteInputLanguagesV1/);
assert.match(siteProfile, /inputSourceLang/);
assert.match(siteProfile, /inputTargetLang/);

const content = read('content/content.js');
assert.match(content, /retryFailedEntries/);
assert.match(content, /FT_SET_PAUSED/);
assert.match(content, /FT_TRANSLATE_DETAILED/);
assert.match(content, /protectedRestores/);
assert.match(content, /FTSiteExclusions/);
assert.match(content, /scheduleSafetyScan/);

const exclusions = read('content/site-exclusions.js');
assert.match(exclusions, /FT_PICK_EXCLUSION/);
assert.match(exclusions, /FT_GET_EXCLUSIONS/);
assert.match(exclusions, /FT_CLEAR_SITE_EXCLUSIONS/);
assert.match(exclusions, /selectorFor/);

const attributes = read('content/attribute-translator.js');
assert.match(attributes, /placeholder/);
assert.match(attributes, /aria-label/);
assert.match(attributes, /FT_TRANSLATE_DETAILED/);
assert.match(attributes, /FTSiteExclusions/);
assert.match(attributes, /current === record\.original/);

const enhancer = read('content/v02-enhancements.js');
assert.match(enhancer, /inputStates:\s*new WeakMap\(\)/);
assert.match(enhancer, /mutedInputs:\s*new WeakSet\(\)/);
assert.match(enhancer, /swapInputLanguages/);
assert.match(enhancer, /仅复制译文/);
assert.match(enhancer, /本次输入暂停预览/);
assert.match(enhancer, /shadowProtectedRestores/);
assert.match(enhancer, /whatsapp/);
assert.match(enhancer, /telegram/);
assert.match(enhancer, /discord/);

const popup = read('popup/popup.js');
assert.match(popup, /siteInputLanguagesV1/);
assert.match(popup, /saveSiteInputProfile/);
assert.match(popup, /runtimeRoute/);
assert.match(popup, /FT_DIAGNOSTICS/);
assert.match(popup, /FT_PICK_EXCLUSION/);

const options = read('options/options.js');
assert.match(options, /floating-translator-settings/);
assert.match(options, /exportSettings/);
assert.match(options, /importSettingsFile/);
assert.match(options, /copyDiagnostics/);
assert.match(options, /includeAzureKey/);
assert.match(options, /siteInputLanguagesV1/);
assert.match(options, /glossaryEntries/);
assert.match(options, /FT_USAGE_STATS/);

console.log('runtime hardening tests passed');