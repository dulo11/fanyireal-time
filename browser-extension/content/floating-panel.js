(() => {
  if (window.top !== window || window.__FT_FLOATING_PANEL__) return;
  window.__FT_FLOATING_PANEL__ = true;

  const STORAGE_POS = "floatingPanelPositionV1";
  const STORAGE_VISIBLE = "floatingPanelVisibleV1";
  const SETTINGS_DEFAULTS = {
    enabled: true,
    autoTranslate: true,
    inputPreview: true,
    inputAutoReplace: false,
    sourceLang: "auto",
    targetLang: "zh-CN",
    displayMode: "translated"
  };
  const LANGUAGES = [
    ["auto", "自动检测"], ["zh-CN", "中文简体"], ["zh-TW", "中文繁体"], ["en", "英语"],
    ["ja", "日语"], ["ko", "韩语"], ["vi", "越南语"], ["th", "泰语"], ["ms", "马来语"],
    ["id", "印尼语"], ["fil", "菲律宾语"], ["fr", "法语"], ["de", "德语"], ["es", "西班牙语"],
    ["pt", "葡萄牙语"], ["ru", "俄语"], ["ar", "阿拉伯语"], ["hi", "印地语"]
  ];

  let settings = { ...SETTINGS_DEFAULTS };
  let pageState = { active: false, paused: false, queued: 0, processed: 0 };
  let opened = false;
  let drag = null;
  let stateRequest = 0;
  let suppressClickUntil = 0;
  let routeBusy = false;
  let floatingVisible = true;
  const STYLE_SIZE = "floatingFabSizeV1";
  const STYLE_OPACITY = "floatingFabOpacityV1";
  const STYLE_SNAP = "floatingFabSnapV1";
  let fabSize = 52;
  let fabOpacity = 1;
  let fabSnap = false;

  const host = document.createElement("div");
  host.dataset.ftOwned = "1";
  host.id = "ft-floating-panel-host";
  // Host 覆盖整个视口，但自身不吃点击；真正的按钮/面板单独 pointer-events:auto。
  // 这样 Android Chromium/Quetta 不会出现“面板画出来了，但超出 52x52 host 后点不到”的问题。
  host.style.cssText = "position:fixed;inset:0;z-index:2147483646;pointer-events:none;contain:style;";
  const root = host.attachShadow({ mode: "open" });

  root.innerHTML = `
    <style>
      :host{all:initial}
      *{box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",Arial,sans-serif}
      #fab{position:fixed;right:18px;bottom:92px;width:52px;height:52px;border:0;border-radius:18px;background:#6750e8;color:#fff;font-size:21px;font-weight:800;box-shadow:0 7px 24px rgba(0,0,0,.27);display:grid;place-items:center;touch-action:none;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent;pointer-events:auto;z-index:2147483647}
      #fab:active{transform:scale(.96)}
      #panel{position:fixed;width:min(318px,calc(100vw - 20px));max-height:min(520px,calc(100vh - 24px));overflow:auto;-webkit-overflow-scrolling:touch;background:rgba(252,252,255,.98);color:#202124;border:1px solid rgba(80,80,100,.16);border-radius:20px;box-shadow:0 14px 44px rgba(0,0,0,.3);padding:14px;display:none;z-index:2147483647;pointer-events:auto;touch-action:pan-y}
      #panel.open{display:block}
      .head{display:flex;align-items:center;gap:9px;margin-bottom:10px}.logo{width:34px;height:34px;border-radius:10px;background:#6750e8;color:white;display:grid;place-items:center;font-weight:800}.title{font-size:16px;font-weight:750;flex:1}.close{border:0;background:#ececf2;border-radius:10px;width:32px;height:32px;font-size:18px;color:#333}
      .status{font-size:12px;line-height:1.5;color:#62636a;background:#f2f2f7;border-radius:11px;padding:8px 10px;margin-bottom:10px;word-break:break-word}
      .toggle{display:flex;align-items:center;gap:9px;font-size:14px;margin:9px 1px}.toggle input{width:18px;height:18px}
      .row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:9px 0}.field{display:flex;flex-direction:column;gap:5px;font-size:11px;color:#666}.field select{width:100%;height:38px;border:1px solid #d7d7df;border-radius:11px;background:#fff;color:#202124;padding:0 8px;font-size:13px}
      .actions{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px}.actions button,.full{min-height:39px;border:1px solid #d5d3e5;border-radius:12px;background:#fff;color:#29282f;font-weight:650;font-size:13px;padding:7px;touch-action:manipulation}.actions button.primary{background:#6750e8;color:#fff;border-color:#6750e8}.actions button.warn{color:#8d2932}.full{width:100%;margin-top:8px;background:#f1efff;color:#4d37c8}
      .route{font-size:11px;color:#777;margin-top:9px;text-align:center}.muted{opacity:.55}
      @media (prefers-color-scheme:dark){#panel{background:rgba(31,31,36,.98);color:#f3f3f7;border-color:#494950}.status{background:#2b2b31;color:#c7c7cf}.field{color:#aaa}.field select,.actions button{background:#29292f;color:#f1f1f4;border-color:#4a4a54}.close{background:#383840;color:#eee}.full{background:#34304a;color:#cfc6ff}.route{color:#aaa}}
    </style>
    <button id="fab" type="button" aria-label="打开浮译小窗口" aria-expanded="false" title="浮译">译</button>
    <section id="panel" role="dialog" aria-label="浮译快捷控制">
      <div class="head"><div class="logo">浮</div><div class="title">浮译</div><button id="close" type="button" class="close">×</button></div>
      <div id="status" class="status">正在读取页面状态…</div>
      <label class="toggle"><input id="enabled" type="checkbox"><span>启用翻译</span></label>
      <label class="toggle"><input id="auto" type="checkbox"><span>自动持续翻译网页</span></label>
      <label class="toggle"><input id="inputPreview" type="checkbox"><span>输入时实时预览译文</span></label>
      <label class="toggle"><input id="inputAutoReplace" type="checkbox"><span>停止输入后自动替换（默认关闭）</span></label>
      <div class="row">
        <label class="field"><span>源语言</span><select id="source"></select></label>
        <label class="field"><span>翻译为</span><select id="target"></select></label>
      </div>
      <div class="actions">
        <button id="pause" type="button" class="primary">暂停</button>
        <button id="translate" type="button">翻译整页</button>
        <button id="rescan" type="button">补扫遗漏</button>
        <button id="restore" type="button" class="warn">恢复原文</button>
      </div>
      <button id="full" type="button" class="full">打开完整设置</button>
      <button id="hide" type="button" class="full">隐藏网页悬浮按钮（插件菜单可恢复）</button>
      <button id="route" type="button" class="full route" aria-label="打开翻译引擎设置">翻译引擎：读取中…</button>
    </section>`;

  const $ = id => root.getElementById(id);
  const fab = $("fab");
  const panel = $("panel");

  function applyFabAppearance() {
    fabSize = Math.max(32, Math.min(72, Number(fabSize) || 52));
    fabOpacity = Math.max(.2, Math.min(1, Number(fabOpacity) || 1));
    fab.style.width = fabSize + "px";
    fab.style.height = fabSize + "px";
    fab.style.opacity = String(fabOpacity);
    fab.style.borderRadius = Math.round(fabSize * .34) + "px";
    if (fab.style.left) {
      const box = fab.getBoundingClientRect();
      setFabPosition(box.left, box.top);
    }
    if (opened) positionPanel();
  }
  async function loadFabAppearance() {
    try {
      const found = await chrome.storage.local.get({
        [STYLE_SIZE]: 52, [STYLE_OPACITY]: 1, [STYLE_SNAP]: false
      });
      fabSize = found[STYLE_SIZE];
      fabOpacity = found[STYLE_OPACITY];
      fabSnap = found[STYLE_SNAP] === true;
    } catch {}
    applyFabAppearance();
  }

  function applyFloatingVisibility() {
    // 隐藏/恢复时释放上一次手势，避免隐藏前残留的状态影响再次点击。
    drag = null;
    suppressClickUntil = 0;
    fab.style.display = floatingVisible ? "grid" : "none";
    if (!floatingVisible) togglePanel(false);
  }
  async function loadFloatingVisibility() {
    try {
      const value = await chrome.storage.local.get({ [STORAGE_VISIBLE]: true });
      floatingVisible = value[STORAGE_VISIBLE] !== false;
    } catch {}
    applyFloatingVisibility();
  }

  function fillLanguages() {
    for (const [value, label] of LANGUAGES) {
      const source = document.createElement("option");
      source.value = value;
      source.textContent = label;
      $("source").appendChild(source);
      if (value !== "auto") {
        const target = document.createElement("option");
        target.value = value;
        target.textContent = label;
        $("target").appendChild(target);
      }
    }
  }

  function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }

  function setFabPosition(x, y) {
    fab.style.right = "auto";
    fab.style.bottom = "auto";
    fab.style.left = `${clamp(x, 6, Math.max(6, innerWidth - fabSize - 6))}px`;
    fab.style.top = `${clamp(y, 6, Math.max(6, innerHeight - fabSize - 6))}px`;
  }

  function positionPanel() {
    if (!opened) return;
    const b = fab.getBoundingClientRect();
    panel.style.visibility = "hidden";
    panel.style.display = "block";
    const p = panel.getBoundingClientRect();
    const left = clamp(b.right > innerWidth / 2 ? b.right - p.width : b.left, 8, Math.max(8, innerWidth - p.width - 8));
    let top = b.top - p.height - 10;
    if (top < 8) top = clamp(b.bottom + 10, 8, Math.max(8, innerHeight - p.height - 8));
    panel.style.left = `${left}px`;
    panel.style.top = `${clamp(top, 8, Math.max(8, innerHeight - p.height - 8))}px`;
    panel.style.visibility = "visible";
  }

  function render() {
    $("enabled").checked = settings.enabled !== false;
    $("auto").checked = settings.autoTranslate !== false;
    $("inputPreview").checked = settings.inputPreview !== false;
    $("inputAutoReplace").checked = settings.inputAutoReplace === true;
    $("source").value = settings.sourceLang || "auto";
    $("target").value = settings.targetLang || "zh-CN";
    $("pause").textContent = pageState.paused ? "继续" : "暂停";
    $("pause").classList.toggle("primary", !pageState.paused);
    const status = pageState.paused ? "已暂停" : (pageState.active ? "翻译运行中" : "当前未运行");
    const extras = [];
    if (pageState.processing) extras.push("处理中");
    if (pageState.queued) extras.push(`待翻 ${pageState.queued}`);
    if (pageState.processed) extras.push(`已翻 ${pageState.processed}`);
    if (pageState.lastError) extras.push(`错误：${pageState.lastError}`);
    $("status").textContent = [status, ...extras].join(" · ");
  }

  function routeLabel(route) {
    return ({ azure: "Azure", baidu: "百度", aliyun: "阿里云", "google-web": "Google Web", "google-fallback": "Google 回退", cache: "缓存" })[route] || route || "暂无";
  }

  async function refreshRoute() {
    if (routeBusy) return;
    routeBusy = true;
    try {
      const r = await chrome.runtime.sendMessage({ type: "FT_DIAGNOSTICS" });
      const runtime = r?.diagnostics?.lastRuntime;
      const pool = r?.diagnostics?.providerPoolLastRoute;
      const actual = pool?.provider || runtime?.actualRoute;
      const credential = pool?.credentialLabel ? ` · ${pool.credentialLabel}` : (pool?.credentialName ? ` · ${pool.credentialName}` : "");
      $("route").textContent = `翻译引擎：${routeLabel(actual)}${credential}`;
    } catch {
      $("route").textContent = "翻译引擎：后台未响应，点此设置";
    } finally { routeBusy = false; }
  }

  function requestState() {
    stateRequest++;
    window.dispatchEvent(new CustomEvent("ft-floating-command", { detail: { action: "get-state", requestId: stateRequest } }));
  }

  function command(action) {
    stateRequest++;
    window.dispatchEvent(new CustomEvent("ft-floating-command", { detail: { action, requestId: stateRequest } }));
    setTimeout(requestState, 120);
  }

  async function saveSync(patch) {
    settings = { ...settings, ...patch };
    try {
      await chrome.storage.sync.set(patch);
      render();
    } catch (error) {
      pageState.lastError = `保存设置失败：${error?.message || error}`;
      render();
    }
  }

  async function load() {
    try {
      settings = { ...SETTINGS_DEFAULTS, ...(await chrome.storage.sync.get(SETTINGS_DEFAULTS)) };
      render();
      requestState();
      refreshRoute();
    } catch (error) {
      pageState.lastError = `读取设置失败：${error?.message || error}`;
      render();
    }
  }

  function togglePanel(force) {
    opened = typeof force === "boolean" ? force : !opened;
    panel.classList.toggle("open", opened);
    panel.style.display = opened ? "block" : "none";
    fab.setAttribute("aria-expanded", opened ? "true" : "false");
    if (opened) {
      requestState();
      refreshRoute();
      requestAnimationFrame(positionPanel);
    }
  }

  async function openOptionsSafe() {
    $("status").textContent = "正在打开完整设置…";
    try {
      const response = await chrome.runtime.sendMessage({ type: "FT_OPEN_OPTIONS" });
      if (response?.ok) {
        togglePanel(false);
        return;
      }
      throw new Error(response?.error || "后台未能打开设置页");
    } catch (firstError) {
      try {
        if (typeof chrome.runtime.openOptionsPage === "function") {
          const result = chrome.runtime.openOptionsPage();
          if (result && typeof result.then === "function") await result;
          togglePanel(false);
          return;
        }
      } catch {}
      pageState.lastError = `无法打开设置：${firstError?.message || firstError}`;
      render();
    }
  }

  window.addEventListener("ft-floating-state", event => {
    if (!event.detail) return;
    pageState = { ...pageState, ...event.detail };
    render();
  });

  // 拖动只负责移动；普通点击统一交给 click 事件。
  // 这样即使某些 Android Chromium 丢失 pointerup，也不会导致“点一下完全没反应”。
  fab.addEventListener("pointerdown", event => {
    const box = fab.getBoundingClientRect();
    if (drag?.source === "touch") return;
    drag = { source: "pointer", id: event.pointerId, x: event.clientX, y: event.clientY, left: box.left, top: box.top, moved: false };
    try { fab.setPointerCapture?.(event.pointerId); } catch {}
  });

  fab.addEventListener("pointermove", event => {
    if (!drag || drag.source !== "pointer" || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (Math.hypot(dx, dy) > 6) drag.moved = true;
    if (!drag.moved) return;
    event.preventDefault();
    setFabPosition(drag.left + dx, drag.top + dy);
    if (opened) positionPanel();
  });

  async function finishDrag(event, source = "pointer") {
    if (!drag || drag.source !== source || (event?.pointerId !== undefined && drag.id !== event.pointerId)) return;
    const moved = drag.moved;
    drag = null;
    if (!moved) return;
    suppressClickUntil = Date.now() + 450;
    const r = fab.getBoundingClientRect();
    if (fabSnap) setFabPosition(r.left < innerWidth / 2 ? 6 : innerWidth - fabSize - 6, r.top);
    const finalRect = fab.getBoundingClientRect();
    try { await chrome.storage.local.set({ [STORAGE_POS]: { x: finalRect.left, y: finalRect.top } }); } catch {}
  }

  fab.addEventListener("pointerup", finishDrag);
  fab.addEventListener("pointercancel", finishDrag);
  fab.addEventListener("lostpointercapture", event => { if (drag?.moved) finishDrag(event); });

  // 手机浏览器可能只分发 touchmove 而不可靠分发 pointermove。
  // TouchEvent 作为真实手指拖动的兜底，不依赖浏览器的 PointerEvent 实现。
  fab.addEventListener("touchstart", event => {
    if (event.touches.length !== 1) return;
    const t = event.changedTouches?.[0] || event.touches?.[0];
    if (!t) return;
    const box = fab.getBoundingClientRect();
    drag = { source: "touch", id: t.identifier, x: t.clientX, y: t.clientY, left: box.left, top: box.top, moved: false };
  }, { passive: true });

  fab.addEventListener("touchmove", event => {
    if (drag?.source !== "touch") return;
    const t = Array.from(event.changedTouches).find(t => t.identifier === drag.id);
    if (!t) return;
    const dx = t.clientX - drag.x;
    const dy = t.clientY - drag.y;
    if (Math.hypot(dx, dy) > 6) drag.moved = true;
    if (!drag.moved) return;
    if (event.cancelable) event.preventDefault();
    setFabPosition(drag.left + dx, drag.top + dy);
    if (opened) positionPanel();
  }, { passive: false });

  fab.addEventListener("touchend", event => {
    if (drag?.source !== "touch") return;
    const t = Array.from(event.changedTouches || []).find(t => t.identifier === drag.id);
    if (!t) return;
    const moved = drag.moved;
    void finishDrag(null, "touch");
    if (!moved && floatingVisible) {
      // 部分安卓浏览器在隐藏→恢复后不再派发兼容 click。
      // 用手指抬起直接打开面板，同时拦截可能随后到来的合成 click。
      suppressClickUntil = Date.now() + 650;
      togglePanel();
    }
  }, { passive: true });

  fab.addEventListener("touchcancel", () => {
    if (drag?.source === "touch") void finishDrag(null, "touch");
  }, { passive: true });
  fab.addEventListener("contextmenu", event => event.preventDefault());

  fab.addEventListener("click", event => {
    event.preventDefault();
    event.stopPropagation();
    if (!floatingVisible || Date.now() < suppressClickUntil) return;
    togglePanel();
  });

  $("close").addEventListener("click", () => togglePanel(false));
  $("enabled").addEventListener("change", event => saveSync({ enabled: event.target.checked }));
  $("auto").addEventListener("change", event => saveSync({ autoTranslate: event.target.checked }));
  $("inputPreview").addEventListener("change", event => saveSync({ inputPreview: event.target.checked }));
  $("inputAutoReplace").addEventListener("change", event => saveSync({ inputAutoReplace: event.target.checked }));
  $("source").addEventListener("change", event => saveSync({ sourceLang: event.target.value }));
  $("target").addEventListener("change", event => saveSync({ targetLang: event.target.value }));
  $("pause").addEventListener("click", () => command("pause-toggle"));
  $("translate").addEventListener("click", () => command("translate-now"));
  $("rescan").addEventListener("click", () => command("rescan"));
  $("restore").addEventListener("click", () => command("restore"));
  $("full").addEventListener("click", openOptionsSafe);
  $("route").addEventListener("click", openOptionsSafe);
  $("hide").addEventListener("click", async () => {
    try {
      await chrome.storage.local.set({ [STORAGE_VISIBLE]: false });
      floatingVisible = false;
      applyFloatingVisibility();
    } catch (error) {
      pageState.lastError = String(error?.message || error);
      render();
    }
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && (changes[STYLE_SIZE] || changes[STYLE_OPACITY] || changes[STYLE_SNAP])) {
      if (changes[STYLE_SIZE]) fabSize = changes[STYLE_SIZE].newValue;
      if (changes[STYLE_OPACITY]) fabOpacity = changes[STYLE_OPACITY].newValue;
      if (changes[STYLE_SNAP]) fabSnap = changes[STYLE_SNAP].newValue === true;
      applyFabAppearance();
    }
    if (area === "local" && changes[STORAGE_VISIBLE]) {
      floatingVisible = changes[STORAGE_VISIBLE].newValue !== false;
      applyFloatingVisibility();
      return;
    }
    if (area !== "sync") return;
    let changed = false;
    for (const key of Object.keys(SETTINGS_DEFAULTS)) {
      if (changes[key]) {
        settings[key] = changes[key].newValue;
        changed = true;
      }
    }
    if (changed) render();
  });

  window.addEventListener("resize", () => {
    const box = fab.getBoundingClientRect();
    if (fab.style.left) setFabPosition(box.left, box.top);
    if (opened) positionPanel();
  }, { passive: true });

  async function restorePosition() {
    try {
      const stored = await chrome.storage.local.get({ [STORAGE_POS]: null });
      const pos = stored[STORAGE_POS];
      if (pos && Number.isFinite(pos.x) && Number.isFinite(pos.y)) setFabPosition(pos.x, pos.y);
    } catch {}
  }

  fillLanguages();
  document.documentElement.appendChild(host);
  restorePosition();
  loadFabAppearance();
  loadFloatingVisibility();
  load();
  setInterval(() => {
    if (opened) {
      requestState();
      refreshRoute();
    }
  }, 1400);
})();
