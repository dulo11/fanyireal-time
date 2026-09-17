const DEFAULTS = {
  enabled: true,
  autoTranslate: true,
  sourceLang: "auto",
  targetLang: "zh-CN",
  displayMode: "translated",
  siteRules: {},
  skipTargetLanguage: true,
  chatMode: true,
  inputPreview: true,
  inputSourceLang: "auto",
  inputTargetLang: "en"
};

const LOCAL_DEFAULTS = {
  fallbackGoogle: true
};

const SITE_INPUT_KEY = "siteInputLanguagesV1";
const LANGUAGES = [
  ["auto", "自动检测"],
  ["zh-CN", "中文（简体）"], ["zh-TW", "中文（繁体）"], ["en", "英语"], ["ja", "日语"],
  ["ko", "韩语"], ["vi", "越南语"], ["th", "泰语"], ["ms", "马来语"], ["id", "印度尼西亚语"],
  ["fil", "菲律宾语"], ["fr", "法语"], ["de", "德语"], ["es", "西班牙语"], ["pt", "葡萄牙语"],
  ["ru", "俄语"], ["ar", "阿拉伯语"], ["hi", "印地语"], ["it", "意大利语"], ["tr", "土耳其语"]
];

const $ = id => document.getElementById(id);
let activeTab = null;
let currentHost = "";
let settings = { ...DEFAULTS };
let localSettings = { ...LOCAL_DEFAULTS };
let pagePaused = false;

function appendLanguageOptions(select, includeAuto) {
  for (const [value, label] of LANGUAGES) {
    if (!includeAuto && value === "auto") continue;
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.appendChild(option);
  }
}

function populateLanguages() {
  appendLanguageOptions($("sourceLang"), true);
  appendLanguageOptions($("targetLang"), false);
  appendLanguageOptions($("inputSourceLang"), true);
  appendLanguageOptions($("inputTargetLang"), false);
}

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return tabs[0] || null;
}

function hostFromTab(tab) {
  try {
    const url = new URL(tab?.url || "");
    return ["http:", "https:"].includes(url.protocol) ? url.hostname.toLowerCase() : "";
  } catch { return ""; }
}

async function sendToPage(message) {
  if (!activeTab?.id) return null;
  try { return await chrome.tabs.sendMessage(activeTab.id, message, { frameId: 0 }); }
  catch { return null; }
}

async function loadSiteInputProfile() {
  if (!currentHost) return null;
  const local = await chrome.storage.local.get({ [SITE_INPUT_KEY]: {} });
  const profile = local[SITE_INPUT_KEY]?.[currentHost];
  if (!profile || typeof profile !== "object") return null;
  return {
    sourceLang: String(profile.sourceLang || "auto"),
    targetLang: String(profile.targetLang || "en")
  };
}

async function saveSiteInputProfile(sourceLang, targetLang) {
  if (!currentHost) return;
  const local = await chrome.storage.local.get({ [SITE_INPUT_KEY]: {} });
  const map = local[SITE_INPUT_KEY] && typeof local[SITE_INPUT_KEY] === "object" ? local[SITE_INPUT_KEY] : {};
  map[currentHost] = { sourceLang, targetLang, updatedAt: Date.now() };
  await chrome.storage.local.set({ [SITE_INPUT_KEY]: map });
}

function routeLabel(route) {
  if (route === "azure") return "Azure";
  if (route === "google-web") return "Google Web";
  if (route === "google-fallback") return "Google 回退";
  if (route === "cache") return "缓存";
  return route || "暂无";
}

async function refreshRuntimeRoute() {
  try {
    const response = await chrome.runtime.sendMessage({ type: "FT_DIAGNOSTICS" });
    const runtime = response?.diagnostics?.lastRuntime;
    if (!runtime) {
      $("runtimeRoute").textContent = "最近翻译路径：暂无记录";
      return;
    }
    const when = Number(runtime.at || 0) ? new Date(runtime.at).toLocaleTimeString() : "-";
    const suffix = runtime.ok === false ? ` · 失败：${runtime.error || "未知错误"}` : ` · ${runtime.durationMs || 0}ms`;
    $("runtimeRoute").textContent = `最近翻译路径：${routeLabel(runtime.actualRoute)} · ${when}${suffix}`;
  } catch {
    $("runtimeRoute").textContent = "最近翻译路径：读取失败";
  }
}

