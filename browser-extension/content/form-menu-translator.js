(() => {
  "use strict";
  if (window.top !== window || window.__FT_FORM_MENU_V1316__) return;
  window.__FT_FORM_MENU_V1316__ = true;

  // 独立表单辅助层：绝不改写原 select/option 的 text、value 或输入框内容。
  const defaults = {
    enabled: true, formMenuEnabled: true, formMenuMode: "bilingual",
    sourceLang: "auto", targetLang: "zh-CN"
  };
  let settings = { ...defaults };
  let requestVersion = 0;
  let activeMenu = null;
  let timer = null;
  let quietUntil = 0;
  const cache = new Map();
  const launchers = new WeakMap();
  const MENU_CHOICES = [
    '[role="option"]', '[role="menuitem"]', '[role="menuitemradio"]',
    '[role="menuitemcheckbox"]', '.dropdown-item', '.ant-select-item-option',
    '.el-select-dropdown__item', '.v-list-item[role="option"]'
  ].join(",");
  const MENU_CONTAINERS = [
    '[role="listbox"]', '[role="menu"]', '[role="dialog"]',
    '.dropdown-menu.show', '.dropdown-menu[style*="display: block"]',
    '.ant-select-dropdown:not([style*="display: none"])',
    '.el-select-dropdown:not([style*="display: none"])'
  ].join(",");

  const host = document.createElement("div");
  host.id = "ft-form-menu-host";
  host.dataset.ftOwned = "1";
  host.style.cssText = "position:fixed;inset:0;z-index:2147483646;pointer-events:none";
  const shadow = host.attachShadow({ mode: "open" });
  shadow.innerHTML = `
  <style>
    :host{all:initial}
    *,*::before,*::after{box-sizing:border-box;font-family:system-ui,-apple-system,Roboto,"Noto Sans SC",sans-serif}
    #panel{display:none;position:fixed;z-index:2147483647;width:min(380px,calc(100vw - 16px));
      max-height:min(480px,70vh);overflow:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;
      padding:12px;background:#fff;color:#23232b;border:1px solid #dedee6;border-radius:16px;
      box-shadow:0 14px 40px #0005;pointer-events:auto}
    #panel.open{display:block}
    .head{display:flex;gap:6px;align-items:center;position:sticky;top:-12px;background:inherit;padding:5px 0 9px;z-index:1}
    .head strong{font-size:14px;flex:1;line-height:1.3}
    button,select{min-height:35px;touch-action:manipulation}
    .head select{max-width:120px;border-radius:8px;border:1px solid #ccd;background:inherit;color:inherit}
    .close{border:0;border-radius:9px;background:#eee;color:#444;width:34px;font-size:20px}
    .hint{font-size:11px;color:#666;margin:4px 0 8px}
    .row{width:100%;display:flex;flex-direction:column;gap:3px;text-align:left;word-break:break-word;
      padding:9px 10px;margin:4px 0;border:1px solid #ddd;border-radius:10px;background:#fafaff;
      color:#27272c;font-size:14px;line-height:1.4}
    .row[disabled]{opacity:.65}
    .translated{color:#4e37b9;font-weight:650}
    .original{color:#61616c;font-size:12px}
    .tail{margin-top:9px;display:flex;gap:7px}
    .tail button{flex:1;border:1px solid #d7d1ef;border-radius:9px;background:#f2efff;color:#4f36ae}
    @media(prefers-color-scheme:dark){
      #panel{background:#282832;color:#f8f8fa;border-color:#575761}
      .row{background:#343441;border-color:#555;color:#fff}
      .translated{color:#b6a9ff}.original,.hint{color:#c1c1ca}
      .close{background:#444;color:#fff}.tail button{background:#453a60;color:#e7dcff}
    }
  </style>
  <aside id="panel" role="dialog" aria-label="浮译表单与菜单翻译">
    <div class="head"><strong id="title">浮译 · 选项翻译</strong>
      <select id="mode" aria-label="选项显示方式">
        <option value="translated">仅中文</option>
        <option value="bilingual">英文＋中文</option>
      </select><button type="button" id="close" class="close" aria-label="关闭">×</button>
    </div>
    <div id="status" class="hint" aria-live="polite"></div>
    <div id="rows"></div>
    <div class="tail"><button id="original" type="button" style="display:none">使用网站原始下拉框</button></div>
  </aside>`;

  const $ = id => shadow.getElementById(id);
  const panel = $("panel");
  const rows = $("rows");

  function isOwned(element) {
    return Boolean(element?.closest?.('[data-ft-owned="1"],#ft-form-menu-host,.ft-input-preview,.ft-selection-bubble'));
  }

  function allowed() {
    return settings.enabled !== false && settings.formMenuEnabled !== false;
  }

  function isVisible(el) {
    if (!el?.isConnected || isOwned(el)) return false;
    const rect = el.getBoundingClientRect();
    if (!rect.width || !rect.height || rect.bottom < -40 || rect.top > innerHeight + 40) return false;
    const styles = getComputedStyle(el);
    return styles.visibility !== "hidden" && styles.display !== "none";
  }

  function mode() {
    return settings.formMenuMode === "translated" ? "translated" : "bilingual";
  }

  function closeMenu() {
    requestVersion++;
    activeMenu = null;
    panel.classList.remove("open");
    rows.replaceChildren();
    $("original").style.display = "none";
  }

  function place(rect) {
    const width = Math.min(380, innerWidth - 16);
    const top = rect?.bottom || innerHeight / 3;
    const availableBelow = innerHeight - top;
    const height = Math.min(480, Math.round(innerHeight * .7));
    panel.style.left = Math.max(8, Math.min(innerWidth - width - 8, rect?.left || 8)) + "px";
    panel.style.top = (availableBelow > Math.min(height, 260)
      ? Math.max(8, top + 5)
      : Math.max(8, (rect?.top || innerHeight / 2) - height - 6)) + "px";
  }

  function cleanText(value) {
    const s = String(value || "").replace(/\s+/g, " ").trim();
    if (s.length > 150 || !/[\p{L}\p{M}]/u.test(s)) return "";
    return s;
  }

  function normalizeOptions(items, kind) {
    const out = [];
    for (const item of items.slice(0, 42)) {
      const text = cleanText(kind === "native" ? item.text : (item.innerText || item.textContent));
      if (!text) continue;
      if (kind === "custom" && (!isVisible(item) || item.closest('[contenteditable="true"],[contenteditable=""]'))) continue;
      out.push({ text, original: item, disabled: Boolean(item.disabled || item.getAttribute?.("aria-disabled") === "true") });
      if (out.length >= 32) break;
    }
    return out;
  }

  async function translateTexts(texts, targetLang) {
    const output = new Array(texts.length);
    const missing = [], positions = [];
    for (let i = 0; i < texts.length; i++) {
      const key = targetLang + "\u0000" + texts[i];
      if (cache.has(key)) output[i] = cache.get(key);
      else { missing.push(texts[i]); positions.push(i); }
    }
    if (missing.length) {
      const response = await Promise.race([
        chrome.runtime.sendMessage({
          type: "FT_TRANSLATE_DETAILED",
          texts: missing,
          options: { sourceLang: settings.sourceLang || "auto", targetLang }
        }),
        new Promise((_, reject) => setTimeout(() => reject(new Error("翻译超时，请检查引擎")), 18000))
      ]);
      if (!response?.ok) throw new Error(response?.error || "翻译服务暂不可用");
      for (let i = 0; i < missing.length; i++) {
        const translated = response.translations?.[i];
        // 单条失败时保留原文，不再把错误字符串伪装成译文。
        output[positions[i]] = typeof translated === "string" && translated.trim() ? translated.trim() : missing[i];
        if (cache.size > 350) cache.delete(cache.keys().next().value);
        cache.set(targetLang + "\u0000" + missing[i], output[positions[i]]);
      }
    }
    return output;
  }

  function selectNative(select, option) {
    if (!select?.isConnected || !option || select.disabled) return;
    const index = Array.from(select.options).indexOf(option);
    if (index < 0 || option.disabled) return;
    select.selectedIndex = index;
    select.dispatchEvent(new Event("input", { bubbles: true }));
    select.dispatchEvent(new Event("change", { bubbles: true }));
    select.focus();
  }

  function renderRows(entry, translated) {
    rows.replaceChildren();
    for (let i = 0; i < entry.choices.length; i++) {
      const item = entry.choices[i], translation = translated[i] || item.text;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "row";
      button.disabled = item.disabled;
      const tr = document.createElement("span");
      tr.className = "translated";
      tr.textContent = translation;
      button.append(tr);
      if (mode() === "bilingual") {
        const original = document.createElement("span");
        original.className = "original";
        original.textContent = item.text;
        button.prepend(original);
      }
      button.setAttribute("aria-label", item.text + "，" + translation);
      button.addEventListener("click", () => {
        quietUntil = Date.now() + 650;
        if (entry.kind === "native") selectNative(entry.anchor, item.original);
        else if (item.original?.isConnected && isVisible(item.original)) item.original.click();
        closeMenu();
      });
      rows.append(button);
    }
  }

  async function openMenu(entry) {
    if (!allowed() || !entry.choices.length) return;
    const version = ++requestVersion;
    activeMenu = entry;
    $("title").textContent = entry.kind === "native" ? "浮译 · 表单选项" : "浮译 · 弹出菜单";
    $("mode").value = mode();
    $("original").style.display = entry.kind === "native" ? "block" : "none";
    $("status").textContent = "正在翻译 " + entry.choices.length + " 个选项…";
    rows.replaceChildren();
    panel.classList.add("open");
    place(entry.anchor.getBoundingClientRect());
    const texts = entry.choices.map(x => x.text);
    try {
      const translated = await translateTexts(texts, settings.targetLang || "zh-CN");
      if (version !== requestVersion || !allowed()) return;
      entry.translated = translated;
      renderRows(entry, translated);
      $("status").textContent = entry.kind === "native"
        ? "点击译文即可选择；网站收到原始选项值，不改变表单字段。"
        : "点击译文可选择对应网页选项；其他弹窗按钮请在原页面操作。";
    } catch (error) {
      if (version !== requestVersion) return;
      entry.translated = texts;
      renderRows(entry, texts);
      $("status").textContent = "翻译暂不可用，已显示原文：" + String(error?.message || error).slice(0, 90);
    }
  }

  function nativeChoices(select) {
    if (!select || select.disabled || select.multiple || select.size > 1) return null;
    return normalizeOptions(Array.from(select.options), "native");
  }

  function attachNativeLauncher(select) {
    if (!allowed() || !isVisible(select) || select.disabled || select.multiple || select.size > 1) return;
    if (launchers.has(select) && launchers.get(select)?.isConnected) return;
    const launcher = document.createElement("button");
    launcher.type = "button";
    launcher.dataset.ftOwned = "1";
    launcher.className = "ft-native-options-launcher";
    launcher.textContent = "中译选项";
    launcher.title = "打开浮译双语选择列表（不改变原始提交值）";
    launcher.style.cssText = "display:inline-flex;align-items:center;min-height:28px;padding:3px 8px;margin:5px 0 6px 5px;vertical-align:middle;border:1px solid #ab9ff0;border-radius:10px;background:#f4f1ff;color:#5740a7;font-size:12px;line-height:1.4;touch-action:manipulation";
    launcher.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      const choices = nativeChoices(select);
      if (choices?.length) void openMenu({ kind: "native", anchor: select, choices });
    });
    select.insertAdjacentElement("afterend", launcher);
    launchers.set(select, launcher);
  }

  function customChoices(root) {
    const items = [...root.querySelectorAll(MENU_CHOICES)];
    if (!items.length && root.matches('[role="listbox"],[role="menu"]')) return [];
    return normalizeOptions(items, "custom");
  }

  function findPopupChoices() {
    if (!allowed()) return null;
    const containers = document.querySelectorAll(MENU_CONTAINERS);
    let best = null;
    // 最多处理36个可能的弹出容器，绝不遍历页面所有文字。
    for (const root of [...containers].slice(0, 36)) {
      if (!isVisible(root)) continue;
      const choices = customChoices(root);
      if (choices.length < 1 || choices.length > 32) continue;
      if (!best || choices.length > best.choices.length) best = { kind: "custom", anchor: root, choices };
    }
    return best;
  }

  function onPageInteraction(event) {
    if (!allowed() || Date.now() < quietUntil || isOwned(event.target)) return;
    const target = event.target;
    if (!(target instanceof Element)) return;
    const select = target.closest("select");
    if (select && !select.multiple && select.size <= 1) {
      attachNativeLauncher(select);
      return; // 仍允许浏览器默认原生选择器；旁边按钮提供中文对照。
    }
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (!allowed() || Date.now() < quietUntil) return;
      const menu = findPopupChoices();
      if (menu) void openMenu(menu);
    }, 240);
  }

  $("close").addEventListener("click", closeMenu);
  $("mode").addEventListener("change", async event => {
    settings.formMenuMode = event.target.value;
    try { await chrome.storage.sync.set({ formMenuMode: settings.formMenuMode }); } catch {}
    if (activeMenu?.translated) renderRows(activeMenu, activeMenu.translated);
  });
  $("original").addEventListener("click", () => {
    const select = activeMenu?.kind === "native" ? activeMenu.anchor : null;
    closeMenu();
    if (!select?.isConnected) return;
    select.focus();
    // showPicker 必须由用户主动点击调用；否则仅提示使用原生控件。
    try { if (typeof select.showPicker === "function") select.showPicker(); } catch {}
  });

  document.addEventListener("focusin", event => {
    const target = event.target;
    if (target instanceof HTMLSelectElement) attachNativeLauncher(target);
  }, true);
  document.addEventListener("click", onPageInteraction, true);
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") closeMenu();
    if (event.key === "Enter" || event.key === " ") onPageInteraction(event);
  }, true);
  window.addEventListener("scroll", () => {
    if (panel.classList.contains("open")) closeMenu();
  }, { passive: true, capture: true });

  async function loadSettings() {
    try { settings = { ...defaults, ...(await chrome.storage.sync.get(defaults)) }; } catch {}
    $("mode").value = mode();
    if (!allowed()) {
      closeMenu();
      for (const launcher of document.querySelectorAll(".ft-native-options-launcher")) launcher.remove();
    }
  }
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && Object.keys(changes).some(key => key in defaults)) void loadSettings();
  });

  if (document.documentElement) document.documentElement.appendChild(host);
  else document.addEventListener("DOMContentLoaded", () => document.documentElement?.appendChild(host), { once: true });
  void loadSettings();
})();