const SYNC_DEFAULTS = { enabled: true, autoTranslate: true };
const LOCAL_DEFAULTS = {
  translationProvider: "azure",
  fallbackGoogle: true,
  azureEndpoint: "https://api.cognitive.microsofttranslator.com",
  azureRegion: "",
  azureKey: "",
  requestTimeoutMs: 15000,
  maxRetries: 3,
  cacheMaxEntries: 30000,
  cacheTtlDays: 30,
  glossaryEnabled: true,
  glossaryCaseSensitive: false,
  glossaryEntries: []
};

const SYNC_BACKUP_KEYS = [
  "enabled", "autoTranslate", "sourceLang", "targetLang", "displayMode", "siteRules",
  "skipTargetLanguage", "chatMode", "inputPreview", "inputSourceLang", "inputTargetLang", "inputPreviewDelay"
];
const LOCAL_BACKUP_KEYS = [
  "translationProvider", "fallbackGoogle", "azureEndpoint", "azureRegion", "requestTimeoutMs", "maxRetries",
  "cacheMaxEntries", "cacheTtlDays", "glossaryEnabled", "glossaryCaseSensitive", "glossaryEntries",
  "siteExclusionsV1", "siteInputLanguagesV1"
];
const BACKUP_SCHEMA = "floating-translator-settings";
const BACKUP_VERSION = 1;
const $ = id => document.getElementById(id);

function pick(source, keys) {
  const out = {};
  for (const key of keys) if (source?.[key] !== undefined) out[key] = source[key];
  return out;
}

function parseGlossaryText(value) {
  const entries = [];
  const invalid = [];
  const seen = new Set();
  String(value || "").split(/\r?\n/).forEach((raw, index) => {
    const line = raw.trim();
    if (!line || line.startsWith("#")) return;
    const match = line.match(/^(.*?)\s*(?:=>|→|->)\s*(.+)$/);
    if (!match) return invalid.push(index + 1);
    const source = match[1].trim();
    const target = match[2].trim();
    if (!source || !target) return invalid.push(index + 1);
    if (seen.has(source)) return;
    seen.add(source);
    entries.push({ source, target });
  });
  return { entries: entries.slice(0, 300), invalid, truncated: entries.length > 300 };
}

function serializeGlossary(entries) {
  return (Array.isArray(entries) ? entries : []).map(item => {
    const source = String(item?.source ?? item?.from ?? "").trim();
    const target = String(item?.target ?? item?.to ?? "").trim();
    return source && target ? `${source} => ${target}` : "";
  }).filter(Boolean).join("\n");
}

function refreshGlossaryStatus() {
  const parsed = parseGlossaryText($("glossaryText").value);
  const notes = [`有效规则 ${parsed.entries.length} 条`];
  if (parsed.invalid.length) notes.push(`格式错误行：${parsed.invalid.slice(0, 8).join("、")}${parsed.invalid.length > 8 ? "…" : ""}`);
  if (parsed.truncated) notes.push("超过 300 条，只保存前 300 条");
  $("glossaryStatus").textContent = `术语表：${notes.join(" · ")}`;
  return parsed;
}

function setStatus(message) {
  $("status").textContent = message;
  clearTimeout(setStatus.timer);
  setStatus.timer = setTimeout(() => $("status").textContent = "", 4600);
}

function toggleProviderSections() {
  $("azureSection").hidden = $("provider").value !== "azure";
}

function routeLabel(route) {
  if (route === "azure") return "Azure";
  if (route === "google-web") return "Google Web";
  if (route === "google-fallback") return "Google 回退";
  if (route === "cache") return "缓存";
  return route || "暂无记录";
}

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
  $("cacheMaxEntries").value = String(local.cacheMaxEntries || 30000);
  $("cacheTtlDays").value = String(local.cacheTtlDays ?? 30);
  $("glossaryEnabled").checked = local.glossaryEnabled !== false;
  $("glossaryCaseSensitive").checked = Boolean(local.glossaryCaseSensitive);
  $("glossaryText").value = serializeGlossary(local.glossaryEntries);
  $("enabled").checked = Boolean(sync.enabled);
  $("autoTranslate").checked = Boolean(sync.autoTranslate);
  $("includeAzureKey").checked = false;
  toggleProviderSections();
  refreshGlossaryStatus();
  await Promise.all([refreshCacheStats(), refreshDiagnostics(), refreshUsage()]);
}

