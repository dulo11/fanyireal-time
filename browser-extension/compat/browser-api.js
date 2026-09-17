(() => {
  if (globalThis.__FT_COMPAT_API_READY__) return;
  globalThis.__FT_COMPAT_API_READY__ = true;

  let nativeBrowser = null;
  try {
    if (typeof browser !== "undefined" && browser?.runtime?.getURL) nativeBrowser = browser;
  } catch {}

  // Firefox 的标准 WebExtensions API 使用 browser.* + Promise。
  // 主代码历史上使用 chrome.*，因此在 Firefox 中优先把 chrome 指向 browser，
  // 让现有 async/await 逻辑保持一致。Chromium 中没有 browser 时保持原样。
  if (nativeBrowser) {
    try {
      const scheme = String(nativeBrowser.runtime.getURL(""));
      if (scheme.startsWith("moz-extension://") || scheme.startsWith("safari-web-extension://")) {
        try {
          Object.defineProperty(globalThis, "chrome", {
            configurable: true,
            writable: true,
            value: nativeBrowser
          });
        } catch {
          try { globalThis.chrome = nativeBrowser; } catch {}
        }
        globalThis.__FT_BROWSER_FAMILY__ = scheme.startsWith("moz-extension://") ? "firefox" : "safari";
      }
    } catch {}
  }

  if (!globalThis.__FT_BROWSER_FAMILY__) {
    globalThis.__FT_BROWSER_FAMILY__ = "chromium";
  }
})();
