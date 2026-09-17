const FT_RUNTIME_DB = "floating-translator-cache";
const FT_RUNTIME_STORE = "translations";

function openRuntimeDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(FT_RUNTIME_DB, 1);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function runtimeCacheCount() {
  const db = await openRuntimeDb();
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
      azureKey: ""
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
    inputPreview: Boolean(sync.inputPreview)
  };
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "FT_DIAGNOSTICS") {
    diagnosticsSnapshot()
      .then(diagnostics => sendResponse({ ok: true, diagnostics }))
      .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
    return true;
  }

  if (message?.type === "FT_FORCE_PAGE_TRANSLATION") {
    if (!sender?.tab?.id) {
      sendResponse({ ok: false, error: "无法确定当前标签页" });
      return false;
    }
    const options = Number.isInteger(sender.frameId) ? { frameId: sender.frameId } : undefined;
    chrome.tabs.sendMessage(sender.tab.id, { type: "FT_TRANSLATE_NOW", reason: message.reason || "mixed-page" }, options, response => {
      const lastError = chrome.runtime.lastError;
      if (lastError) sendResponse({ ok: false, error: lastError.message });
      else sendResponse(response?.ok ? response : { ok: true });
    });
    return true;
  }

  return false;
});