const SYNC_DEFAULTS = {
  enabled: true,
  autoTranslate: true,
  sourceLang: "auto",
  targetLang: "zh-CN",
  displayMode: "translated",
  siteRules: {}
};

const LOCAL_DEFAULTS = {
  translationProvider: "azure",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: ""
};

const DB_NAME = "floating-translator-cache";
const DB_VERSION = 1;
const STORE = "translations";
const AZURE_BATCH_MAX_ITEMS = 50;
const AZURE_BATCH_MAX_CHARS = 20000;

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

async function cacheGet(key) {
  try {
    const db = await openDb();
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readonly");
      const request = tx.objectStore(STORE).get(key);
      request.onsuccess = () => resolve(request.result ?? null);
      request.onerror = () => reject(request.error);
    });
  } catch {
    return null;
  }
}

async function cacheSet(key, value) {
  try {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      tx.objectStore(STORE).put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // 缓存失败不影响翻译本身。
  }
}

async function getConfig() {
  const [sync, local] = await Promise.all([
    chrome.storage.sync.get(SYNC_DEFAULTS),
    chrome.storage.local.get(LOCAL_DEFAULTS)
  ]);

  const merged = { ...SYNC_DEFAULTS, ...LOCAL_DEFAULTS, ...sync, ...local };
  // 从曾经的 OCI 测试版升级时，自动迁回 Azure，不让旧设置把翻译卡死。
  if (merged.translationProvider === "oci-proxy") merged.translationProvider = "azure";
  return merged;
}

function normalizeGoogleLang(lang) {
  if (!lang) return "auto";
  const value = lang.toLowerCase();
  if (value === "zh-hans") return "zh-CN";
  if (value === "zh-hant") return "zh-TW";
  return lang;
}

function normalizeAzureLang(lang) {
  if (!lang) return "";
  const value = lang.toLowerCase();
  if (value === "zh-cn" || value === "zh-hans") return "zh-Hans";
  if (value === "zh-tw" || value === "zh-hant") return "zh-Hant";
  return lang;
}

