const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

const html = read('popup/popup.html');
const js = read('popup/popup.js');

assert.match(html, /id=["']autoTranslate["']/);
assert.match(html, /关闭后不会再随网页自动翻译/);
assert.match(html, /id=["']fallbackGoogleQuick["']/);
assert.match(html, /Azure 失败时自动使用 Google Web 备用/);

assert.match(js, /LOCAL_DEFAULTS/);
assert.match(js, /fallbackGoogle:\s*true/);
assert.match(js, /fallbackGoogleQuick/);
assert.match(js, /chrome\.storage\.local\.set\(patch\)/);
assert.match(js, /saveLocal\(\{ fallbackGoogle: event\.target\.checked \}\)/);

console.log('v1.0 quick controls tests passed');
