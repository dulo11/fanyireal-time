const SYNC_DEFAULTS = { enabled: true, autoTranslate: true };
const LOCAL_DEFAULTS = {
  translationProvider: "azure",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: "",
  requestTimeoutMs: 15000,
  maxRetries: 3
};

const $ = id => document.getElementById(id);

async function load() {
  const [sync, localRaw] = await Promise.all([
    chrome.storage.sync.get(SYNC_DEFAULTS),
    chrome.storage.local.get(null)
  ]);

  const local = { ...LOCAL_DEFAULTS, ...localRaw };
  if (local.translationProvider === "oci-proxy") local.translationProvider = "azure";

  $("provider").value = local.translationProvider || "azure";
  $("fallbackGoogle").checked = local.fallbackGoogle !== false;
  $("azureEndpoint").value = local.azureEndpoint || LOCAL_DEFAULTS.azureEndpoint;
  $("azureRegion").value = local.azureRegion || "";
  $("azureKey").placeholder = local.azureKey ? "已保存（留空表示不修改）" : "请输入 Azure Translator Key";
  $("requestTimeoutMs").value = String(local.requestTimeoutMs || 15000);
  $("maxRetries").value = String(local.maxRetries ?? 3);
  $("enabled").checked = Boolean(sync.enabled);
  $("autoTranslate").checked = Boolean(sync.autoTranslate);
  toggleProviderSections();
}

function toggleProviderSections() {
  $("azureSection").hidden = $("provider").value !== "azure";
}

function setStatus(message) {
  $("status").textContent = message;
  clearTimeout(setStatus.timer);
  setStatus.timer = setTimeout(() => $("status").textContent = "", 3200);
}

async function save() {
  const sync = {
    enabled: $("enabled").checked,
    autoTranslate: $("autoTranslate").checked
  };

  const local = {
    translationProvider: $("provider").value,
    fallbackGoogle: $("fallbackGoogle").checked,
    azureEndpoint: $("azureEndpoint").value.trim() || LOCAL_DEFAULTS.azureEndpoint,
    azureRegion: $("azureRegion").value.trim(),
    requestTimeoutMs: Math.max(3000, Math.min(45000, Number($("requestTimeoutMs").value) || 15000)),
    maxRetries: Math.max(0, Math.min(5, Number($("maxRetries").value) || 0))
  };

  if ($("clearAzureKey").checked) local.azureKey = "";
  else if ($("azureKey").value.trim()) local.azureKey = $("azureKey").value.trim();

  await Promise.all([
    chrome.storage.sync.set(sync),
    chrome.storage.local.set(local),
    chrome.storage.local.remove(["ociProxyEndpoint", "ociProxyToken"])
  ]);

  $("azureKey").value = "";
  $("clearAzureKey").checked = false;
  setStatus("已保存");
  await load();
}

async function testEngine() {
  setStatus("正在测试…");
  const response = await chrome.runtime.sendMessage({
    type: "FT_TRANSLATE",
    texts: ["Hello, this is a translation test."],
    options: { sourceLang: "en", targetLang: "zh-CN" }
  });
  if (!response?.ok) throw new Error(response?.error || "测试失败");
  setStatus(`测试成功：${response.translations?.[0] || "已返回译文"}`);
}

async function clearCache() {
  const response = await chrome.runtime.sendMessage({ type: "FT_CLEAR_CACHE" });
  if (!response?.ok) throw new Error(response?.error || "清理失败");
  setStatus("翻译缓存已清空");
}

$("provider").addEventListener("change", toggleProviderSections);
$("save").addEventListener("click", () => save().catch(error => setStatus(`保存失败：${error?.message || error}`)));
$("testEngine").addEventListener("click", () => testEngine().catch(error => setStatus(`测试失败：${error?.message || error}`)));
$("clearCache").addEventListener("click", () => clearCache().catch(error => setStatus(`清理失败：${error?.message || error}`)));

load().catch(error => setStatus(`加载失败：${error?.message || error}`));
