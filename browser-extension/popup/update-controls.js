(() => {
  const $ = id => document.getElementById(id);
  const versionNode = $("extensionVersion");
  const statusNode = $("updateStatus");
  const checkButton = $("checkExtensionUpdate");
  const applyButton = $("applyExtensionUpdate");
  const card = $("updateCard");
  if (!versionNode || !statusNode || !checkButton || !applyButton || !card) return;

  const manifest = chrome.runtime.getManifest();
  versionNode.textContent = `当前版本：v${manifest.version}`;

  const family = globalThis.__FT_BROWSER_FAMILY__ || "chromium";
  if (family === "firefox") {
    statusNode.textContent = "Firefox 版不使用 Chromium CRX 更新通道。";
    checkButton.hidden = true;
    applyButton.hidden = true;
  }

  function setStatus(text) {
    statusNode.textContent = text;
  }

  async function requestUpdate() {
    checkButton.disabled = true;
    setStatus("正在向浏览器请求检查更新…");
    try {
      if (typeof chrome.runtime.requestUpdateCheck !== "function") {
        throw new Error("当前浏览器没有提供 requestUpdateCheck");
      }
      const result = await chrome.runtime.requestUpdateCheck();
      const status = result?.status || "unknown";
      if (status === "no_update") {
        setStatus(`已是最新版 · v${manifest.version}`);
        applyButton.hidden = true;
      } else if (status === "update_available") {
        const version = result?.version ? ` v${result.version}` : "";
        setStatus(`发现新版${version}，浏览器正在准备更新。`);
        applyButton.hidden = false;
      } else if (status === "throttled") {
        setStatus("检查过于频繁，被浏览器暂时限流；稍后再试。自动检查仍会继续。");
      } else {
        setStatus(`更新检查返回：${status}`);
      }
    } catch (error) {
      setStatus(`当前浏览器未完成标准更新检查：${error?.message || error}`);
    } finally {
      checkButton.disabled = false;
    }
  }

  if (family !== "firefox") {
    checkButton.addEventListener("click", requestUpdate);
    applyButton.addEventListener("click", () => {
      setStatus("正在重新加载扩展以应用已准备好的更新…");
      setTimeout(() => chrome.runtime.reload(), 120);
    });
  }

  // Quetta 等 Android Chromium 对 runtime.openOptionsPage() 可能无响应。
  // 捕获阶段直接使用 tabs.create 打开扩展内部 options 页面；失败再走后台/标准 API。
  const openOptions = $("openOptions");
  if (openOptions) {
    openOptions.addEventListener("click", async event => {
      event.preventDefault();
      event.stopImmediatePropagation();
      const url = chrome.runtime.getURL("options/options.html");

      try {
        if (chrome.tabs?.create) {
          const created = chrome.tabs.create({ url });
          if (created && typeof created.then === "function") await created;
          window.close?.();
          return;
        }
      } catch {}

      try {
        const response = await chrome.runtime.sendMessage({ type: "FT_OPEN_OPTIONS" });
        if (response?.ok) {
          window.close?.();
          return;
        }
      } catch {}

      try {
        if (typeof chrome.runtime.openOptionsPage === "function") {
          const opened = chrome.runtime.openOptionsPage();
          if (opened && typeof opened.then === "function") await opened;
          window.close?.();
          return;
        }
      } catch {}

      setStatus("当前浏览器无法直接打开高级设置，请到扩展详情页进入选项。 ");
    }, true);
  }
})();