function render() {
  $("enabled").checked = Boolean(settings.enabled);
  $("autoTranslate").checked = Boolean(settings.autoTranslate);
  $("fallbackGoogleQuick").checked = localSettings.fallbackGoogle !== false;
  $("skipTargetLanguage").checked = settings.skipTargetLanguage !== false;
  $("sourceLang").value = settings.sourceLang || "auto";
  $("targetLang").value = settings.targetLang || "zh-CN";
  $("displayMode").value = settings.displayMode || "translated";
  $("chatMode").checked = Boolean(settings.chatMode);
  $("inputPreview").checked = Boolean(settings.inputPreview);
  $("inputSourceLang").value = settings.inputSourceLang || "auto";
  $("inputTargetLang").value = settings.inputTargetLang || "en";
  $("siteRule").value = currentHost ? (settings.siteRules?.[currentHost] || "default") : "default";
  $("siteRule").disabled = !currentHost;
  $("host").textContent = currentHost || "此页面不支持扩展脚本";
  $("pauseResume").textContent = pagePaused ? "继续翻译" : "暂停翻译";
  $("swapInputLang").disabled = (settings.inputSourceLang || "auto") === "auto";
  $("swapInputLang").title = $("swapInputLang").disabled ? "先把输入语言改成具体语言后才能交换" : "交换输入与发送语言";
  $("pickExclusion").disabled = !currentHost;
  $("clearExclusions").disabled = !currentHost;
}

async function saveSync(patch) {
  settings = { ...settings, ...patch };
  await chrome.storage.sync.set(patch);
  render();
  await sendToPage({ type: "FT_REFRESH_SETTINGS" });
  await refreshPageState();
}

async function saveLocal(patch) {
  localSettings = { ...localSettings, ...patch };
  await chrome.storage.local.set(patch);
  render();
}

async function saveInputLanguages(patch) {
  await saveSync(patch);
  await saveSiteInputProfile(settings.inputSourceLang || "auto", settings.inputTargetLang || "en");
}

async function refreshPageState() {
  const response = await sendToPage({ type: "FT_GET_PAGE_STATE" });
  if (!response?.ok) {
    $("pageState").textContent = "此页面无法注入翻译脚本";
    return;
  }
  pagePaused = Boolean(response.paused);
  const pieces = [pagePaused ? "已暂停" : (response.active ? "持续翻译中" : "未翻译")];
  if (response.pageLang) pieces.push(response.pageLang);
  if (response.processing) pieces.push("处理中");
  if (response.queued) pieces.push(`待翻译 ${response.queued}`);
  if (response.processed) pieces.push(`已翻译 ${response.processed}`);
  if (response.retried) pieces.push(`精确重试 ${response.retried}`);
  if (response.protectedRestores) pieces.push(`防覆盖恢复 ${response.protectedRestores}`);
  if (response.failed) pieces.push(`失败文本 ${response.failed}`);
  if (settings.chatMode && settings.inputPreview) pieces.push("聊天输入预览开");
  $("pageState").textContent = pieces.join(" · ");
  $("pageState").title = response.lastError || "";
  render();
}

async function refreshExclusions() {
  if (!currentHost) {
    $("exclusionCount").textContent = "排除区域：此页面不支持";
    return;
  }
  const response = await sendToPage({ type: "FT_GET_EXCLUSIONS" });
  if (!response?.ok) {
    $("exclusionCount").textContent = "排除区域：读取失败";
    return;
  }
  const count = Array.isArray(response.selectors) ? response.selectors.length : 0;
  $("exclusionCount").textContent = `排除区域：${count} 条规则`;
  $("clearExclusions").disabled = count === 0;
}

