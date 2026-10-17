const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const base = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(base, rel), 'utf8');
const manifest = JSON.parse(read('manifest.json'));
const firefox = JSON.parse(read('compat/firefox/manifest.json'));

for (const m of [manifest, firefox]) {
  const scripts = m.content_scripts?.[0]?.js || [];
  const gate = scripts.indexOf('content/load-guard.js');
  assert.ok(gate >= 0, 'Missing startup guard');
  for (const core of ['content/content.js', 'content/v02-enhancements.js', 'content/mixed-page-guard.js', 'content/attribute-translator.js']) {
    assert.ok(gate < scripts.indexOf(core), `Startup guard must load before ${core}`);
    assert.match(read(core), /FTSiteStartup\?\.wait/, `${core} must wait for safe startup`);
  }
}
const core = read('content/content.js');
assert.match(core, /state\.mutationWindowCount > 180/, 'Mutation storm protection must be present');
assert.match(core, /state\.queue\.size >= 1000/, 'Text queue must be bounded');
assert.match(core, /state\.scanTokens\.size >= \(state\.settings\.performanceSafeMode/, 'Concurrent tree scans must be bounded');
assert.match(core, /state\.flushTimer !== null/, 'Queue scheduling must not be delayed by every new node');

async function checkReadiness(initialState) {
  const listeners = new Map();
  const timers = [];
  const ctx = {
    document: { readyState: initialState },
    window: {
      addEventListener: (name, fn) => listeners.set(name, fn)
    },
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; }
  };
  vm.runInNewContext(read('content/load-guard.js'), ctx);
  let released = false;
  ctx.FTSiteStartup.wait().then(() => { released = true; });
  await Promise.resolve();
  assert.equal(released, false, 'Translation must not start immediately');
  if (initialState !== 'complete') {
    assert.equal(timers.length, 0, 'No auto translation timer before window load');
    assert.ok(listeners.has('load'));
    listeners.get('load')();
  }
  assert.equal(timers.length, 1);
  assert.ok(timers[0].ms >= 1000);
  timers[0].fn();
  await ctx.FTSiteStartup.wait();
  assert.equal(released, true, 'Translation should start after load and idle delay');
}

(async () => {
  await checkReadiness('loading');
  await checkReadiness('complete');
  console.log('browser page-load performance guard tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
