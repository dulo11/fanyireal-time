(() => {
  if (globalThis.FT_PROVIDER_POOL) return;
  globalThis.FT_PROVIDER_POOL = true;

  const baseTranslateBatch = globalThis.translateBatch;
  const cryptoLite = globalThis.FTCryptoLite;
  if (typeof baseTranslateBatch !== "function" || !cryptoLite) return;

  const DEFAULT_ORDER = ["azure", "baidu", "aliyun", "google-web"];
  const DEFAULTS = {
    providerPoolEnabled: true,
    providerOrder: DEFAULT_ORDER,
    providerCooldownMs: 60000,
    fallbackGoogle: true,
    azureCredentials: [],
    baiduCredentials: [],
    aliyunCredentials: [],
    azureEndpoint: "https://api.cognitive.microsofttranslator.com",
    azureRegion: "",
    azureKey: "",
    requestTimeoutMs: 15000,
    maxRetries: 3,
    cacheTtlDays: 30
  };
  const ROUTE_KEY = "providerPoolLastRouteV1";
  const health = new Map();

  const PROVIDER_LABELS = {
    azure: "Azure",
    baidu: "百度翻译",
    aliyun: "阿里云翻译",
    "google-web": "Google Web"
  };

  function uid(prefix = "cred") {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
  }

  function enabled(value) {
    return value !== false;
  }

  function normalizeOrder(order) {
    const clean = (Array.isArray(order) ? order : []).filter(item => DEFAULT_ORDER.includes(item));
    for (const item of DEFAULT_ORDER) if (!clean.includes(item)) clean.push(item);
    return clean;
  }

  function normalizeCredential(provider, raw, index) {
    const item = raw && typeof raw === "object" ? { ...raw } : {};
    item.id ||= `${provider}-${index + 1}`;
    item.label ||= `${PROVIDER_LABELS[provider]} ${index + 1}`;
    if (item.enabled === undefined) item.enabled = true;
    return item;
  }

  async function getPoolConfig() {
    const local = await chrome.storage.local.get(DEFAULTS);
    const config = { ...DEFAULTS, ...local, providerOrder: normalizeOrder(local.providerOrder) };
    config.azureCredentials = (Array.isArray(local.azureCredentials) ? local.azureCredentials : []).map((item, i) => normalizeCredential("azure", item, i));
    config.baiduCredentials = (Array.isArray(local.baiduCredentials) ? local.baiduCredentials : []).map((item, i) => normalizeCredential("baidu", item, i));
    config.aliyunCredentials = (Array.isArray(local.aliyunCredentials) ? local.aliyunCredentials : []).map((item, i) => normalizeCredential("aliyun", item, i));

    if (!config.azureCredentials.length && config.azureKey) {
      config.azureCredentials.push({
        id: "azure-legacy",
        label: "Azure 原有 Key",
        enabled: true,
        key: config.azureKey,
        region: config.azureRegion || "",
        endpoint: config.azureEndpoint || DEFAULTS.azureEndpoint
      });
    }
    return config;
  }

  function healthKey(provider, credential) {
    return `${provider}:${credential?.id || "default"}`;
  }

  function getHealth(provider, credential) {
    const key = healthKey(provider, credential);
    if (!health.has(key)) health.set(key, { failures: 0, cooldownUntil: 0, authBlocked: false, lastError: "", lastCategory: "", lastAt: 0 });
    return health.get(key);
  }

  function resetHealth(provider, credential) {
    const state = getHealth(provider, credential);
    state.failures = 0;
    state.cooldownUntil = 0;
    state.authBlocked = false;
    state.lastError = "";
    state.lastCategory = "";
    state.lastAt = Date.now();
  }

  function markFailure(provider, credential, error, config) {
    const state = getHealth(provider, credential);
    const category = error?.category || "transient";
    state.failures += 1;
    state.lastError = String(error?.message || error || "请求失败");
    state.lastCategory = category;
    state.lastAt = Date.now();

    if (category === "auth") {
      state.authBlocked = true;
      state.cooldownUntil = Number.MAX_SAFE_INTEGER;
      return;
    }

    const baseCooldown = Math.max(5000, Math.min(10 * 60 * 1000, Number(config.providerCooldownMs || 60000)));
    if (category === "rate" || category === "transient") {
      state.cooldownUntil = Date.now() + Math.max(baseCooldown, Number(error?.retryAfterMs || 0));
    } else {
      state.cooldownUntil = Date.now() + Math.min(15000, baseCooldown);
    }
  }

  function isAvailable(provider, credential) {
    const state = getHealth(provider, credential);
    if (state.authBlocked) return false;
    return Number(state.cooldownUntil || 0) <= Date.now();
  }

  function makeError(message, category = "transient", status = 0, retryAfterMs = 0) {
    const error = new Error(message);
    error.category = category;
    error.status = status;
    error.retryAfterMs = retryAfterMs;
    return error;
  }

  function classifyStatus(status) {
    if (status === 401 || status === 403) return "auth";
    if (status === 429) return "rate";
    if ([408, 425, 500, 502, 503, 504].includes(status)) return "transient";
    if (status >= 400 && status < 500) return "request";
    return "transient";
  }

  function retryAfterMs(headers) {
    const value = headers?.get?.("retry-after");
    if (!value) return 0;
    const seconds = Number(value);
    if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
    const date = Date.parse(value);
    return Number.isFinite(date) ? Math.max(0, date - Date.now()) : 0;
  }

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  async function requestText(url, init, config, label) {
    const timeoutMs = Math.max(3000, Math.min(45000, Number(config.requestTimeoutMs || 15000)));
    const retries = Math.min(1, Math.max(0, Number(config.maxRetries ?? 1)));
    let last = null;

    for (let attempt = 0; attempt <= retries; attempt++) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(url, { ...init, signal: controller.signal });
        const text = await response.text();
        clearTimeout(timer);
        if (response.ok) return { response, text };
        const category = classifyStatus(response.status);
        const error = makeError(`${label}失败：HTTP ${response.status}${text ? ` · ${text.slice(0, 240)}` : ""}`, category, response.status, retryAfterMs(response.headers));
        last = error;
        if (!(["rate", "transient"].includes(category)) || attempt === retries) throw error;
        await sleep(Math.min(3000, 450 * (2 ** attempt) + error.retryAfterMs));
      } catch (error) {
        clearTimeout(timer);
        if (error?.category) throw error;
        last = makeError(`${label}网络失败：${error?.name === "AbortError" ? "请求超时" : (error?.message || error)}`, "transient");
        if (attempt === retries) throw last;
        await sleep(450 * (2 ** attempt));
      }
    }
    throw last || makeError(`${label}请求失败`);
  }

  function normalizeAzureLang(lang) {
    const value = String(lang || "").toLowerCase();
    if (!value || value === "auto") return "";
    if (["zh-cn", "zh-hans"].includes(value)) return "zh-Hans";
    if (["zh-tw", "zh-hant"].includes(value)) return "zh-Hant";
    return lang;
  }

  const BAIDU_LANG = {
    "zh-cn": "zh", "zh-hans": "zh", "zh-tw": "cht", "zh-hant": "cht",
    ja: "jp", ko: "kor", fr: "fra", es: "spa", ar: "ara", vi: "vie", ms: "may",
    fil: "fil", en: "en", th: "th", ru: "ru", pt: "pt", de: "de", it: "it", hi: "hi", id: "id", tr: "tr"
  };

  function normalizeBaiduLang(lang, allowAuto = false) {
    const value = String(lang || "").toLowerCase();
    if (allowAuto && (!value || value === "auto")) return "auto";
    return BAIDU_LANG[value] || value || (allowAuto ? "auto" : "zh");
  }

  function normalizeAliyunLang(lang, allowAuto = false) {
    const value = String(lang || "").toLowerCase();
    if (allowAuto && (!value || value === "auto")) return "auto";
    if (["zh-cn", "zh-hans"].includes(value)) return "zh";
    if (["zh-tw", "zh-hant"].includes(value)) return "zh-tw";
    return value || (allowAuto ? "auto" : "zh");
  }

  function splitText(text, max = 1800) {
    const source = String(text ?? "");
    if (source.length <= max) return [source];
    const out = [];
    let rest = source;
    while (rest.length > max) {
      let cut = max;
      const floor = Math.floor(max * 0.55);
      for (const marker of ["\n", "。", "！", "？", ". ", "! ", "? ", "; ", "，", ", ", " "]) {
        const index = rest.lastIndexOf(marker, max);
        if (index >= floor) { cut = index + marker.length; break; }
      }
      out.push(rest.slice(0, cut));
      rest = rest.slice(cut);
    }
    if (rest) out.push(rest);
    return out;
  }

  async function cached(provider, sourceLang, targetLang, text, config) {
    if (typeof globalThis.cacheGet !== "function" || typeof globalThis.cacheKey !== "function") return null;
    return globalThis.cacheGet(globalThis.cacheKey(provider, sourceLang, targetLang, text), config);
  }

  async function cacheWrite(provider, sourceLang, targetLang, text, translated) {
    if (typeof globalThis.cacheSet !== "function" || typeof globalThis.cacheKey !== "function") return;
    await globalThis.cacheSet(globalThis.cacheKey(provider, sourceLang, targetLang, text), translated);
  }

  async function translateAzure(texts, options, credential, config) {
    const source = options.sourceLang || "auto";
    const target = options.targetLang || "zh-CN";
    const endpoint = String(credential.endpoint || config.azureEndpoint || DEFAULTS.azureEndpoint).replace(/\/$/, "");
    if (!credential.key) throw makeError("Azure 凭据缺少 Key", "auth");

    const results = new Array(texts.length);
    const misses = [];
    for (let i = 0; i < texts.length; i++) {
      const text = String(texts[i] ?? "");
      if (!text.trim()) { results[i] = text; continue; }
      const hit = await cached("azure", source, target, text, config);
      if (typeof hit === "string") results[i] = hit;
      else misses.push({ index: i, text });
    }
    if (!misses.length) return results;

    const expanded = [];
    misses.forEach((item, missIndex) => splitText(item.text, 4500).forEach((piece, pieceIndex) => expanded.push({ missIndex, pieceIndex, text: piece })));
    const pieceResults = Array.from({ length: misses.length }, () => []);
    let cursor = 0;
    while (cursor < expanded.length) {
      const batch = [];
      let chars = 0;
      while (cursor < expanded.length && batch.length < 50 && chars + expanded[cursor].text.length <= 20000) {
        batch.push(expanded[cursor]);
        chars += expanded[cursor].text.length;
        cursor++;
      }
      if (!batch.length) batch.push(expanded[cursor++]);

      const params = new URLSearchParams({ "api-version": "3.0", to: normalizeAzureLang(target) });
      if (source !== "auto") params.set("from", normalizeAzureLang(source));
      const headers = { "Content-Type": "application/json", "Ocp-Apim-Subscription-Key": credential.key };
      if (credential.region || config.azureRegion) headers["Ocp-Apim-Subscription-Region"] = credential.region || config.azureRegion;
      const { text } = await requestText(`${endpoint}/translate?${params}`, {
        method: "POST", headers, body: JSON.stringify(batch.map(item => ({ text: item.text })))
      }, config, credential.label || "Azure");
      let data;
      try { data = JSON.parse(text); } catch { throw makeError("Azure 返回了无法解析的数据", "transient"); }
      if (!Array.isArray(data) || data.length !== batch.length) throw makeError("Azure 返回条数与请求不一致", "transient");
      batch.forEach((item, i) => { pieceResults[item.missIndex][item.pieceIndex] = data[i]?.translations?.[0]?.text ?? item.text; });
      if (typeof globalThis.recordUsage === "function") await globalThis.recordUsage("azure", batch.map(item => item.text));
    }

    for (let i = 0; i < misses.length; i++) {
      const translated = pieceResults[i].join("");
      results[misses[i].index] = translated;
      await cacheWrite("azure", source, target, misses[i].text, translated);
    }
    return results;
  }

  function baiduBusinessError(data) {
    const code = String(data?.error_code || "");
    if (!code) return null;
    if (["54003", "54005", "59004"].includes(code)) return makeError(`百度翻译限流：${code} ${data.error_msg || ""}`.trim(), "rate", 429, code === "54005" ? 3000 : 60000);
    if (["52001", "52002"].includes(code)) return makeError(`百度翻译临时错误：${code} ${data.error_msg || ""}`.trim(), "transient");
    if (["52003", "54001", "54004", "58000", "58002", "58003", "90107"].includes(code)) return makeError(`百度凭据/账户错误：${code} ${data.error_msg || ""}`.trim(), "auth", 403);
    return makeError(`百度翻译错误：${code} ${data.error_msg || ""}`.trim(), "request", 400);
  }

  async function baiduPiece(piece, source, target, credential, config) {
    const endpoint = credential.endpoint || "https://fanyi-api.baidu.com/ait/api/aiTextTranslate";
    const appid = credential.appId || credential.appid || "";
    if (!appid) throw makeError("百度凭据缺少 APPID", "auth");
    let init;

    if (credential.apiKey) {
      init = {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${credential.apiKey}` },
        body: JSON.stringify({ appid, from: normalizeBaiduLang(source, true), to: normalizeBaiduLang(target), q: piece, model_type: "nmt" })
      };
    } else if (credential.secret) {
      const salt = `${Date.now()}${Math.floor(Math.random() * 100000)}`;
      const sign = cryptoLite.md5Hex(`${appid}${piece}${salt}${credential.secret}`);
      const body = new URLSearchParams({ appid, q: piece, from: normalizeBaiduLang(source, true), to: normalizeBaiduLang(target), salt, sign, model_type: "nmt" });
      init = { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: body.toString() };
    } else {
      throw makeError("百度凭据需要 API Key，或 APPID + 密钥", "auth");
    }

    const { text } = await requestText(endpoint, init, config, credential.label || "百度翻译");
    let data;
    try { data = JSON.parse(text); } catch { throw makeError("百度翻译返回了无法解析的数据", "transient"); }
    const business = baiduBusinessError(data);
    if (business) throw business;
    if (!Array.isArray(data?.trans_result)) throw makeError("百度翻译未返回 trans_result", "transient");
    return data.trans_result.map(item => item?.dst || "").join("");
  }

  async function translateBaidu(texts, options, credential, config) {
    const source = options.sourceLang || "auto";
    const target = options.targetLang || "zh-CN";
    const results = [];
    for (const original of texts) {
      const text = String(original ?? "");
      if (!text.trim()) { results.push(text); continue; }
      const hit = await cached("baidu", source, target, text, config);
      if (typeof hit === "string") { results.push(hit); continue; }
      const pieces = [];
      for (const piece of splitText(text, 1800)) pieces.push(await baiduPiece(piece, source, target, credential, config));
      const translated = pieces.join("");
      await cacheWrite("baidu", source, target, text, translated);
      results.push(translated);
    }
    return results;
  }

  function randomNonce() {
    return crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  async function aliyunPiece(piece, source, target, credential, config) {
    if (!credential.accessKeyId || !credential.accessKeySecret) throw makeError("阿里云凭据缺少 AccessKey ID / Secret", "auth");
    const endpoint = String(credential.endpoint || "https://mt.cn-hangzhou.aliyuncs.com").replace(/\/$/, "");
    const resource = "/api/translate/web/general";
    const url = `${endpoint}${resource}`;
    const body = JSON.stringify({
      FormatType: "text",
      SourceLanguage: normalizeAliyunLang(source, true),
      TargetLanguage: normalizeAliyunLang(target),
      SourceText: piece,
      Scene: "general"
    });
    const accept = "application/json";
    const contentType = "application/json;charset=utf-8";
    const contentMD5 = cryptoLite.md5Base64(body);
    const date = new Date().toUTCString();
    const nonce = randomNonce();
    const version = "2019-01-02";
    const stringToSign = [
      "POST", accept, contentMD5, contentType, date,
      "x-acs-signature-method:HMAC-SHA1",
      `x-acs-signature-nonce:${nonce}`,
      `x-acs-version:${version}`
    ].join("\n") + `\n${resource}`;
    const signature = await cryptoLite.hmacSha1Base64(credential.accessKeySecret, stringToSign);
    const headers = {
      "Accept": accept,
      "Content-Type": contentType,
      "Content-MD5": contentMD5,
      "Date": date,
      "x-acs-signature-method": "HMAC-SHA1",
      "x-acs-signature-nonce": nonce,
      "x-acs-version": version,
      "Authorization": `acs ${credential.accessKeyId}:${signature}`
    };
    const { text } = await requestText(url, { method: "POST", headers, body }, config, credential.label || "阿里云翻译");
    let data;
    try { data = JSON.parse(text); } catch {
      if (/Signature|AccessKey|Forbidden|Unauthorized/i.test(text)) throw makeError(`阿里云认证失败：${text.slice(0, 180)}`, "auth", 403);
      throw makeError("阿里云翻译返回了无法解析的数据", "transient");
    }
    const code = data?.Code ?? data?.code;
    if (code !== undefined && String(code) !== "200") {
      const message = String(data?.Message || data?.message || "");
      if (/signature|access.?key|authorization|forbidden/i.test(message)) throw makeError(`阿里云认证失败：${message}`, "auth", 403);
      if (/thrott|limit|qps|quota/i.test(message)) throw makeError(`阿里云限流：${message}`, "rate", 429, 60000);
      throw makeError(`阿里云翻译错误：${code} ${message}`.trim(), "request", 400);
    }
    const translated = data?.Data?.Translated ?? data?.data?.translated ?? data?.Translated ?? data?.translated;
    if (typeof translated !== "string") throw makeError("阿里云翻译未返回译文", "transient");
    return translated;
  }

  async function translateAliyun(texts, options, credential, config) {
    const source = options.sourceLang || "auto";
    const target = options.targetLang || "zh-CN";
    const results = [];
    for (const original of texts) {
      const text = String(original ?? "");
      if (!text.trim()) { results.push(text); continue; }
      const hit = await cached("aliyun", source, target, text, config);
      if (typeof hit === "string") { results.push(hit); continue; }
      const pieces = [];
      for (const piece of splitText(text, 4500)) pieces.push(await aliyunPiece(piece, source, target, credential, config));
      const translated = pieces.join("");
      await cacheWrite("aliyun", source, target, text, translated);
      results.push(translated);
    }
    return results;
  }

  async function translateByProvider(provider, texts, options, credential, config) {
    if (provider === "azure") return translateAzure(texts, options, credential, config);
    if (provider === "baidu") return translateBaidu(texts, options, credential, config);
    if (provider === "aliyun") return translateAliyun(texts, options, credential, config);
    if (provider === "google-web") return baseTranslateBatch(texts, { ...options, provider: "google-web" });
    throw makeError(`未知翻译引擎：${provider}`, "request");
  }

  function credentialsFor(provider, config) {
    if (provider === "azure") return config.azureCredentials.filter(item => enabled(item.enabled) && item.key);
    if (provider === "baidu") return config.baiduCredentials.filter(item => enabled(item.enabled) && (item.apiKey || item.secret) && (item.appId || item.appid));
    if (provider === "aliyun") return config.aliyunCredentials.filter(item => enabled(item.enabled) && item.accessKeyId && item.accessKeySecret);
    return [];
  }

  async function writeRoute(provider, credential, ok, error = "") {
    try {
      await chrome.storage.local.set({
        [ROUTE_KEY]: {
          provider,
          label: PROVIDER_LABELS[provider] || provider,
          credentialId: credential?.id || "",
          credentialLabel: credential?.label || "",
          ok,
          error: String(error || ""),
          at: Date.now()
        }
      });
    } catch {}
  }

  async function pooledTranslateBatch(texts, options = {}) {
    const clean = Array.isArray(texts) ? texts.map(value => String(value ?? "")) : [];
    const config = await getPoolConfig();
    if (!config.providerPoolEnabled) return baseTranslateBatch(clean, options);

    if (options?.provider === "google-web") return baseTranslateBatch(clean, { ...options, provider: "google-web" });

    const order = options?.provider && DEFAULT_ORDER.includes(options.provider)
      ? [options.provider, ...(options.provider !== "google-web" && config.fallbackGoogle ? ["google-web"] : [])]
      : config.providerOrder;
    const errors = [];

    for (const provider of order) {
      if (provider === "google-web") {
        if (!config.fallbackGoogle && options?.provider !== "google-web") continue;
        try {
          const result = await translateByProvider(provider, clean, options, null, config);
          await writeRoute(provider, null, true);
          return result;
        } catch (error) {
          errors.push(`${PROVIDER_LABELS[provider]}：${error?.message || error}`);
          await writeRoute(provider, null, false, error?.message || error);
          continue;
        }
      }

      const credentials = credentialsFor(provider, config);
      if (!credentials.length) continue;
      let attempted = false;
      for (const credential of credentials) {
        if (!isAvailable(provider, credential)) continue;
        attempted = true;
        try {
          const result = await translateByProvider(provider, clean, options, credential, config);
          resetHealth(provider, credential);
          await writeRoute(provider, credential, true);
          return result;
        } catch (error) {
          markFailure(provider, credential, error, config);
          errors.push(`${credential.label || PROVIDER_LABELS[provider]}：${error?.message || error}`);
          await writeRoute(provider, credential, false, error?.message || error);
        }
      }
      if (!attempted) errors.push(`${PROVIDER_LABELS[provider]}：全部凭据正在冷却或已因认证错误停用`);
    }

    throw new Error(errors.length ? `所有翻译引擎均不可用：${errors.join("；")}` : "没有可用的翻译引擎或凭据");
  }

  function statusSnapshot(config) {
    const providers = {};
    for (const provider of ["azure", "baidu", "aliyun"]) {
      const credentials = credentialsFor(provider, config);
      providers[provider] = credentials.map(item => {
        const state = getHealth(provider, item);
        return {
          id: item.id,
          label: item.label,
          enabled: enabled(item.enabled),
          available: !state.authBlocked && state.cooldownUntil <= Date.now(),
          authBlocked: state.authBlocked,
          cooldownUntil: state.cooldownUntil === Number.MAX_SAFE_INTEGER ? 0 : state.cooldownUntil,
          failures: state.failures,
          lastCategory: state.lastCategory,
          lastError: state.lastError,
          lastAt: state.lastAt
        };
      });
    }
    return { enabled: config.providerPoolEnabled !== false, order: config.providerOrder, cooldownMs: config.providerCooldownMs, providers };
  }

  globalThis.translateBatch = pooledTranslateBatch;
  globalThis.FTProviderPool = Object.freeze({ getPoolConfig, statusSnapshot, uid, normalizeOrder });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;
    if (["azureCredentials", "baiduCredentials", "aliyunCredentials", "providerOrder", "providerPoolEnabled", "providerCooldownMs"].some(key => key in changes)) health.clear();
  });

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "FT_PROVIDER_POOL_STATUS") {
      getPoolConfig().then(config => sendResponse({ ok: true, status: statusSnapshot(config) })).catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
      return true;
    }
    if (message?.type === "FT_PROVIDER_POOL_RESET_HEALTH") {
      health.clear();
      sendResponse({ ok: true });
      return false;
    }
    return false;
  });
})();