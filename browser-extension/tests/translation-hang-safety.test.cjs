const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const base = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(base, file), 'utf8');
const main = read('content/content.js');
const attr = read('content/attribute-translator.js');
const shadow = read('content/v02-enhancements.js');

assert.match(main, /batch\.length >= 8/);
assert.match(main, /chars \+ core\.length > 2400/);
assert.match(main, /state\.consecutiveBatchErrors >= 2/);
assert.match(main, /pauseForSafety\(reason\)/);
assert.match(main, /ft-translation-safety-pause/);
assert.match(attr, /record\.restoreAttempts <= 3/);
assert.match(shadow, /record\.restoreAttempts > 3/);
assert.match(attr, /ft-translation-safety-pause/);
assert.match(shadow, /ft-translation-safety-pause/);

function extract(text, start, end) {
  const s = text.indexOf(start);
  const e = text.indexOf(end, s + start.length);
  assert.ok(s >= 0 && e > s, 'Unable to extract test routine');
  return text.slice(s, e);
}

async function testTimeout() {
  let timerFn = null;
  let timerCleared = false;
  const ctx = {
    chrome: { runtime: { sendMessage: () => new Promise(() => {}) } },
    setTimeout: fn => { timerFn = fn; return 123; },
    clearTimeout: id => { if (id === 123) timerCleared = true; },
    Promise, Error
  };
  vm.runInNewContext(extract(main, '  async function sendTranslationWithDeadline(', '\n  async function processQueue()'), ctx);
  const waiting = ctx.sendTranslationWithDeadline({ type: 'FT_TRANSLATE_DETAILED' });
  assert.equal(typeof timerFn, 'function', 'Never-ending message must have a deadline');
  timerFn();
  await assert.rejects(waiting, /超过18秒/);
  assert.equal(timerCleared, true, 'Deadline timer must be cleared');
}

function testDomLoopProtection() {
  const state = {
    active: true, paused: false, protectedRestores: 0,
    settings: { displayMode: 'translated', performanceSafeMode: false }
  };
  const ctx = { state, isExcludedElement: () => false, effectiveDisplayMode: () => state.settings.displayMode, Date };
  vm.runInNewContext(extract(main, '  function restoreProtectedNode(', '\n  function enqueueNode('), ctx);
  const node = { isConnected: true, nodeValue: 'original', parentElement: {} };
  const record = { originalFull: 'original', renderedFull: 'translated' };
  for (let i = 0; i < 3; i++) {
    assert.equal(ctx.restoreProtectedNode(node, record), true);
    assert.equal(node.nodeValue, 'translated');
    node.nodeValue = 'original'; // site's framework restores the original
  }
  assert.equal(ctx.restoreProtectedNode(node, record), false, 'DOM rewrite loop should be broken');
  assert.equal(node.nodeValue, 'original', 'Site must retain control of its own DOM');
  // Default safe mode does not even attempt the first rewrite after framework restore.
  state.settings.performanceSafeMode = true;
  record.restoreAttempts = 0;
  assert.equal(ctx.restoreProtectedNode(node, record), false);
  assert.equal(node.nodeValue, 'original');
}

(async () => {
  await testTimeout();
  testDomLoopProtection();
  console.log('translation hang and DOM rewrite loop safety tests passed');
})().catch(err => { console.error(err); process.exitCode = 1; });
