(() => {
  if (globalThis.__FT_RUNTIME_TELEMETRY__) return;
  globalThis.__FT_RUNTIME_TELEMETRY__ = true;

  const baseTranslateBatch = globalThis.translateBatch;
  if (typeof baseTranslateBatch !== "function") return;

  const STATE_KEY = "translationRuntimeStateV1";

  async function requestedProvider(options = {}) {
    if (options?.provider) return String(options.provider);
    const local = await chrome.storage.local.get({ translationProvider: "azure" });
    return local.translationProvider === "oci-proxy" ? "azure" : (local.translationProvider || "azure");
  }

  async function usageCounters() {
    try {
      const usage = await globalThis.getUsageStats?.();
      const day = usage?.day || {};
      return {
        azure: Number(day.azure?.requests || 0),
        google: Number(day.googleWeb?.requests || 0),
        cacheHits: Number(day.cacheHits || 0)
      };
    } catch {
      return { azure: 0, google: 0, cacheHits: 0 };
    }
  }

  function charCount(texts) {
    return (Array.isArray(texts) ? texts : []).reduce((sum, text) => sum + String(text ?? "").length, 0);
  }

  async function writeState(state) {
    try { await chrome.storage.local.set({ [STATE_KEY]: state }); } catch {}
  }

  globalThis.translateBatch = async function telemetryTranslateBatch(texts, options = {}) {
    const startedAt = Date.now();
    const requested = await requestedProvider(options);
    const before = await usageCounters();

    try {
      const result = await baseTranslateBatch(texts, options);
      const after = await usageCounters();
      const azureDelta = Math.max(0, after.azure - before.azure);
      const googleDelta = Math.max(0, after.google - before.google);
      const cacheDelta = Math.max(0, after.cacheHits - before.cacheHits);

      let route = "cache";
      let fallbackUsed = false;
      if (azureDelta > 0) route = "azure";
      else if (googleDelta > 0) {
        fallbackUsed = requested !== "google-web";
        route = fallbackUsed ? "google-fallback" : "google-web";
      } else if (cacheDelta === 0 && !Array.isArray(texts)) {
        route = requested;
      }

      await writeState({
        ok: true,
        requestedProvider: requested,
        actualRoute: route,
        fallbackUsed,
        sourceLang: options?.sourceLang || "auto",
        targetLang: options?.targetLang || "",
        chars: charCount(texts),
        itemCount: Array.isArray(texts) ? texts.length : 0,
        durationMs: Date.now() - startedAt,
        at: Date.now(),
        error: ""
      });
      return result;
    } catch (error) {
      await writeState({
        ok: false,
        requestedProvider: requested,
        actualRoute: requested,
        fallbackUsed: false,
        sourceLang: options?.sourceLang || "auto",
        targetLang: options?.targetLang || "",
        chars: charCount(texts),
        itemCount: Array.isArray(texts) ? texts.length : 0,
        durationMs: Date.now() - startedAt,
        at: Date.now(),
        error: String(error?.message || error)
      });
      throw error;
    }
  };
})();