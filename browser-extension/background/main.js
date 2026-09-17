(() => {
  function safeImport(path, required = false) {
    try {
      importScripts(path);
      return true;
    } catch (error) {
      console.error(`[浮译] 后台模块加载失败：${path}`, error);
      if (required) throw error;
      return false;
    }
  }

  // 部分 Android Chromium 对 contextMenus 实现不完整。
  // 它不是翻译核心能力，缺失时用空实现，避免后台 Service Worker 因此整体启动失败。
  try {
    if (!globalThis.chrome?.contextMenus) {
      globalThis.chrome.contextMenus = {
        removeAll(callback) { try { callback?.(); } catch {} },
        create() {},
        onClicked: { addListener() {} }
      };
    } else {
      chrome.contextMenus.removeAll ||= callback => { try { callback?.(); } catch {} };
      chrome.contextMenus.create ||= () => {};
      chrome.contextMenus.onClicked ||= { addListener() {} };
      chrome.contextMenus.onClicked.addListener ||= () => {};
    }
  } catch (error) {
    console.warn("[浮译] contextMenus 兼容层未能安装", error);
  }

  // shared/provider/telemetry 都属于增强模块；任何一个失败都不应拖死核心翻译。
  safeImport("../compat/browser-api.js", true);
  safeImport("../shared/crypto-lite.js");
  safeImport("../shared/glossary-core.js");
  safeImport("service-worker.js", true);
  safeImport("provider-pool.js");
  safeImport("glossary-runtime.js");
  safeImport("runtime-telemetry.js");
  safeImport("cache-stats.js");
  safeImport("runtime-extras.js");
})();
