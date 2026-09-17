const SYNC_DEFAULTS = {
  enabled: true,
  autoTranslate: true,
  sourceLang: "auto",
  targetLang: "zh-CN",
  displayMode: "translated",
  siteRules: {},
  skipTargetLanguage: true
};

const LOCAL_DEFAULTS = {
  translationProvider: "azure",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: "",
  requestTimeoutMs: 15000,
  maxRetries: 3,
  cacheMaxEntries: 30000,
  cacheTtlDays: 30
};

const DB_NAME = "floating-translator-cache";
const DB_VERSION = 1;
const STORE = "translations";
const USAGE_KEY = "translationUsageV2";
const AZURE_BATCH_MAX_ITEMS = 50;
const AZURE_BATCH_MAX_CHARS = 20000;
const AZURE_ITEM_CHARS = 4500;
const CACHE_PRUNE_INTERVAL_MS = 15 * 60 * 1000;
const azureHealth = { failures: 0, openUntil: 0 };
let lastCachePruneAt = 0;
let usageWriteQueue = Promise.resolve();

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function cacheRecordText(record) {
  if (typeof record === "string") return record;
  if (record && typeof record.text === "string") return record.text;
  return null;
}

async function cacheDelete(key) {
  try {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).delete(key);
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
  } catch {}
}

async function cacheGet(key, config = LOCAL_DEFAULTS) {
  try {
    const db = await openDb();
    const record = await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const request = tx.objectStore(STORE).get(key);
      request.onsuccess = () => resolve(request.result ?? null);
      request.onerror = () => reject(request.error);
    });
    if (record == null) return null;
    const text = cacheRecordText(record);
    if (typeof text !== "string") return null;
    const ttlDays = Math.max(0, Math.min(3650, Number(config.cacheTtlDays ?? 30)));
    if (ttlDays > 0 && record && typeof record === "object" && Number(record.ts) > 0) {
      if (Date.now() - Number(record.ts) > ttlDays * 86400000) {
        cacheDelete(key);
        return null;
      }
    }
    return text;
  } catch {
    return null;
  }
}

async function cacheSet(key, value) {
  try {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put({ text: String(value ?? ""), ts: Date.now() }, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // 缓存失败不影响翻译。
  }
}

async function cacheClear() {
  try {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).clear();
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
  } catch {}
}

