const FT_STATS_DB_NAME = "floating-translator-cache";
const FT_STATS_DB_VERSION = 1;
const FT_STATS_STORE = "translations";

function ftOpenStatsDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(FT_STATS_DB_NAME, FT_STATS_DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(FT_STATS_STORE)) db.createObjectStore(FT_STATS_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function ftCacheStats() {
  const db = await ftOpenStatsDb();
  const entries = await new Promise((resolve, reject) => {
    const tx = db.transaction(FT_STATS_STORE, "readonly");
    const request = tx.objectStore(FT_STATS_STORE).count();
    request.onsuccess = () => resolve(request.result || 0);
    request.onerror = () => reject(request.error);
  });
  return { entries };
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== "FT_CACHE_STATS") return false;
  ftCacheStats()
    .then(stats => sendResponse({ ok: true, ...stats }))
    .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
  return true;
});
