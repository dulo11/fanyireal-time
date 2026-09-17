const SYNC_DEFAULTS = {
  enabled: true,
  autoTranslate: true,
  sourceLang: "auto",
  targetLang: "zh-CN",
  displayMode: "translated",
  siteRules: {}
};

const LOCAL_DEFAULTS = {
  translationProvider: "google-web",
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: ""
};

const DB_NAME = "floating-translator-cache";
const DB_VERSION = 1;
const STORE = "translations";

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE);
      }
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
  return { ...SYNC_DEFAULTS, ...LOCAL_DEFAULTS, ...sync, ...local };
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
    if (!response.ok) {
      throw new Error(`Google Web 翻译失败：HTTP ${response.status}`);
    }
    const data = await response.json();
    const result = Array.isArray(data?.[0])
      ? data[0].map(segment => segment?.[0] || "").join("")
      : "";
    translated.push(result || piece);
  }

  return translated.join("");
}

async function azureTranslate(text, sourceLang, targetLang, config) {
  if (!config.azureKey) {
    throw new Error("尚未设置 Microsoft/Azure Translator Key");
  }

  const endpoint = String(config.azureEndpoint || LOCAL_DEFAULTS.azureEndpoint).replace(/\/$/, "");
  const params = new URLSearchParams({
    "api-version": "3.0",
    to: normalizeAzureLang(targetLang)
  });
  if (sourceLang && sourceLang !== "auto") {
    params.set("from", normalizeAzureLang(sourceLang));
  }

  const headers = {
    "Content-Type": "application/json",
    "Ocp-Apim-Subscription-Key": config.azureKey
  };
  if (config.azureRegion) {
    headers["Ocp-Apim-Subscription-Region"] = config.azureRegion;
  }

  const response = await fetch(`${endpoint}/translate?${params.toString()}`, {
    method: "POST",
    headers,
    body: JSON.stringify([{ text }])
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`Microsoft/Azure 翻译失败：HTTP ${response.status}${body ? ` - ${body.slice(0, 180)}` : ""}`);
  }

  const data = await response.json();
  return data?.[0]?.translations?.[0]?.text || text;
}

async function translateOne(text, options, config) {
  if (!text || !text.trim()) return text;
  const provider = options.provider || config.translationProvider || "google-web";
  const sourceLang = options.sourceLang || config.sourceLang || "auto";
  const targetLang = options.targetLang || config.targetLang || "zh-CN";
  const key = `${provider}\u0000${sourceLang}\u0000${targetLang}\u0000${text}`;
  const cached = await cacheGet(key);
  if (typeof cached === "string") return cached;

  let result;
  if (provider === "azure") {
    result = await azureTranslate(text, sourceLang, targetLang, config);
  } else {
    result = await googleWebTranslate(text, sourceLang, targetLang);
  }

  await cacheSet(key, result);
  return result;
}

async function mapLimit(items, limit, mapper) {
  const results = new Array(items.length);
  let cursor = 0;

  async function worker() {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      results[index] = await mapper(items[index], index);
    }
  }

  const workers = Array.from({ length: Math.min(limit, items.length) }, () => worker());
  await Promise.all(workers);
  return results;
}

async function translateBatch(texts, options = {}) {
  const config = await getConfig();
  const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
  return mapLimit(clean, 4, text => translateOne(text, options, config));
}

chrome.runtime.onInstalled.addListener(async () => {
  const currentSync = await chrome.storage.sync.get(Object.keys(SYNC_DEFAULTS));
  const missingSync = {};
  for (const [key, value] of Object.entries(SYNC_DEFAULTS)) {
    if (currentSync[key] === undefined) missingSync[key] = value;
  }
  if (Object.keys(missingSync).length) await chrome.storage.sync.set(missingSync);

  const currentLocal = await chrome.storage.local.get(Object.keys(LOCAL_DEFAULTS));
  const missingLocal = {};
  for (const [key, value] of Object.entries(LOCAL_DEFAULTS)) {
    if (currentLocal[key] === undefined) missingLocal[key] = value;
  }
  if (Object.keys(missingLocal).length) await chrome.storage.local.set(missingLocal);

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
      .then(config => sendResponse({ ok: true, config: { ...config, azureKey: config.azureKey ? "__SET__" : "" } }))
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
