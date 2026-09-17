const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));

assert.match(manifest.version, /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/);
assert.equal(manifest.name, '浮译');
assert.equal(manifest.background.service_worker, 'background/main.js');

const scripts = manifest.content_scripts?.[0]?.js || [];
assert.deepEqual(scripts.slice(0, 8), [
  'compat/browser-api.js',
  'shared/language-core.js',
  'content/site-exclusions.js',
  'content/site-input-profile.js',
  'content/content.js',
  'content/v02-enhancements.js',
  'content/mixed-page-guard.js',
  'content/attribute-translator.js'
]);
for (const rel of scripts) assert.equal(fs.existsSync(path.join(root, rel)), true, `${rel} must exist`);

const compat = read('compat/browser-api.js');
assert.match(compat, /moz-extension/);
assert.match(compat, /globalThis,\s*["']chrome["']/);
assert.match(compat, /__FT_BROWSER_FAMILY__/);

const backgroundMain = read('background/main.js');
assert.match(backgroundMain, /crypto-lite\.js/);
assert.match(backgroundMain, /glossary-core\.js/);
assert.match(backgroundMain, /service-worker\.js/);
assert.match(backgroundMain, /provider-pool\.js/);
assert.match(backgroundMain, /glossary-runtime\.js/);
assert.match(backgroundMain, /runtime-telemetry\.js/);
assert.match(backgroundMain, /cache-stats\.js/);
assert.match(backgroundMain, /runtime-extras\.js/);

const providerPool = read('background/provider-pool.js');
assert.match(providerPool, /providerPoolEnabled/);
assert.match(providerPool, /azureCredentials/);
assert.match(providerPool, /baiduCredentials/);
assert.match(providerPool, /aliyunCredentials/);
assert.match(providerPool, /providerCooldownMs/);
assert.match(providerPool, /FT_PROVIDER_POOL_STATUS/);
assert.match(providerPool, /api\.cognitive\.microsofttranslator\.com/);
assert.match(providerPool, /fanyi-api\.baidu\.com/);
assert.match(providerPool, /mt\.cn-hangzhou\.aliyuncs\.com/);
assert.match(providerPool, /HMAC-SHA1/);
assert.match(providerPool, /md5Hex/);

const telemetry = read('background/runtime-telemetry.js');
assert.match(telemetry, /translationRuntimeStateV1/);
assert.match(telemetry, /providerPoolLastRouteV1/);
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
assert.match(read('popup/popup.html'), /\.\.\/compat\/browser-api\.js/);

const options = read('options/options.js');
assert.match(options, /floating-translator-settings/);
assert.match(options, /exportSettings/);
assert.match(options, /importSettingsFile/);
assert.match(options, /copyDiagnostics/);
assert.match(options, /includeAzureKey/);
assert.match(options, /siteInputLanguagesV1/);
assert.match(options, /glossaryEntries/);
assert.match(options, /FT_USAGE_STATS/);

const poolUi = read('options/provider-pool-ui.js');
assert.match(poolUi, /savePool/);
assert.match(poolUi, /bindAdd/);
assert.match(poolUi, /FT_PROVIDER_POOL_STATUS/);
assert.match(poolUi, /providerCooldownMs/);
const optionHtml = read('options/options.html');
assert.match(optionHtml, /\.\.\/compat\/browser-api\.js/);
assert.match(optionHtml, /provider-pool-ui\.js/);
for (const id of ['addAzureCredential', 'addBaiduCredential', 'addAliyunCredential', 'saveProviderPool', 'providerPoolStatus']) {
  assert.match(optionHtml, new RegExp(`id=["']${id}["']`));
}

assert.match(manifest.key, /^[A-Za-z0-9+/]+=*$/);
assert.equal(manifest.update_url, 'https://raw.githubusercontent.com/dulo11/fanyireal-time/extension-update-channel/updates.xml');

console.log('runtime hardening tests passed');