function splitLongText(text, maxLength = 3500) {
  if (text.length <= maxLength) return [text];
  const pieces = [];
  let rest = text;
  while (rest.length > maxLength) {
    let cut = maxLength;
    const searchFrom = Math.floor(maxLength * 0.55);
    const candidates = ["\n", "。", "！", "？", ". ", "! ", "? ", "; ", "，", ", "];
    for (const marker of candidates) {
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

async function googleWebTranslate(text, sourceLang, targetLang) {
  const pieces = splitLongText(text);
  const translated = [];

  for (const piece of pieces) {
    const params = new URLSearchParams({
      client: "gtx",
      sl: normalizeGoogleLang(sourceLang || "auto"),
      tl: normalizeGoogleLang(targetLang),
      dt: "t",
      q: piece
    });
    const response = await fetch(`https://translate.googleapis.com/translate_a/single?${params.toString()}`);
    if (!response.ok) throw new Error(`Google Web 翻译失败：HTTP ${response.status}`);
    const data = await response.json();
    const result = Array.isArray(data?.[0])
      ? data[0].map(segment => segment?.[0] || "").join("")
      : "";
    translated.push(result || piece);
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
  const params = new URLSearchParams({
    "api-version": "3.0",
    to: normalizeAzureLang(targetLang)
  });
  if (sourceLang && sourceLang !== "auto") params.set("from", normalizeAzureLang(sourceLang));
  return `${endpoint}/translate?${params.toString()}`;
}

async function azureTranslateMany(texts, sourceLang, targetLang, config) {
  const response = await fetch(azureUrl(sourceLang, targetLang, config), {
    method: "POST",
    headers: azureHeaders(config),
    body: JSON.stringify(texts.map(text => ({ text })))
  });

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`Microsoft/Azure 翻译失败：HTTP ${response.status}${body ? ` - ${body.slice(0, 180)}` : ""}`);
  }

  const data = await response.json();
  if (!Array.isArray(data) || data.length !== texts.length) {
    throw new Error("Microsoft/Azure 返回条数与请求不一致");
  }
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

async function translateAzureBatch(texts, sourceLang, targetLang, config) {
  const results = new Array(texts.length);
  const misses = [];

  await Promise.all(texts.map(async (text, index) => {
    if (!text.trim()) {
      results[index] = text;
      return;
    }
    const key = cacheKey("azure", sourceLang, targetLang, text);
    const cached = await cacheGet(key);
    if (typeof cached === "string") results[index] = cached;
    else misses.push({ index, text, key });
  }));

  for (const batch of makeAzureBatches(misses)) {
    try {
      const translated = await azureTranslateMany(batch.map(item => item.text), sourceLang, targetLang, config);
      await Promise.all(batch.map(async (item, i) => {
        results[item.index] = translated[i];
        await cacheSet(item.key, translated[i]);
      }));
    } catch (error) {
      if (!config.fallbackGoogle) throw error;
      console.warn("[FloatingTranslator] Azure 批量翻译失败，回退 Google Web", error);
      await Promise.all(batch.map(async item => {
        const translated = await googleWebTranslate(item.text, sourceLang, targetLang);
        results[item.index] = translated;
        await cacheSet(item.key, translated);
      }));
    }
  }

  return results;
}

async function translateGoogleBatch(texts, sourceLang, targetLang) {
  return Promise.all(texts.map(async text => {
    if (!text.trim()) return text;
    const key = cacheKey("google-web", sourceLang, targetLang, text);
    const cached = await cacheGet(key);
    if (typeof cached === "string") return cached;
    const translated = await googleWebTranslate(text, sourceLang, targetLang);
    await cacheSet(key, translated);
    return translated;
  }));
}

async function translateBatch(texts, options = {}) {
  const config = await getConfig();
  const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
  const provider = options.provider || config.translationProvider || "azure";
  const sourceLang = options.sourceLang || config.sourceLang || "auto";
  const targetLang = options.targetLang || config.targetLang || "zh-CN";

  if (provider === "google-web") return translateGoogleBatch(clean, sourceLang, targetLang);
  return translateAzureBatch(clean, sourceLang, targetLang, config);
}

chrome.runtime.onInstalled.addListener(async () => {
  const currentSync = await chrome.storage.sync.get(Object.keys(SYNC_DEFAULTS));
  const missingSync = {};
  for (const [key, value] of Object.entries(SYNC_DEFAULTS)) {
    if (currentSync[key] === undefined) missingSync[key] = value;
  }
  if (Object.keys(missingSync).length) await chrome.storage.sync.set(missingSync);

  const currentLocal = await chrome.storage.local.get(null);
  const patch = {};
  for (const [key, value] of Object.entries(LOCAL_DEFAULTS)) {
    if (currentLocal[key] === undefined) patch[key] = value;
  }
  if (currentLocal.translationProvider === "oci-proxy") patch.translationProvider = "azure";
  // 删除旧 OCI 测试配置，避免升级后继续残留敏感/无用数据。
  await chrome.storage.local.remove(["ociProxyEndpoint", "ociProxyToken"]);
  if (Object.keys(patch).length) await chrome.storage.local.set(patch);

  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: "ft-translate-selection",
      title: "FloatingTranslator：翻译选中文字",
      contexts: ["selection"]
    });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== "ft-translate-selection" || !info.selectionText || !tab?.id) return;
  try {
    const [translated] = await translateBatch([info.selectionText]);
    await chrome.tabs.sendMessage(tab.id, {
      type: "FT_SHOW_SELECTION_TRANSLATION",
      original: info.selectionText,
      translated
    });
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
    getConfig()
      .then(config => sendResponse({
        ok: true,
        config: {
          ...config,
          azureKey: config.azureKey ? "__SET__" : ""
        }
      }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_TRANSLATE") {
    translateBatch(message.texts, message.options)
      .then(translations => sendResponse({ ok: true, translations }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  return false;
});
