const SYNC_DEFAULTS = { enabled: true, autoTranslate: true };
const LOCAL_DEFAULTS = {
  translationProvider: "azure",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: ""
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
  $("enabled").checked = Boolean(sync.enabled);
  $("autoTranslate").checked = Boolean(sync.autoTranslate);
  toggleProviderSections();
}

function toggleProviderSections() {
  $("azureSection").hidden = $("provider").value !== "azure";
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
    azureRegion: $("azureRegion").value.trim()
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
  $("status").textContent = "已保存";
  setTimeout(() => $("status").textContent = "", 1800);
  await load();
}

$("provider").addEventListener("change", toggleProviderSections);
$("save").addEventListener("click", () => save().catch(error => {
  $("status").textContent = `保存失败：${error?.message || error}`;
}));

load().catch(error => {
  $("status").textContent = `加载失败：${error?.message || error}`;
});