async function cachePrune(config = LOCAL_DEFAULTS) {
  const maxEntries = Math.max(1000, Math.min(200000, Number(config.cacheMaxEntries ?? 30000)));
  const ttlDays = Math.max(0, Math.min(3650, Number(config.cacheTtlDays ?? 30)));
  const cutoff = ttlDays > 0 ? Date.now() - ttlDays * 86400000 : 0;
  const db = await openDb();
  const rows = await new Promise((resolve, reject) => {
    const items = [];
    const tx = db.transaction(STORE, "readonly");
    const request = tx.objectStore(STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return resolve(items);
      const value = cursor.value;
      const ts = value && typeof value === "object" ? Number(value.ts) || 0 : 0;
      items.push({ key: cursor.key, ts });
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
  });

  const expiredKeys = ttlDays > 0 ? rows.filter(row => row.ts > 0 && row.ts < cutoff).map(row => row.key) : [];
  const expiredSet = new Set(expiredKeys);
  const survivors = rows.filter(row => !expiredSet.has(row.key)).sort((a, b) => a.ts - b.ts);
  const excess = Math.max(0, survivors.length - maxEntries);
  const deleteKeys = [...expiredKeys, ...survivors.slice(0, excess).map(row => row.key)];

  if (deleteKeys.length) {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      deleteKeys.forEach(key => store.delete(key));
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
  }

  lastCachePruneAt = Date.now();
  return { before: rows.length, deleted: deleteKeys.length, remaining: Math.max(0, rows.length - deleteKeys.length) };
}

async function maybePruneCache(config) {
  if (Date.now() - lastCachePruneAt < CACHE_PRUNE_INTERVAL_MS) return;
  try { await cachePrune(config); } catch {}
}

async function getConfig() {
  const [sync, local] = await Promise.all([
    chrome.storage.sync.get(SYNC_DEFAULTS),
    chrome.storage.local.get(LOCAL_DEFAULTS)
  ]);
  const merged = { ...SYNC_DEFAULTS, ...LOCAL_DEFAULTS, ...sync, ...local };
  if (merged.translationProvider === "oci-proxy") merged.translationProvider = "azure";
  return merged;
}

function dateKeys(now = new Date()) {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return { dayKey: `${y}-${m}-${d}`, monthKey: `${y}-${m}` };
}

function emptyUsageBucket() {
  return {
    azure: { requests: 0, chars: 0 },
    googleWeb: { requests: 0, chars: 0 },
    cacheHits: 0
  };
}

function normalizeUsage(raw) {
  const { dayKey, monthKey } = dateKeys();
  const usage = raw && typeof raw === "object" ? structuredClone(raw) : {};
  if (usage.dayKey !== dayKey) {
    usage.dayKey = dayKey;
    usage.day = emptyUsageBucket();
  }
  if (usage.monthKey !== monthKey) {
    usage.monthKey = monthKey;
    usage.month = emptyUsageBucket();
  }
  usage.day ||= emptyUsageBucket();
  usage.month ||= emptyUsageBucket();
  return usage;
}

function queueUsageMutation(mutator) {
  usageWriteQueue = usageWriteQueue.then(async () => {
    const stored = await chrome.storage.local.get({ [USAGE_KEY]: null });
    const usage = normalizeUsage(stored[USAGE_KEY]);
    mutator(usage);
    await chrome.storage.local.set({ [USAGE_KEY]: usage });
  }).catch(() => {});
  return usageWriteQueue;
}

function recordUsage(provider, texts) {
  const chars = texts.reduce((sum, text) => sum + String(text ?? "").length, 0);
  const key = provider === "google-web" ? "googleWeb" : "azure";
  return queueUsageMutation(usage => {
    for (const bucket of [usage.day, usage.month]) {
      bucket[key] ||= { requests: 0, chars: 0 };
      bucket[key].requests += 1;
      bucket[key].chars += chars;
    }
  });
}

function recordCacheHits(count) {
  if (!count) return Promise.resolve();
  return queueUsageMutation(usage => {
    usage.day.cacheHits = Number(usage.day.cacheHits || 0) + count;
    usage.month.cacheHits = Number(usage.month.cacheHits || 0) + count;
  });
}

async function getUsageStats() {
  await usageWriteQueue;
  const stored = await chrome.storage.local.get({ [USAGE_KEY]: null });
  return normalizeUsage(stored[USAGE_KEY]);
}

function normalizeGoogleLang(lang) {
  if (!lang) return "auto";
  const value = String(lang).toLowerCase();
  if (value === "zh-hans" || value === "zh-cn") return "zh-CN";
  if (value === "zh-hant" || value === "zh-tw") return "zh-TW";
  return lang;
}

function normalizeAzureLang(lang) {
  if (!lang) return "";
  const value = String(lang).toLowerCase();
  if (value === "zh-cn" || value === "zh-hans") return "zh-Hans";
  if (value === "zh-tw" || value === "zh-hant") return "zh-Hant";
  if (value === "fil") return "fil";
  return lang;
}

function splitLongText(text, maxLength = 3500) {
  if (text.length <= maxLength) return [text];
  const pieces = [];
  let rest = text;
  while (rest.length > maxLength) {
    let cut = maxLength;
    const searchFrom = Math.floor(maxLength * 0.55);
    for (const marker of ["\n", "。", "！", "？", ". ", "! ", "? ", "; ", "，", ", ", " "]) {
      const index = rest.lastIndexOf(marker, maxLength);
      if (index >= searchFrom) {
        cut = index + marker.length;
        break;
      }
    }
    pieces.push(rest.slice(0, cut));
    rest = rest.slice(cut);
  }
  if (rest) pieces.push(rest);
  return pieces;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function retryDelay(response, attempt) {
  const retryAfter = response?.headers?.get?.("retry-after");
  if (retryAfter) {
    const seconds = Number(retryAfter);
    if (Number.isFinite(seconds)) return Math.min(10000, Math.max(400, seconds * 1000));
  }
  return Math.min(5000, 450 * (2 ** attempt) + Math.floor(Math.random() * 180));
}

function isRetryableStatus(status) {
  return [408, 425, 429, 500, 502, 503, 504].includes(status);
}

async function fetchWithRetry(url, init, config, label) {
  const retries = Math.max(0, Math.min(5, Number(config.maxRetries ?? 3)));
  const timeoutMs = Math.max(3000, Math.min(45000, Number(config.requestTimeoutMs ?? 15000)));
  let lastError = null;

  for (let attempt = 0; attempt <= retries; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(url, { ...init, signal: controller.signal });
      clearTimeout(timer);
      if (response.ok || !isRetryableStatus(response.status) || attempt === retries) return response;
      await sleep(retryDelay(response, attempt));
    } catch (error) {
      clearTimeout(timer);
      lastError = error;
      if (attempt === retries) break;
      await sleep(Math.min(5000, 450 * (2 ** attempt)));
    }
  }
  throw new Error(`${label}请求失败：${lastError?.message || lastError || "网络异常"}`);
}

async function googleWebTranslate(text, sourceLang, targetLang, config) {
  const translated = [];
  for (const piece of splitLongText(text, 3400)) {
    const params = new URLSearchParams({
      client: "gtx",
      sl: normalizeGoogleLang(sourceLang || "auto"),
      tl: normalizeGoogleLang(targetLang),
      dt: "t",
      q: piece
    });
    const response = await fetchWithRetry(
      `https://translate.googleapis.com/translate_a/single?${params.toString()}`,
      {},
      { ...config, maxRetries: Math.min(2, Number(config.maxRetries ?? 2)) },
      "Google Web"
    );
    if (!response.ok) throw new Error(`Google Web 翻译失败：HTTP ${response.status}`);
    const data = await response.json();
    const result = Array.isArray(data?.[0]) ? data[0].map(segment => segment?.[0] || "").join("") : "";
    translated.push(result || piece);
    await recordUsage("google-web", [piece]);
  }
  return translated.join("");
}

function azureHeaders(config) {
  if (!config.azureKey) throw new Error("尚未设置 Microsoft/Azure Translator Key");
  const headers = {
    "Content-Type": "application/json",
    "Ocp-Apim-Subscription-Key": config.azureKey
  };
  if (config.azureRegion) headers["Ocp-Apim-Subscription-Region"] = config.azureRegion;
  return headers;
}

function azureUrl(sourceLang, targetLang, config) {
  const endpoint = String(config.azureEndpoint || LOCAL_DEFAULTS.azureEndpoint).replace(/\/$/, "");
  const params = new URLSearchParams({ "api-version": "3.0", to: normalizeAzureLang(targetLang) });
  if (sourceLang && sourceLang !== "auto") params.set("from", normalizeAzureLang(sourceLang));
  return `${endpoint}/translate?${params.toString()}`;
}

async function azureTranslateMany(texts, sourceLang, targetLang, config) {
  const response = await fetchWithRetry(azureUrl(sourceLang, targetLang, config), {
    method: "POST",
    headers: azureHeaders(config),
    body: JSON.stringify(texts.map(text => ({ text })))
  }, config, "Microsoft/Azure");

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    const error = new Error(`Microsoft/Azure 翻译失败：HTTP ${response.status}${body ? ` - ${body.slice(0, 180)}` : ""}`);
    error.status = response.status;
    throw error;
  }

  const data = await response.json();
  if (!Array.isArray(data) || data.length !== texts.length) throw new Error("Microsoft/Azure 返回条数与请求不一致");
  await recordUsage("azure", texts);
  return data.map((item, index) => item?.translations?.[0]?.text ?? texts[index]);
}

