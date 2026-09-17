(() => {
  if (window.top !== window || window.__FT_FLOATING_PANEL_BRIDGE__) return;
  window.__FT_FLOATING_PANEL_BRIDGE__ = true;

  window.addEventListener("ft-floating-command", async event => {
    const detail = event.detail || {};
    try {
      const response = await chrome.runtime.sendMessage({
        type: "FT_FLOATING_PAGE_ACTION",
        action: detail.action || "get-state"
      });
      const state = response?.state || response;
      window.dispatchEvent(new CustomEvent("ft-floating-state", {
        detail: {
          ...(state && typeof state === "object" ? state : {}),
          requestId: detail.requestId,
          bridgeOk: Boolean(response?.ok ?? state?.ok)
        }
      }));
    } catch (error) {
      window.dispatchEvent(new CustomEvent("ft-floating-state", {
        detail: { requestId: detail.requestId, bridgeOk: false, lastError: String(error?.message || error) }
      }));
    }
  });
})();