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
    return;
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
        setStatus("检查过于频繁，被浏览器暂时限流；稍后再试。自动检查仍会继续。 ");
      } else {
        setStatus(`更新检查返回：${status}`);
      }
    } catch (error) {
      setStatus(`当前浏览器未完成标准更新检查：${error?.message || error}`);
    } finally {
      checkButton.disabled = false;
    }
  }

  checkButton.addEventListener("click", requestUpdate);
  applyButton.addEventListener("click", () => {
    setStatus("正在重新加载扩展以应用已准备好的更新…");
    setTimeout(() => chrome.runtime.reload(), 120);
  });
})();
