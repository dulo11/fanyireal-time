const SYNC_DEFAULTS = { enabled: true, autoTranslate: true };
const LOCAL_DEFAULTS = {
  translationProvider: "google-web",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: "",
  ociProxyEndpoint: "",
  ociProxyToken: ""
};

const $ = id => document.getElementById(id);

async function load() {
  const [sync, local] = await Promise.all([
    chrome.storage.sync.get(SYNC_DEFAULTS),
    chrome.storage.local.get(LOCAL_DEFAULTS)
  ]);

  $("provider").value = local.translationProvider || "google-web";
  $("fallbackGoogle").checked = local.fallbackGoogle !== false;
  $("azureEndpoint").value = local.azureEndpoint || LOCAL_DEFAULTS.azureEndpoint;
  $("azureRegion").value = local.azureRegion || "";
  $("azureKey").placeholder = local.azureKey ? "已保存（留空表示不修改）" : "请输入 Key";
  $("ociProxyEndpoint").value = local.ociProxyEndpoint || "";
  $("ociProxyToken").placeholder = local.ociProxyToken ? "已保存（留空表示不修改）" : "可选 Token";
  $("enabled").checked = Boolean(sync.enabled);
  $("autoTranslate").checked = Boolean(sync.autoTranslate);
  toggleProviderSections();
}

function toggleProviderSections() {
  const provider = $("provider").value;
  $("azureSection").hidden = provider !== "azure";
  $("ociSection").hidden = provider !== "oci-proxy";
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
    ociProxyEndpoint: $("ociProxyEndpoint").value.trim()
  };

  if ($("clearAzureKey").checked) local.azureKey = "";
  else if ($("azureKey").value.trim()) local.azureKey = $("azureKey").value.trim();

  if ($("clearOciProxyToken").checked) local.ociProxyToken = "";
  else if ($("ociProxyToken").value.trim()) local.ociProxyToken = $("ociProxyToken").value.trim();

  await Promise.all([
    chrome.storage.sync.set(sync),
    chrome.storage.local.set(local)
  ]);

  $("azureKey").value = "";
  $("clearAzureKey").checked = false;
  $("ociProxyToken").value = "";
  $("clearOciProxyToken").checked = false;
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
