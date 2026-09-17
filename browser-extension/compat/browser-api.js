(() => {
  if (globalThis.__FT_COMPAT_API_READY__) return;
  globalThis.__FT_COMPAT_API_READY__ = true;

  let nativeBrowser = null;
  try {
    if (typeof browser !== "undefined" && browser?.runtime?.getURL) nativeBrowser = browser;
  } catch {}

  // Firefox 的标准 WebExtensions API 使用 browser.* + Promise。
  // 主代码历史上使用 chrome.*，因此在 Firefox 中优先把 chrome 指向 browser。
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

  if (!globalThis.__FT_BROWSER_FAMILY__) globalThis.__FT_BROWSER_FAMILY__ = "chromium";

  const family = globalThis.__FT_BROWSER_FAMILY__;

  function timeoutPromise(executor, timeoutMs, label) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(new Error(`${label}超时`));
      }, Math.max(500, Number(timeoutMs || 5000)));

      const ok = value => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      };
      const fail = error => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error(String(error || `${label}失败`)));
      };

      try { executor(ok, fail); }
      catch (error) { fail(error); }
    });
  }

  function lastErrorMessage() {
    try { return chrome?.runtime?.lastError?.message || ""; }
    catch { return ""; }
  }

  // Quetta / 部分 Android Chromium 暴露了 Chrome API，但某些消息 API 仍然只可靠支持 callback。
  // 主代码大量使用 await chrome.runtime.sendMessage / tabs.sendMessage，若浏览器不给 Promise，
  // 就会出现 Popup 永远“正在读取”、网页翻译请求无返回、悬浮窗按钮无反应。
  if (family === "chromium" && globalThis.chrome?.runtime) {
    const runtime = chrome.runtime;
    const nativeRuntimeSend = typeof runtime.sendMessage === "function" ? runtime.sendMessage.bind(runtime) : null;
    const nativeOpenOptions = typeof runtime.openOptionsPage === "function" ? runtime.openOptionsPage.bind(runtime) : null;

    if (nativeRuntimeSend) {
      const runtimeSendCompat = (...args) => {
        if (typeof args.at(-1) === "function") return nativeRuntimeSend(...args);
        return timeoutPromise((resolve, reject) => {
          nativeRuntimeSend(...args, response => {
            const message = lastErrorMessage();
            if (message) reject(new Error(message));
            else resolve(response);
          });
        }, /^FT_TRANSLATE/.test(args[0]?.type || "") ? 120000 : 6000, "扩展消息通信");
      };

      try { runtime.sendMessage = runtimeSendCompat; }
      catch {
        try { Object.defineProperty(runtime, "sendMessage", { configurable: true, value: runtimeSendCompat }); } catch {}
      }

      // 手机 Chromium 对 openOptionsPage 支持不一致。优先让后台直接创建 options 标签页。
      const openOptionsCompat = callback => {
        const task = timeoutPromise((resolve, reject) => {
          nativeRuntimeSend({ type: "FT_OPEN_OPTIONS" }, response => {
            const message = lastErrorMessage();
            if (message) return reject(new Error(message));
            if (response?.ok) return resolve(response);
            reject(new Error(response?.error || "无法打开完整设置"));
          });
        }, 6000, "打开完整设置").catch(async error => {
          if (!nativeOpenOptions) throw error;
          const result = nativeOpenOptions();
          if (result && typeof result.then === "function") await result;
          return { ok: true, method: "native-open-options" };
        });
        if (typeof callback === "function") task.then(() => callback(), () => callback());
        return task;
      };
      try { runtime.openOptionsPage = openOptionsCompat; }
      catch {
        try { Object.defineProperty(runtime, "openOptionsPage", { configurable: true, value: openOptionsCompat }); } catch {}
      }
    }

    function wrapAsync(owner, name, timeoutMs = 5000) {
      if (typeof owner?.[name] !== "function") return;
      const native = owner[name].bind(owner);
      owner[name] = (...args) => {
        if (typeof args.at(-1) === "function") return native(...args);
        return timeoutPromise((resolve, reject) => {
          const result = native(...args, value => {
            const message = lastErrorMessage();
            if (message) reject(new Error(message)); else resolve(value);
          });
          if (result && typeof result.then === "function") result.then(resolve, reject);
        }, timeoutMs, name);
      };
    }
    for (const area of [chrome.storage?.sync, chrome.storage?.local]) {
      for (const name of ["get", "set", "remove", "clear"]) wrapAsync(area, name);
    }
    for (const name of ["executeScript", "insertCSS"]) wrapAsync(chrome.scripting, name, 10000);
    wrapAsync(chrome.tabs, "create");

    if (globalThis.chrome?.tabs) {
      const tabs = chrome.tabs;
      const nativeTabsSend = typeof tabs.sendMessage === "function" ? tabs.sendMessage.bind(tabs) : null;
      const nativeTabsQuery = typeof tabs.query === "function" ? tabs.query.bind(tabs) : null;

      if (nativeTabsSend) {
        const tabsSendCompat = (...args) => {
          if (typeof args.at(-1) === "function") return nativeTabsSend(...args);
          return timeoutPromise((resolve, reject) => {
            nativeTabsSend(...args, response => {
              const message = lastErrorMessage();
              if (message) reject(new Error(message));
              else resolve(response);
            });
          }, 5000, "当前网页通信");
        };
        try { tabs.sendMessage = tabsSendCompat; }
        catch {
          try { Object.defineProperty(tabs, "sendMessage", { configurable: true, value: tabsSendCompat }); } catch {}
        }
      }

      if (nativeTabsQuery) {
        const tabsQueryCompat = (...args) => {
          if (typeof args.at(-1) === "function") return nativeTabsQuery(...args);
          return timeoutPromise((resolve, reject) => {
            nativeTabsQuery(...args, result => {
              const message = lastErrorMessage();
              if (message) reject(new Error(message));
              else resolve(result || []);
            });
          }, 5000, "读取当前标签页");
        };
        try { tabs.query = tabsQueryCompat; }
        catch {
          try { Object.defineProperty(tabs, "query", { configurable: true, value: tabsQueryCompat }); } catch {}
        }
      }
    }
  }

  globalThis.FTBrowserCompat = Object.freeze({
    family,
    timeoutPromise
  });
})();