function makeAzureBatches(items) {
  const batches = [];
  let current = [];
  let chars = 0;
  for (const item of items) {
    const size = item.text.length;
    if (current.length && (current.length >= AZURE_BATCH_MAX_ITEMS || chars + size > AZURE_BATCH_MAX_CHARS)) {
      batches.push(current);
      current = [];
      chars = 0;
    }
    current.push(item);
    chars += size;
  }
  if (current.length) batches.push(current);
  return batches;
}

function cacheKey(provider, sourceLang, targetLang, text) {
  return `${provider}\u0000${sourceLang}\u0000${targetLang}\u0000${text}`;
}

function groupUniqueTexts(texts) {
  const map = new Map();
  texts.forEach((text, index) => {
    if (!map.has(text)) map.set(text, { text, indexes: [] });
    map.get(text).indexes.push(index);
  });
  return [...map.values()];
}

async function translateGoogleBatch(texts, sourceLang, targetLang, config) {
  const results = new Array(texts.length);
  const unique = groupUniqueTexts(texts);
  let cursor = 0;
  let cacheHits = 0;

  async function worker() {
    while (cursor < unique.length) {
      const item = unique[cursor++];
      if (!item.text.trim()) {
        item.indexes.forEach(index => { results[index] = item.text; });
        continue;
      }
      const key = cacheKey("google-web", sourceLang, targetLang, item.text);
      let translated = await cacheGet(key, config);
      if (typeof translated !== "string") {
        translated = await googleWebTranslate(item.text, sourceLang, targetLang, config);
        await cacheSet(key, translated);
      } else {
        cacheHits++;
      }
      item.indexes.forEach(index => { results[index] = translated; });
    }
  }

  await Promise.all(Array.from({ length: Math.min(4, unique.length || 1) }, worker));
  await recordCacheHits(cacheHits);
  return results;
}