async function init() {
  populateLanguages();
  [settings, localSettings, activeTab] = await Promise.all([
    chrome.storage.sync.get(DEFAULTS),
    chrome.storage.local.get(LOCAL_DEFAULTS),
    getActiveTab()
  ]);
  settings = { ...DEFAULTS, ...settings };
  localSettings = { ...LOCAL_DEFAULTS, ...localSettings };
  currentHost = hostFromTab(activeTab);

  const profile = await loadSiteInputProfile();
  if (profile) {
    settings.inputSourceLang = profile.sourceLang;
    settings.inputTargetLang = profile.targetLang;
    await chrome.storage.sync.set({ inputSourceLang: profile.sourceLang, inputTargetLang: profile.targetLang });
  }

  render();
  await Promise.all([refreshPageState(), refreshExclusions(), refreshRuntimeRoute()]);

  $("enabled").addEventListener("change", event => saveSync({ enabled: event.target.checked }));
  $("autoTranslate").addEventListener("change", event => saveSync({ autoTranslate: event.target.checked }));
  $("fallbackGoogleQuick").addEventListener("change", event => saveLocal({ fallbackGoogle: event.target.checked }));
  $("skipTargetLanguage").addEventListener("change", event => saveSync({ skipTargetLanguage: event.target.checked }));
  $("sourceLang").addEventListener("change", event => saveSync({ sourceLang: event.target.value }));
  $("targetLang").addEventListener("change", event => saveSync({ targetLang: event.target.value }));
  $("displayMode").addEventListener("change", event => saveSync({ displayMode: event.target.value }));
  $("chatMode").addEventListener("change", event => saveSync({ chatMode: event.target.checked }));
  $("inputPreview").addEventListener("change", event => saveSync({ inputPreview: event.target.checked }));
  $("inputSourceLang").addEventListener("change", event => saveInputLanguages({ inputSourceLang: event.target.value }));
  $("inputTargetLang").addEventListener("change", event => saveInputLanguages({ inputTargetLang: event.target.value }));

  $("swapInputLang").addEventListener("click", async () => {
    const source = settings.inputSourceLang || "auto";
    const target = settings.inputTargetLang || "en";
    if (source === "auto") return;
    await saveInputLanguages({ inputSourceLang: target, inputTargetLang: source });
  });

  $("siteRule").addEventListener("change", async event => {
    if (!currentHost) return;
    const siteRules = { ...(settings.siteRules || {}) };
    if (event.target.value === "default") delete siteRules[currentHost];
    else siteRules[currentHost] = event.target.value;
    await saveSync({ siteRules });
  });

  $("pickExclusion").addEventListener("click", async () => {
    const response = await sendToPage({ type: "FT_PICK_EXCLUSION" });
    $("exclusionCount").textContent = response?.ok ? "排除区域：已进入网页选择模式" : "排除区域：无法启动选择模式";
  });

  $("clearExclusions").addEventListener("click", async () => {
    const response = await sendToPage({ type: "FT_CLEAR_SITE_EXCLUSIONS" });
    if (response?.ok) await refreshExclusions();
  });

  $("pauseResume").addEventListener("click", async () => {
    const response = await sendToPage({ type: "FT_SET_PAUSED", paused: !pagePaused });
    if (response?.ok) pagePaused = Boolean(response.paused);
    render();
    setTimeout(refreshPageState, 120);
  });

  $("translateNow").addEventListener("click", async () => {
    $("pageState").textContent = "正在恢复并重新翻译整页…";
    await sendToPage({ type: "FT_TRANSLATE_NOW" });
    pagePaused = false;
    setTimeout(refreshPageState, 300);
  });

  $("rescanPage").addEventListener("click", async () => {
    $("pageState").textContent = pagePaused ? "当前已暂停，继续后再补扫" : "正在补扫遗漏内容…";
    if (!pagePaused) await sendToPage({ type: "FT_RESCAN_PAGE" });
    setTimeout(refreshPageState, 250);
  });

  $("restorePage").addEventListener("click", async () => {
    await sendToPage({ type: "FT_RESTORE_PAGE" });
    pagePaused = false;
    setTimeout(refreshPageState, 150);
  });

  $("openOptions").addEventListener("click", () => chrome.runtime.openOptionsPage());
  setInterval(() => { refreshPageState(); refreshRuntimeRoute(); }, 1400);
}

init().catch(error => {
  $("pageState").textContent = `加载失败：${error?.message || error}`;
});