const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.join(__dirname, "..");
const read = name => fs.readFileSync(path.join(root, name), "utf8");
const plugin = read("background/provider-pool.js");
const ui = read("options/provider-pool-ui.js");
const html = read("options/options.html");
const manifest = JSON.parse(read("manifest.json"));
const firefox = JSON.parse(read("compat/firefox/manifest.json"));

assert.equal(manifest.version, "1.3.15");
assert.equal(firefox.version, "1.3.15");
assert.match(ui, /aiCredentials/);
assert.match(ui, /aiTranslationStyle/);
assert.match(html, /id="providerOrder5"/);
assert.match(html, /id="ai-openaiCredentials"/);
assert.match(html, /id="aiTranslationStyle"/);
assert.match(html, /id="addAi-openaiCredential"/);
assert.match(plugin, /function validAiEndpoint/);
assert.match(plugin, /function parseAiTranslation/);
assert.match(plugin, /options\.__signal/);
assert.match(plugin, /"ai-openai"/);

async function start(config) {
  const calls = [];
  const cache = new Map();
  const saved = {};
  const ctx = {
    URL, AbortController, setTimeout, clearTimeout,
    FTCryptoLite: {},
    translateBatch: async items => items.map(text => "legacy:" + text),
    cacheKey: (provider, from, to, text) => [provider, from, to, text].join("///"),
    cacheGet: async key => cache.get(key),
    cacheSet: async (key, value) => { cache.set(key, value); },
    chrome: {
      storage: {
        local: {
          async get(defaults) { return { ...defaults, ...config }; },
          async set(value) { Object.assign(saved, value); }
        },
        onChanged: { addListener() {} }
      },
      runtime: { onMessage: { addListener() {} } }
    },
    fetch: async (url, init) => {
      calls.push({ url, init });
      return {
        ok: true, status: 200,
        async json() {
          const request = JSON.parse(init.body);
          const originals = JSON.parse(request.messages[1].content);
          return { choices: [{ message: { content: JSON.stringify(originals.map(t => "译:" + t)) } }] };
        }
      };
    }
  };
  vm.createContext(ctx);
  vm.runInContext(plugin, ctx);
  return { ctx, calls, saved, cache };
}

(async () => {
  const cred = { id: "ai1", label: "AI Test Key", enabled: true, apiKey: "private-not-for-logs",
    endpoint: "https://example.test/v1/chat/completions", model: "example-model" };
  const config = {
    providerPoolEnabled: true,
    providerOrder: ["ai-openai", "azure", "baidu", "aliyun", "google-web"],
    aiCredentials: [cred],
    aiTranslationStyle: "简洁译法",
    fallbackGoogle: false
  };
  const { ctx, calls, saved } = await start(config);
  const out = await ctx.translateBatch(["Hello", "World"], { sourceLang: "en", targetLang: "zh-CN" });
  assert.deepEqual(Array.from(out), ["译:Hello", "译:World"]);
  assert.equal(calls.length, 1, "One AI request should translate 2 texts");
  assert.equal(calls[0].url, cred.endpoint);
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.Authorization, "Bearer " + cred.apiKey);
  const data = JSON.parse(calls[0].init.body);
  assert.equal(data.model, cred.model);
  assert.equal(data.temperature, 0);
  assert.match(data.messages[0].content, /简洁译法/);
  assert.equal(saved.providerPoolLastRouteV1.provider, "ai-openai");
  assert.ok(!JSON.stringify(saved).includes(cred.apiKey), "Diagnostic route must not expose API credentials");

  const cached = await ctx.translateBatch(["Hello", "World"], { sourceLang: "en", targetLang: "zh-CN" });
  assert.deepEqual(Array.from(cached), ["译:Hello", "译:World"]);
  assert.equal(calls.length, 1, "Identical AI translations should use local cache");

  const cancelled = new AbortController();
  cancelled.abort();
  await assert.rejects(
    () => ctx.translateBatch(["Test"], { __signal: cancelled.signal }),
    /翻译任务已取消/
  );
  assert.equal(calls.length, 1, "Cancelled job may not send text");

  const bad = await start({ ...config, aiCredentials: [{ ...cred, endpoint: "http://unsafe.test/chat/completions" }] });
  await assert.rejects(() => bad.ctx.translateBatch(["Hello"]), /HTTPS/);
  assert.equal(bad.calls.length, 0, "Never send API Key to HTTP endpoint");

  const legacy = await start({ providerPoolEnabled: true, aiCredentials: [], fallbackGoogle: false,
    providerOrder: ["azure", "baidu", "aliyun", "google-web"] });
  const snapshot = await legacy.ctx.FTProviderPool.getPoolConfig();
  assert.deepEqual(Array.from(snapshot.providerOrder), ["azure", "baidu", "aliyun", "google-web", "ai-openai"],
    "Existing users retain unchanged provider priority and AI appends last");
  console.log("v1.3.15 AI provider security, caching and backward-compatibility tests passed");
})().catch(err => { console.error(err); process.exitCode = 1; });