async function translateAzureBatch(texts, sourceLang, targetLang, config) {
  const results = new Array(texts.length);
  const unique = groupUniqueTexts(texts);
  const misses = [];
  let cacheHits = 0;

  for (const item of unique) {
    if (!item.text.trim()) {
      item.indexes.forEach(index => { results[index] = item.text; });
      continue;
    }
    const key = cacheKey("azure", sourceLang, targetLang, item.text);
    const cached = await cacheGet(key, config);
    if (typeof cached === "string") {
      cacheHits++;
      item.indexes.forEach(index => { results[index] = cached; });
    } else {
      misses.push({ ...item, key });
    }
  }

  await recordCacheHits(cacheHits);
  if (!misses.length) return results;

  if (azureHealth.openUntil > Date.now()) {
    if (!config.fallbackGoogle) throw new Error("Azure 临时熔断中，请稍后重试");
    const fallback = await translateGoogleBatch(misses.map(item => item.text), sourceLang, targetLang, config);
    misses.forEach((item, i) => item.indexes.forEach(index => { results[index] = fallback[i]; }));
    return results;
  }

  const expanded = [];
  misses.forEach((item, missIndex) => {
    splitLongText(item.text, AZURE_ITEM_CHARS).forEach((piece, pieceIndex) => {
      expanded.push({ missIndex, pieceIndex, text: piece });
    });
  });

  try {
    const pieceResults = Array.from({ length: misses.length }, () => []);
    for (const batch of makeAzureBatches(expanded)) {
      const translated = await azureTranslateMany(batch.map(item => item.text), sourceLang, targetLang, config);
      batch.forEach((item, i) => { pieceResults[item.missIndex][item.pieceIndex] = translated[i]; });
    }

    azureHealth.failures = 0;
    azureHealth.openUntil = 0;
    for (let i = 0; i < misses.length; i++) {
      const item = misses[i];
      const translated = pieceResults[i].join("");
      item.indexes.forEach(index => { results[index] = translated; });
      await cacheSet(item.key, translated);
    }
    return results;
  } catch (error) {
    azureHealth.failures += 1;
    if (azureHealth.failures >= 3) azureHealth.openUntil = Date.now() + 60000;
    if (!config.fallbackGoogle) throw error;
    console.warn("[FloatingTranslator] Azure 失败，回退 Google Web", error);
    const fallback = await translateGoogleBatch(misses.map(item => item.text), sourceLang, targetLang, config);
    misses.forEach((item, i) => item.indexes.forEach(index => { results[index] = fallback[i]; }));
    return results;
  }
}