async function save({ showStatus = true, reload = true } = {}) {
  const parsedGlossary = refreshGlossaryStatus();
  const sync = { enabled: $("enabled").checked, autoTranslate: $("autoTranslate").checked };
  const local = {
    translationProvider: $("provider").value,
    fallbackGoogle: $("fallbackGoogle").checked,
    azureEndpoint: $("azureEndpoint").value.trim() || LOCAL_DEFAULTS.azureEndpoint,
    azureRegion: $("azureRegion").value.trim(),
    requestTimeoutMs: Math.max(3000, Math.min(45000, Number($("requestTimeoutMs").value) || 15000)),
    maxRetries: Math.max(0, Math.min(5, Number($("maxRetries").value) || 0)),
    cacheMaxEntries: Math.max(1000, Math.min(200000, Number($("cacheMaxEntries").value) || 30000)),
    cacheTtlDays: Math.max(0, Math.min(3650, Number($("cacheTtlDays").value) || 0)),
    glossaryEnabled: $("glossaryEnabled").checked,
    glossaryCaseSensitive: $("glossaryCaseSensitive").checked,
    glossaryEntries: parsedGlossary.entries
  };
  if ($("clearAzureKey").checked) local.azureKey = "";
  else if ($("azureKey").value.trim()) local.azureKey = $("azureKey").value.trim();

  await Promise.all([
    chrome.storage.sync.set(sync), chrome.storage.local.set(local), chrome.storage.local.remove(["ociProxyEndpoint", "ociProxyToken"])
  ]);
  $("azureKey").value = "";
  $("clearAzureKey").checked = false;
  if (showStatus) setStatus(`已保存${parsedGlossary.invalid.length ? `；${parsedGlossary.invalid.length} 行格式错误未保存` : ""}`);
  if (reload) await load();
}

async function testEngine() {
  setStatus("正在保存当前配置…");
  await save({ showStatus: false, reload: false });
  setStatus("正在测试…");
  const response = await chrome.runtime.sendMessage({
    type: "FT_TRANSLATE", texts: ["Hello, this is a translation test."], options: { sourceLang: "en", targetLang: "zh-CN" }
  });
  if (!response?.ok) throw new Error(response?.error || "测试失败");
  setStatus(`测试成功：${response.translations?.[0] || "已返回译文"}`);
  await Promise.all([refreshCacheStats(), refreshDiagnostics(), refreshUsage()]);
}

async function refreshDiagnostics() {
  const response = await chrome.runtime.sendMessage({ type: "FT_DIAGNOSTICS" });
  if (!response?.ok) {
    $("engineStatus").textContent = `引擎状态：读取失败${response?.error ? `（${response.error}）` : ""}`;
    $("runtimeStatus").textContent = "最近实际翻译路径：读取失败";
    return;
  }
  const d = response.diagnostics || {};
  const provider = d.provider === "google-web" ? "Google Web" : "Azure";
  const key = d.provider === "google-web" ? "无需 Key" : (d.azureKeySet ? "Key 已设置" : "Key 未设置");
  const fallback = d.fallbackGoogle ? "Google 回退开启" : "Google 回退关闭";
  $("engineStatus").textContent = `引擎状态：${provider} · ${key} · ${fallback}`;
  const r = d.lastRuntime;
  if (!r) $("runtimeStatus").textContent = "最近实际翻译路径：暂无记录";
  else {
    const at = r.at ? new Date(r.at).toLocaleString() : "-";
    $("runtimeStatus").textContent = `最近实际翻译路径：${routeLabel(r.actualRoute)} · ${r.ok === false ? "失败" : "成功"} · ${r.durationMs || 0}ms · ${at}`;
  }
}

async function refreshCacheStats() {
  const response = await chrome.runtime.sendMessage({ type: "FT_CACHE_STATS" });
  if (!response?.ok) {
    $("cacheStats").textContent = `缓存统计：读取失败${response?.error ? `（${response.error}）` : ""}`;
    return;
  }
  $("cacheStats").textContent = `缓存统计：${Number(response.entries || 0).toLocaleString()} 条译文缓存`;
}

function formatUsageBucket(bucket = {}) {
  const azure = bucket.azure || {};
  const google = bucket.googleWeb || {};
  return `Azure ${Number(azure.chars || 0).toLocaleString()} 字符 / ${Number(azure.requests || 0).toLocaleString()} 请求 · ` +
    `Google ${Number(google.chars || 0).toLocaleString()} 字符 / ${Number(google.requests || 0).toLocaleString()} 请求 · 缓存命中 ${Number(bucket.cacheHits || 0).toLocaleString()} 条`;
}

async function refreshUsage() {
  const response = await chrome.runtime.sendMessage({ type: "FT_USAGE_STATS" });
  if (!response?.ok) {
    $("usageToday").textContent = `今日用量：读取失败${response?.error ? `（${response.error}）` : ""}`;
    $("usageMonth").textContent = "本月用量：读取失败";
    return;
  }
  const usage = response.usage || {};
  $("usageToday").textContent = `今日用量（${usage.dayKey || "-"}）：${formatUsageBucket(usage.day)}`;
  $("usageMonth").textContent = `本月用量（${usage.monthKey || "-"}）：${formatUsageBucket(usage.month)}`;
}

async function pruneCache() {
  await save({ showStatus: false, reload: false });
  const response = await chrome.runtime.sendMessage({ type: "FT_PRUNE_CACHE" });
  if (!response?.ok) throw new Error(response?.error || "整理失败");
  await refreshCacheStats();
  setStatus(`缓存整理完成：删除 ${response.deleted || 0} 条，剩余 ${response.remaining || 0} 条`);
}

