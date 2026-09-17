(() => {
  if (window.top !== window || window.__FT_FLOATING_PANEL__) return;
  window.__FT_FLOATING_PANEL__ = true;

  const STORAGE_POS = "floatingPanelPositionV1";
  const SETTINGS_DEFAULTS = {
    enabled: true,
    autoTranslate: true,
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
      <button id="route" type="button" class="full route" aria-label="打开翻译引擎设置">翻译引擎：读取中…</button>
    </section>`;

  const $ = id => root.getElementById(id);
  const fab = $("fab");
  const panel = $("panel");

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
    fab.style.left = `${clamp(x, 6, Math.max(6, innerWidth - 58))}px`;
    fab.style.top = `${clamp(y, 6, Math.max(6, innerHeight - 58))}px`;
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
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, left: box.left, top: box.top, moved: false };
    try { fab.setPointerCapture?.(event.pointerId); } catch {}
  });

  fab.addEventListener("pointermove", event => {
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (Math.hypot(dx, dy) > 6) drag.moved = true;
    if (!drag.moved) return;
    event.preventDefault();
    setFabPosition(drag.left + dx, drag.top + dy);
    if (opened) positionPanel();
  });

  async function finishDrag(event) {
    if (!drag || (event?.pointerId !== undefined && drag.id !== event.pointerId)) return;
    const moved = drag.moved;
    drag = null;
    if (!moved) return;
    suppressClickUntil = Date.now() + 450;
    const r = fab.getBoundingClientRect();
    try { await chrome.storage.local.set({ [STORAGE_POS]: { x: r.left, y: r.top } }); } catch {}
  }

  fab.addEventListener("pointerup", finishDrag);
  fab.addEventListener("pointercancel", finishDrag);
  fab.addEventListener("lostpointercapture", event => { if (drag?.moved) finishDrag(event); });

  fab.addEventListener("click", event => {
    event.preventDefault();
    event.stopPropagation();
    if (Date.now() < suppressClickUntil) return;
    togglePanel();
  });

  // 旧 Android WebView/浏览器若没有 PointerEvent，至少保证轻触可打开。
  if (!("PointerEvent" in window)) {
    let touchStart = null;
    fab.addEventListener("touchstart", event => {
      const t = event.touches?.[0];
      if (t) touchStart = { x: t.clientX, y: t.clientY };
    }, { passive: true });
    fab.addEventListener("touchend", event => {
      const t = event.changedTouches?.[0];
      if (!t || !touchStart) return;
      const moved = Math.hypot(t.clientX - touchStart.x, t.clientY - touchStart.y) > 8;
      touchStart = null;
      if (!moved) {
        togglePanel();
        suppressClickUntil = Date.now() + 450;
      }
    }, { passive: true });
  }

  $("close").addEventListener("click", () => togglePanel(false));
  $("enabled").addEventListener("change", event => saveSync({ enabled: event.target.checked }));
  $("auto").addEventListener("change", event => saveSync({ autoTranslate: event.target.checked }));
  $("source").addEventListener("change", event => saveSync({ sourceLang: event.target.value }));
  $("target").addEventListener("change", event => saveSync({ targetLang: event.target.value }));
  $("pause").addEventListener("click", () => command("pause-toggle"));
  $("translate").addEventListener("click", () => command("translate-now"));
  $("rescan").addEventListener("click", () => command("rescan"));
  $("restore").addEventListener("click", () => command("restore"));
  $("full").addEventListener("click", openOptionsSafe);
  $("route").addEventListener("click", openOptionsSafe);

  chrome.storage.onChanged.addListener((changes, area) => {
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
  load();
  setInterval(() => {
    if (opened) {
      requestState();
      refreshRoute();
    }
  }, 1400);
})();
