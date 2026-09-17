const assert = require('node:assert/strict');

require('../shared/language-core.js');
const FT = globalThis.FTLanguage;

assert.ok(FT, 'FTLanguage should be exported on globalThis');
assert.equal(FT.detect('Hello, how are you today?').lang, 'latin');
assert.notEqual(FT.detect('Hello, how are you today?').lang, 'ja');
assert.equal(FT.detect('これは日本語です').lang, 'ja');
assert.equal(FT.detect('안녕하세요').lang, 'ko');
assert.equal(FT.detect('สวัสดีครับ').lang, 'th');
assert.equal(FT.detect('Привет мир').lang, 'ru');
assert.equal(FT.detect('你好世界').lang, 'han');
assert.equal(FT.detect('你好世界').ambiguous, true);

assert.equal(FT.isLatinScriptLanguage('en'), true);
assert.equal(FT.isLatinScriptLanguage('fr-FR'), true);
assert.equal(FT.isLatinScriptLanguage('ja'), false);
assert.equal(FT.isLatinScriptLanguage('zh-CN'), false);

assert.equal(
  FT.shouldTranslateText('Hello world', { sourceLang: 'auto', targetLang: 'ja', pageLang: 'en' }),
  true,
  'English must never be skipped as Japanese'
);
assert.equal(
  FT.shouldTranslateText('Hello world', { sourceLang: 'auto', targetLang: 'ja', pageLang: 'ja' }),
  true,
  'English on a Japanese page must still translate to Japanese'
);
assert.equal(
  FT.shouldTranslateText('Hello world', { sourceLang: 'auto', targetLang: 'zh-CN', pageLang: 'zh-CN' }),
  true,
  'English on a Chinese page must still translate to Chinese'
);
assert.equal(
  FT.shouldTranslateText('Hello world', { sourceLang: 'auto', targetLang: 'en', pageLang: 'en' }),
  false,
  'Latin text may be skipped when both page and target are a Latin-script language'
);
assert.equal(
  FT.shouldTranslateText('これは日本語です', { sourceLang: 'auto', targetLang: 'ja', pageLang: 'ja' }),
  false,
  'confident Japanese should be skipped when target is Japanese'
);
assert.equal(
  FT.shouldTranslateText('東京駅', { sourceLang: 'auto', targetLang: 'ja', pageLang: 'ja' }),
  true,
  'Han-only text remains ambiguous instead of being forced to Japanese or Chinese'
);
assert.equal(
  FT.shouldTranslateText('中文内容', { sourceLang: 'auto', targetLang: 'zh-CN', pageLang: 'zh-CN' }),
  false,
  'Han text on a Chinese page may be skipped for a Chinese target'
);
assert.equal(
  FT.shouldTranslateText('Hello', { sourceLang: 'en', targetLang: 'en', pageLang: '' }),
  false,
  'manual source equal to target should not translate'
);

console.log('language-core tests passed');