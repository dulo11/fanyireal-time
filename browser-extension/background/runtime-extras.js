const FT_RUNTIME_DB = "floating-translator-cache";
const FT_RUNTIME_STORE = "translations";
const FT_RUNTIME_STATE_KEY = "translationRuntimeStateV1";

function openRuntimeDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(FT_RUNTIME_DB, 1);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function runtimeCacheCount() {
  const db = await openDb();
  return await new Promise((resolve, reject) => {
    const tx = db.transaction(FT_RUNTIME_STORE, "readonly");
    const request = tx.objectStore(FT_RUNTIME_STORE).count();
    request.onsuccess = () => resolve(request.result || 0);
    request.onerror = () => reject(request.error);
  });
}

async function diagnosticsSnapshot() {
  const [sync, local, entries] = await Promise.all([
    chrome.storage.sync.get({
      enabled: true,
      autoTranslate: true,
      sourceLang: "auto",
      targetLang: "zh-CN",
      skipTargetLanguage: true,
      chatMode: true,
      inputPreview: true
    }),
    chrome.storage.local.get({
      translationProvider: "azure",
      fallbackGoogle: true,
      azureEndpoint: "https://api.cognitive.microsofttranslator.com",
      azureRegion: "",
      azureKey: "",
      providerPoolLastRouteV1: null,
      [FT_RUNTIME_STATE_KEY]: null
    }),
    runtimeCacheCount().catch(() => 0)
  ]);

  return {
    provider: local.translationProvider === "oci-proxy" ? "azure" : local.translationProvider,
    fallbackGoogle: local.fallbackGoogle !== false,
    azureKeySet: Boolean(local.azureKey),
    azureEndpoint: local.azureEndpoint,
    azureRegionSet: Boolean(local.azureRegion),
    cacheEntries: entries,
    enabled: Boolean(sync.enabled),
    autoTranslate: Boolean(sync.autoTranslate),
    sourceLang: sync.sourceLang,
    targetLang: sync.targetLang,
    skipTargetLanguage: sync.skipTargetLanguage !== false,
    chatMode: Boolean(sync.chatMode),
    inputPreview: Boolean(sync.inputPreview),
    lastRuntime: local[FT_RUNTIME_STATE_KEY] || null,
    providerPoolLastRoute: local.providerPoolLastRouteV1 || null
  };
}

function relayToSender(sender, payload) {
  return new Promise((resolve, reject) => {
    if (!sender?.tab?.id) {
      reject(new Error("无法确定当前标签页"));
      return;
    }
    const timer = setTimeout(() => reject(new Error("网页响应超时，请刷新网页")), 5000);
    const options = Number.isInteger(sender.frameId) ? { frameId: sender.frameId } : undefined;
    try {
      chrome.tabs.sendMessage(sender.tab.id, payload, options, response => {
        clearTimeout(timer);
        const lastError = chrome.runtime.lastError;
        if (lastError) reject(new Error(lastError.message));
        else resolve(response || { ok: true });
      });
    } catch (error) {
      clearTimeout(timer);
      reject(error);
    }
  });
}

async function openOptionsTab() {
  const url = chrome.runtime.getURL("options/options.html");
  if (chrome.tabs?.create) {
    const created = chrome.tabs.create({ url });
    if (created && typeof created.then === "function") await created;
    return { ok: true, url, method: "tabs.create" };
  }
  if (typeof chrome.runtime.openOptionsPage === "function") {
    const opened = chrome.runtime.openOptionsPage();
    if (opened && typeof opened.then === "function") await opened;
    return { ok: true, url, method: "openOptionsPage" };
  }
  throw new Error("当前浏览器不支持打开扩展设置页");
}

async function handleFloatingAction(sender, action) {
  if (action === "get-state") {
    const state = await relayToSender(sender, { type: "FT_GET_PAGE_STATE" });
    return { ok: Boolean(state?.ok), state };
  }
  if (action === "pause-toggle") {
    const current = await relayToSender(sender, { type: "FT_GET_PAGE_STATE" });
    const state = await relayToSender(sender, { type: "FT_SET_PAUSED", paused: !Boolean(current?.paused) });
    return { ok: Boolean(state?.ok), state };
  }
  if (action === "translate-now") {
    const result = await relayToSender(sender, { type: "FT_TRANSLATE_NOW" });
    const state = await relayToSender(sender, { type: "FT_GET_PAGE_STATE" });
    return { ok: Boolean(result?.ok), state };
  }
  if (action === "rescan") {
    const result = await relayToSender(sender, { type: "FT_RESCAN_PAGE" });
    const state = await relayToSender(sender, { type: "FT_GET_PAGE_STATE" });
    return { ok: Boolean(result?.ok), state };
  }
  if (action === "restore") {
    const result = await relayToSender(sender, { type: "FT_RESTORE_PAGE" });
    const state = await relayToSender(sender, { type: "FT_GET_PAGE_STATE" });
    return { ok: Boolean(result?.ok), state };
  }
  throw new Error(`未知悬浮窗操作：${action}`);
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "FT_BACKGROUND_PING") {
    sendResponse({ ok: true, at: Date.now() });
    return false;
  }

  if (message?.type === "FT_OPEN_OPTIONS") {
    openOptionsTab()
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_DIAGNOSTICS") {
    diagnosticsSnapshot()
      .then(diagnostics => sendResponse({ ok: true, diagnostics }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_FLOATING_PAGE_ACTION") {
    handleFloatingAction(sender, String(message.action || "get-state"))
      .then(sendResponse)
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error), state: { lastError: String(error?.message || error) } }));
    return true;
  }

  if (message?.type === "FT_FORCE_PAGE_TRANSLATION") {
    relayToSender(sender, { type: "FT_TRANSLATE_NOW", reason: message.reason || "mixed-page" })
      .then(response => sendResponse(response?.ok ? response : { ok: true }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  return false;
});