async function clearCache() {
  const response = await chrome.runtime.sendMessage({ type: "FT_CLEAR_CACHE" });
  if (!response?.ok) throw new Error(response?.error || "清理失败");
  await refreshCacheStats();
  setStatus("翻译缓存已清空");
}

async function resetUsage() {
  const response = await chrome.runtime.sendMessage({ type: "FT_RESET_USAGE_STATS" });
  if (!response?.ok) throw new Error(response?.error || "重置失败");
  await refreshUsage();
  setStatus("本地用量统计已重置");
}

async function diagnosticObject() {
  const [diagnostics, usage, cache] = await Promise.all([
    chrome.runtime.sendMessage({ type: "FT_DIAGNOSTICS" }),
    chrome.runtime.sendMessage({ type: "FT_USAGE_STATS" }),
    chrome.runtime.sendMessage({ type: "FT_CACHE_STATS" })
  ]);
  return {
    product: "FloatingTranslator Browser",
    version: chrome.runtime.getManifest().version,
    generatedAt: new Date().toISOString(),
    userAgent: navigator.userAgent,
    diagnostics: diagnostics?.diagnostics || null,
    usage: usage?.usage || null,
    cacheEntries: cache?.entries ?? null
  };
}

async function copyDiagnostics() {
  const data = await diagnosticObject();
  if (data.diagnostics) delete data.diagnostics.azureKey;
  await navigator.clipboard.writeText(JSON.stringify(data, null, 2));
  setStatus("诊断信息已复制，不包含 Azure Key");
}

async function exportSettings() {
  const [syncRaw, localRaw] = await Promise.all([chrome.storage.sync.get(null), chrome.storage.local.get(null)]);
  const localKeys = $("includeAzureKey").checked ? [...LOCAL_BACKUP_KEYS, "azureKey"] : LOCAL_BACKUP_KEYS;
  const payload = {
    schema: BACKUP_SCHEMA,
    schemaVersion: BACKUP_VERSION,
    extensionVersion: chrome.runtime.getManifest().version,
    exportedAt: new Date().toISOString(),
    sync: pick(syncRaw, SYNC_BACKUP_KEYS),
    local: pick(localRaw, localKeys)
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `FloatingTranslator-settings-v${chrome.runtime.getManifest().version}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  setStatus($("includeAzureKey").checked ? "设置已导出（包含 Azure Key，请妥善保管）" : "设置已导出（未包含 Azure Key）");
}

async function importSettingsFile(file) {
  const text = await file.text();
  const parsed = JSON.parse(text);
  if (parsed?.schema !== BACKUP_SCHEMA || Number(parsed?.schemaVersion) !== BACKUP_VERSION) throw new Error("不是支持的 FloatingTranslator 设置备份");
  const sync = pick(parsed.sync || {}, SYNC_BACKUP_KEYS);
  const local = pick(parsed.local || {}, [...LOCAL_BACKUP_KEYS, "azureKey"]);
  if (local.translationProvider === "oci-proxy") local.translationProvider = "azure";
  await Promise.all([chrome.storage.sync.set(sync), chrome.storage.local.set(local)]);
  setStatus(`设置恢复成功${local.azureKey !== undefined ? "（备份中包含 Key）" : "（保留当前 Key）"}`);
  await load();
}

$("provider").addEventListener("change", toggleProviderSections);
$("glossaryText").addEventListener("input", refreshGlossaryStatus);
$("save").addEventListener("click", () => save().catch(error => setStatus(`保存失败：${error?.message || error}`)));
$("testEngine").addEventListener("click", () => testEngine().catch(error => setStatus(`测试失败：${error?.message || error}`)));
$("refreshUsage").addEventListener("click", () => refreshUsage().catch(error => setStatus(`用量读取失败：${error?.message || error}`)));
$("resetUsage").addEventListener("click", () => resetUsage().catch(error => setStatus(`重置失败：${error?.message || error}`)));
$("refreshCacheStats").addEventListener("click", () => refreshCacheStats().catch(error => setStatus(`统计失败：${error?.message || error}`)));
$("pruneCache").addEventListener("click", () => pruneCache().catch(error => setStatus(`整理失败：${error?.message || error}`)));
$("clearCache").addEventListener("click", () => clearCache().catch(error => setStatus(`清理失败：${error?.message || error}`)));
$("copyDiagnostics").addEventListener("click", () => copyDiagnostics().catch(error => setStatus(`复制失败：${error?.message || error}`)));
$("exportSettings").addEventListener("click", () => exportSettings().catch(error => setStatus(`导出失败：${error?.message || error}`)));
$("importSettings").addEventListener("click", () => $("importFile").click());
$("importFile").addEventListener("change", event => {
  const file = event.target.files?.[0];
  if (file) importSettingsFile(file).catch(error => setStatus(`导入失败：${error?.message || error}`));
  event.target.value = "";
});

load().catch(error => setStatus(`加载失败：${error?.message || error}`));