async function translateBatch(texts, options = {}) {
  const config = await getConfig();
  const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
  const provider = options.provider || config.translationProvider || "azure";
  const sourceLang = options.sourceLang || config.sourceLang || "auto";
  const targetLang = options.targetLang || config.targetLang || "zh-CN";
  await maybePruneCache(config);
  if (provider === "google-web") return translateGoogleBatch(clean, sourceLang, targetLang, config);
  return translateAzureBatch(clean, sourceLang, targetLang, config);
}

function canSplitDetailedError(error) {
  const message = String(error?.message || error || "");
  return /HTTP\s+(400|413|422)\b/i.test(message);
}

async function translateDetailed(texts, options = {}) {
  const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
  const translations = new Array(clean.length).fill(null);
  const errors = new Array(clean.length).fill(null);

  async function solve(indexes) {
    if (!indexes.length) return;
    const subset = indexes.map(index => clean[index]);
    try {
      const translated = await translateBatch(subset, options);
      indexes.forEach((originalIndex, i) => { translations[originalIndex] = translated[i]; });
    } catch (error) {
      const message = String(error?.message || error || "翻译失败");
      if (indexes.length === 1 || !canSplitDetailedError(error)) {
        indexes.forEach(index => { errors[index] = message; });
        return;
      }
      const middle = Math.ceil(indexes.length / 2);
      await solve(indexes.slice(0, middle));
      await solve(indexes.slice(middle));
    }
  }

  await solve(clean.map((_, index) => index));
  return { translations, errors };
}

chrome.runtime.onInstalled.addListener(async () => {
  const currentSync = await chrome.storage.sync.get(Object.keys(SYNC_DEFAULTS));
  const missingSync = {};
  for (const [key, value] of Object.entries(SYNC_DEFAULTS)) if (currentSync[key] === undefined) missingSync[key] = value;
  if (Object.keys(missingSync).length) await chrome.storage.sync.set(missingSync);

  const currentLocal = await chrome.storage.local.get(null);
  const patch = {};
  for (const [key, value] of Object.entries(LOCAL_DEFAULTS)) if (currentLocal[key] === undefined) patch[key] = value;
  if (currentLocal.translationProvider === "oci-proxy") patch.translationProvider = "azure";
  await chrome.storage.local.remove(["ociProxyEndpoint", "ociProxyToken"]);
  if (Object.keys(patch).length) await chrome.storage.local.set(patch);

  try { await cachePrune({ ...LOCAL_DEFAULTS, ...currentLocal, ...patch }); } catch {}

  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "ft-translate-selection", title: "FloatingTranslator：翻译选中文字", contexts: ["selection"] });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "ft-translate-selection" || !info.selectionText || !tab?.id) return;
  try {
    const [translated] = await translateBatch([info.selectionText]);
    await chrome.tabs.sendMessage(tab.id, { type: "FT_SHOW_SELECTION_TRANSLATION", original: info.selectionText, translated });
  } catch (error) {
    await chrome.tabs.sendMessage(tab.id, {
      type: "FT_SHOW_SELECTION_TRANSLATION",
      original: info.selectionText,
      translated: `翻译失败：${error?.message || error}`,
      error: true
    }).catch(() => {});
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "FT_GET_CONFIG") {
    getConfig().then(config => sendResponse({
      ok: true,
      config: { ...config, azureKey: config.azureKey ? "__SET__" : "" },
      health: { azureCircuitOpen: azureHealth.openUntil > Date.now(), azureFailures: azureHealth.failures }
    })).catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_TRANSLATE") {
    translateBatch(message.texts, message.options)
      .then(translations => sendResponse({ ok: true, translations }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_TRANSLATE_DETAILED") {
    translateDetailed(message.texts, message.options)
      .then(result => sendResponse({ ok: true, ...result }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_CLEAR_CACHE") {
    cacheClear().then(() => sendResponse({ ok: true })).catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_PRUNE_CACHE") {
    getConfig().then(cachePrune).then(stats => sendResponse({ ok: true, ...stats }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_USAGE_STATS") {
    getUsageStats().then(usage => sendResponse({ ok: true, usage }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_RESET_USAGE_STATS") {
    chrome.storage.local.remove(USAGE_KEY).then(() => sendResponse({ ok: true }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  return false;
});