const DEFAULTS = {
  enabled: true,
  autoTranslate: true,
  performanceSafeMode: true,
  visiblePriority: true,
  bilingualFontScale: 100,
  bilingualColor: "#6750e8",
  siteDisplayModes: {},
  sourceLang: "auto",
  targetLang: "zh-CN",
  displayMode: "translated",
  siteRules: {},
  skipTargetLanguage: true,
  chatMode: true,
  inputPreview: true,
  inputQuickTranslate: false,
  selectionToolsEnabled: true,
  selectionTargetLang: "page",
  inputAutoReplace: false,
  inputAutoReplaceDelay: 1500,
  siteInputAutoReplace: {},
  inputSourceLang: "auto",
  inputTargetLang: "en"
};

const LOCAL_DEFAULTS = {
  floatingPanelVisibleV1: true,
  floatingFabSizeV1: 52,
  floatingFabOpacityV1: 1,
  floatingFabSnapV1: false,
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
let repairing = null;
let repairAttempted = false;
let pageError = "";
let refreshBusy = false;

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

async function repairPage() {
  if (repairing) return repairing;
  if (repairAttempted) throw new Error("网页脚本恢复失败，请刷新网页后重试");
  repairAttempted = true;
  repairing = (async () => {
    if (!currentHost) throw new Error("浏览器内部页不允许注入，请打开普通网页");
    if (!chrome.scripting?.executeScript) throw new Error("当前浏览器不支持脚本恢复，请刷新网页并检查扩展的网站访问权限");
    const block = chrome.runtime.getManifest().content_scripts[0];
    const target = { tabId: activeTab.id, frameIds: [0] };
    await chrome.scripting.insertCSS({ target, files: block.css });
    await chrome.scripting.executeScript({ target, files: block.js });
  })();
  try { await repairing; } finally { repairing = null; }
}

async function sendToPage(message) {
  if (!activeTab?.id) return null;
  try {
    const response = await chrome.tabs.sendMessage(activeTab.id, message, { frameId: 0 });
    if (!response) throw new Error("网页脚本没有响应");
    pageError = "";
    return response;
  } catch (error) {
    try {
      await repairPage();
      const response = await chrome.tabs.sendMessage(activeTab.id, message, { frameId: 0 });
      if (!response) throw new Error("网页脚本没有响应，请刷新网页");
      pageError = "";
      return response;
    } catch (repairError) {
      pageError = String(repairError?.message || error?.message || error);
      return null;
    }
  }
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
  $("floatingPanelVisible").checked = localSettings.floatingPanelVisibleV1 !== false;
  $("floatingFabSize").value = String(localSettings.floatingFabSizeV1 ?? 52);
  $("floatingFabOpacity").value = String(localSettings.floatingFabOpacityV1 ?? 1);
  $("floatingFabSnap").checked = localSettings.floatingFabSnapV1 === true;
  $("autoTranslate").checked = Boolean(settings.autoTranslate);
  $("visiblePriority").checked = settings.visiblePriority !== false;
  $("bilingualFontScale").value = String(settings.bilingualFontScale || 100);
  $("bilingualColor").value = /^#[0-9a-f]{6}$/i.test(settings.bilingualColor || "") ? settings.bilingualColor : "#6750e8";
  $("performanceSafeMode").checked = settings.performanceSafeMode !== false;
  $("fallbackGoogleQuick").checked = localSettings.fallbackGoogle !== false;
  $("skipTargetLanguage").checked = settings.skipTargetLanguage !== false;
  $("sourceLang").value = settings.sourceLang || "auto";
  $("targetLang").value = settings.targetLang || "zh-CN";
  $("displayMode").value = settings.displayMode || "translated";
  $("selectionToolsEnabled").checked = settings.selectionToolsEnabled !== false;
  $("selectionTargetLang").value = settings.selectionTargetLang || "page";
  $("chatMode").checked = Boolean(settings.chatMode);
  $("inputPreview").checked = Boolean(settings.inputPreview);
  $("inputQuickTranslate").checked = Boolean(settings.inputQuickTranslate);
  $("inputAutoReplace").checked = settings.inputAutoReplace === true;
  $("inputAutoReplaceDelay").value = String(settings.inputAutoReplaceDelay || 1500);
  $("siteInputAutoReplace").value = currentHost
    ? (settings.siteInputAutoReplace?.[currentHost] || "default") : "default";
  $("siteInputAutoReplace").disabled = !currentHost;
  $("inputSourceLang").value = settings.inputSourceLang || "auto";
  $("inputTargetLang").value = settings.inputTargetLang || "en";
  $("siteRule").value = currentHost ? (settings.siteRules?.[currentHost] || "default") : "default";
  $("siteRule").disabled = !currentHost;
  $("siteDisplayMode").value = currentHost ? (settings.siteDisplayModes?.[currentHost] || "default") : "default";
  $("siteDisplayMode").disabled = !currentHost;
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
    $("pageState").textContent = `网页连接失败：${pageError || "请刷新网页，并允许扩展访问此网站"}`;
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
  const diagnostic = [
    `网站：${response.host || currentHost || "-"}`,
    `翻译：${response.active ? (response.paused ? "已暂停" : "运行中") : "未运行"}`,
    `排队：${response.queued ?? 0}；视区外待处理：${response.deferred ?? 0}`,
    `已翻译：${response.processed ?? 0}；失败：${response.failed ?? 0}`,
    `最近请求：${Number.isFinite(response.lastRequestMs) && response.lastRequestAt ? response.lastRequestMs + "ms" : "暂无"}`,
    `翻译任务：${response.processing ? "处理中" : "空闲"}`,
    `视区优先：${response.visiblePriority ? "开" : "关"}`,
    `异常：${response.safetyPauseReason || response.lastError || "无"}`
  ];
  $("pageDiagnostics").textContent = diagnostic.join("\n");
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

function bindControls() {
  $("enabled").addEventListener("change", event => saveSync({ enabled: event.target.checked }));
  $("autoTranslate").addEventListener("change", event => saveSync({ autoTranslate: event.target.checked }));
  $("performanceSafeMode").addEventListener("change", event => saveSync({ performanceSafeMode: event.target.checked }));
  $("visiblePriority").addEventListener("change", event => saveSync({ visiblePriority: event.target.checked }));
  $("bilingualFontScale").addEventListener("change", event => saveSync({ bilingualFontScale: Number(event.target.value) }));
  $("bilingualColor").addEventListener("change", event => saveSync({ bilingualColor: event.target.value }));
  $("floatingPanelVisible").addEventListener("change", event => saveLocal({ floatingPanelVisibleV1: event.target.checked }));
  $("floatingFabSize").addEventListener("change", event => saveLocal({ floatingFabSizeV1: Number(event.target.value) }));
  $("floatingFabOpacity").addEventListener("change", event => saveLocal({ floatingFabOpacityV1: Number(event.target.value) }));
  $("floatingFabSnap").addEventListener("change", event => saveLocal({ floatingFabSnapV1: event.target.checked }));
  $("fallbackGoogleQuick").addEventListener("change", event => saveLocal({ fallbackGoogle: event.target.checked }));
  $("skipTargetLanguage").addEventListener("change", event => saveSync({ skipTargetLanguage: event.target.checked }));
  $("sourceLang").addEventListener("change", event => saveSync({ sourceLang: event.target.value }));
  $("targetLang").addEventListener("change", event => saveSync({ targetLang: event.target.value }));
  $("displayMode").addEventListener("change", event => saveSync({ displayMode: event.target.value }));
  $("selectionToolsEnabled").addEventListener("change", event => saveSync({ selectionToolsEnabled: event.target.checked }));
  $("selectionTargetLang").addEventListener("change", event => saveSync({ selectionTargetLang: event.target.value }));
  $("chatMode").addEventListener("change", event => saveSync({ chatMode: event.target.checked }));
  $("inputPreview").addEventListener("change", event => saveSync({ inputPreview: event.target.checked }));
  $("inputQuickTranslate").addEventListener("change", event => saveSync({ inputQuickTranslate: event.target.checked }));
  $("inputAutoReplace").addEventListener("change", event => saveSync({ inputAutoReplace: event.target.checked }));
  $("inputAutoReplaceDelay").addEventListener("change", event => saveSync({ inputAutoReplaceDelay: Number(event.target.value) }));
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

  $("siteInputAutoReplace").addEventListener("change", async event => {
    if (!currentHost) return;
    const siteInputAutoReplace = { ...(settings.siteInputAutoReplace || {}) };
    if (event.target.value === "default") delete siteInputAutoReplace[currentHost];
    else siteInputAutoReplace[currentHost] = event.target.value;
    await saveSync({ siteInputAutoReplace });
  });

  $("siteDisplayMode").addEventListener("change", async event => {
    if (!currentHost) return;
    const siteDisplayModes = { ...(settings.siteDisplayModes || {}) };
    if (event.target.value === "default") delete siteDisplayModes[currentHost];
    else siteDisplayModes[currentHost] = event.target.value;
    await saveSync({ siteDisplayModes });
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

  $("clearQueue").addEventListener("click", async () => {
    const response = await sendToPage({ type: "FT_CLEAR_QUEUE" });
    $("diagnosticsFeedback").textContent = response?.ok ? "翻译队列已清空，后续滚动和自动翻译仍可继续。" : "无法清空队列，请暂停或关闭当前网页翻译。";
    setTimeout(refreshPageState, 150);
  });

  $("refreshDiagnostics").addEventListener("click", refreshPageState);
  $("copyDiagnostics").addEventListener("click", async () => {
    const page = await sendToPage({ type: "FT_GET_PAGE_STATE" });
    const safe = {
      version: chrome.runtime.getManifest().version,
      host: currentHost,
      browser: navigator.userAgent,
      active: page?.active,
      paused: page?.paused,
      queued: page?.queued,
      deferred: page?.deferred,
      processing: page?.processing,
      processed: page?.processed,
      failed: page?.failed,
      retried: page?.retried,
      lastRequestMs: page?.lastRequestMs,
      lastError: page?.lastError,
      safetyPauseReason: page?.safetyPauseReason,
      autoTranslate: settings.autoTranslate,
      performanceSafeMode: settings.performanceSafeMode,
      visiblePriority: settings.visiblePriority
    };
    const data = JSON.stringify(safe, null, 2);
    try {
      await navigator.clipboard.writeText(data);
      $("diagnosticsFeedback").textContent = "诊断已复制（不含翻译密钥）";
    } catch {
      $("pageDiagnostics").textContent = data;
      $("diagnosticsFeedback").textContent = "无法访问剪贴板；上方已显示诊断文本，可长按复制。";
    }
  });

  $("restorePage").addEventListener("click", async () => {
    await sendToPage({ type: "FT_RESTORE_PAGE" });
    pagePaused = false;
    setTimeout(refreshPageState, 150);
  });

  $("openOptions").addEventListener("click", () => {
    chrome.runtime.openOptionsPage().catch(error => {
      $("pageState").textContent = `打开设置失败：${error.message}`;
    });
  });
}

async function init() {
  bindControls();
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
  void Promise.allSettled([refreshPageState(), refreshExclusions(), refreshRuntimeRoute()]);

  setInterval(async () => {
    if (refreshBusy) return;
    refreshBusy = true;
    try { await Promise.allSettled([refreshPageState(), refreshRuntimeRoute()]); }
    finally { refreshBusy = false; }
  }, 5000);
}

init().catch(error => {
  $("pageState").textContent = `加载失败：${error?.message || error}`;
});