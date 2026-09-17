(() => {
  if (globalThis.FTSiteExclusions) return;

  const STORAGE_KEY = "siteExclusionsV1";
  const BUILTIN_SELECTOR = "[translate='no'],.notranslate,[data-no-translate],[data-ft-no-translate]";
  let allRules = {};
  let selectors = [];
  let picking = false;
  let hoverTarget = null;
  let overlay = null;
  let banner = null;

  const ready = loadRules(false);

  function hostKey() {
    return String(location.hostname || "").toLowerCase();
  }

  function cleanSelectors(values) {
    const list = Array.isArray(values) ? values : [];
    const out = [];
    const seen = new Set();
    for (const raw of list) {
      const value = String(raw || "").trim();
      if (!value || seen.has(value)) continue;
      try { document.querySelector(value); } catch { continue; }
      seen.add(value);
      out.push(value);
      if (out.length >= 100) break;
    }
    return out;
  }

  async function loadRules(announce = true) {
    try {
      const stored = await chrome.storage.local.get({ [STORAGE_KEY]: {} });
      allRules = stored[STORAGE_KEY] && typeof stored[STORAGE_KEY] === "object" ? stored[STORAGE_KEY] : {};
      selectors = cleanSelectors(allRules[hostKey()]);
    } catch {
      allRules = {};
      selectors = [];
    }
    if (announce) window.dispatchEvent(new CustomEvent("ft-exclusions-changed", { detail: { selectors: [...selectors] } }));
    return selectors;
  }

  function closestMatch(element, selector) {
    try { return element?.closest?.(selector) || null; }
    catch { return null; }
  }

  function isExcluded(element) {
    let current = element instanceof Element ? element : element?.parentElement;
    while (current) {
      if (closestMatch(current, BUILTIN_SELECTOR)) return true;
      for (const selector of selectors) if (closestMatch(current, selector)) return true;
      const root = current.getRootNode?.();
      current = root instanceof ShadowRoot ? root.host : null;
    }
    return false;
  }

  function cssEscape(value) {
    if (globalThis.CSS?.escape) return CSS.escape(value);
    return String(value).replace(/[^a-zA-Z0-9_-]/g, char => `\\${char}`);
  }

  function unique(selector) {
    try { return document.querySelectorAll(selector).length === 1; }
    catch { return false; }
  }

  function selectorFor(element) {
    if (!(element instanceof Element)) return "";
    if (element.id) {
      const candidate = `#${cssEscape(element.id)}`;
      if (unique(candidate)) return candidate;
    }

    for (const attr of ["data-testid", "data-test", "data-qa", "name"]) {
      const value = element.getAttribute(attr);
      if (!value || value.length > 80) continue;
      const candidate = `${element.localName}[${attr}="${String(value).replace(/"/g, '\\"')}"]`;
      if (unique(candidate)) return candidate;
    }

    const classes = [...element.classList].filter(value => value && value.length <= 40 && !/^\d/.test(value)).slice(0, 3);
    if (classes.length) {
      const candidate = `${element.localName}${classes.map(value => `.${cssEscape(value)}`).join("")}`;
      if (unique(candidate)) return candidate;
    }

    const parts = [];
    let current = element;
    for (let depth = 0; current && current !== document.documentElement && depth < 6; depth++) {
      const tag = current.localName || "div";
      const parent = current.parentElement;
      if (!parent) {
        parts.unshift(tag);
        break;
      }
      const siblings = [...parent.children].filter(child => child.localName === tag);
      const index = Math.max(1, siblings.indexOf(current) + 1);
      parts.unshift(siblings.length > 1 ? `${tag}:nth-of-type(${index})` : tag);
      const candidate = parts.join(" > ");
      if (unique(candidate)) return candidate;
      current = parent;
    }
    return parts.join(" > ");
  }

  async function saveSelector(selector) {
    const host = hostKey();
    if (!host || !selector) return;
    const stored = await chrome.storage.local.get({ [STORAGE_KEY]: {} });
    const map = stored[STORAGE_KEY] && typeof stored[STORAGE_KEY] === "object" ? stored[STORAGE_KEY] : {};
    const next = cleanSelectors([...(map[host] || []), selector]);
    map[host] = next;
    await chrome.storage.local.set({ [STORAGE_KEY]: map });
    await loadRules(true);
  }

  async function clearCurrentSite() {
    const host = hostKey();
    const stored = await chrome.storage.local.get({ [STORAGE_KEY]: {} });
    const map = stored[STORAGE_KEY] && typeof stored[STORAGE_KEY] === "object" ? stored[STORAGE_KEY] : {};
    delete map[host];
    await chrome.storage.local.set({ [STORAGE_KEY]: map });
    await loadRules(true);
  }

  async function removeSelector(selector) {
    const host = hostKey();
    const stored = await chrome.storage.local.get({ [STORAGE_KEY]: {} });
    const map = stored[STORAGE_KEY] && typeof stored[STORAGE_KEY] === "object" ? stored[STORAGE_KEY] : {};
    const next = cleanSelectors((map[host] || []).filter(value => value !== selector));
    if (next.length) map[host] = next; else delete map[host];
    await chrome.storage.local.set({ [STORAGE_KEY]: map });
    await loadRules(true);
  }

  function ensurePickerUi() {
    overlay ||= document.createElement("div");
    overlay.dataset.ftOwned = "1";
    Object.assign(overlay.style, {
      position: "fixed", pointerEvents: "none", zIndex: "2147483646",
      border: "2px solid #7c3aed", background: "rgba(124,58,237,.12)", borderRadius: "6px",
      display: "none", boxSizing: "border-box"
    });
    if (!overlay.isConnected) document.documentElement.appendChild(overlay);

    banner ||= document.createElement("div");
    banner.dataset.ftOwned = "1";
    banner.textContent = "浮译：点击要排除翻译的区域 · Esc 取消";
    Object.assign(banner.style, {
      position: "fixed", left: "50%", top: "12px", transform: "translateX(-50%)",
      zIndex: "2147483647", padding: "8px 12px", borderRadius: "8px",
      background: "rgba(20,20,24,.94)", color: "white", font: "13px/1.4 system-ui,sans-serif",
      boxShadow: "0 4px 18px rgba(0,0,0,.28)", pointerEvents: "none"
    });
    if (!banner.isConnected) document.documentElement.appendChild(banner);
  }

  function moveOverlay(element) {
    if (!overlay || !(element instanceof Element)) return;
    const rect = element.getBoundingClientRect();
    Object.assign(overlay.style, {
      display: rect.width && rect.height ? "block" : "none",
      left: `${Math.max(0, rect.left)}px`, top: `${Math.max(0, rect.top)}px`,
      width: `${Math.max(0, Math.min(innerWidth - Math.max(0, rect.left), rect.width))}px`,
      height: `${Math.max(0, Math.min(innerHeight - Math.max(0, rect.top), rect.height))}px`
    });
  }

  function pickerMove(event) {
    const target = event.target instanceof Element ? event.target : null;
    if (!target || target.closest?.("[data-ft-owned='1']")) return;
    hoverTarget = target;
    moveOverlay(target);
  }

  async function pickerClick(event) {
    if (!picking) return;
    const target = event.target instanceof Element ? event.target : hoverTarget;
    if (!target || target.closest?.("[data-ft-owned='1']")) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    const selector = selectorFor(target);
    stopPicker();
    if (selector) await saveSelector(selector);
  }

  function pickerKey(event) {
    if (event.key === "Escape") {
      event.preventDefault();
      stopPicker();
    }
  }

  function stopPicker() {
    if (!picking) return;
    picking = false;
    hoverTarget = null;
    overlay?.remove();
    banner?.remove();
    overlay = null;
    banner = null;
    document.removeEventListener("pointermove", pickerMove, true);
    document.removeEventListener("click", pickerClick, true);
    document.removeEventListener("keydown", pickerKey, true);
  }

  function startPicker() {
    if (window.top !== window) return false;
    stopPicker();
    picking = true;
    ensurePickerUi();
    document.addEventListener("pointermove", pickerMove, true);
    document.addEventListener("click", pickerClick, true);
    document.addEventListener("keydown", pickerKey, true);
    return true;
  }

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (window.top !== window) return false;
    if (message?.type === "FT_PICK_EXCLUSION") {
      sendResponse({ ok: startPicker(), selectors: [...selectors] });
      return false;
    }
    if (message?.type === "FT_GET_EXCLUSIONS") {
      sendResponse({ ok: true, host: hostKey(), selectors: [...selectors] });
      return false;
    }
    if (message?.type === "FT_CLEAR_SITE_EXCLUSIONS") {
      clearCurrentSite().then(() => sendResponse({ ok: true, selectors: [] }))
        .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
      return true;
    }
    if (message?.type === "FT_REMOVE_EXCLUSION") {
      removeSelector(String(message.selector || "")).then(() => sendResponse({ ok: true, selectors: [...selectors] }))
        .catch(error => sendResponse({ ok: false, error: String(error?.message || error) }));
      return true;
    }
    return false;
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[STORAGE_KEY]) loadRules(true);
  });

  globalThis.FTSiteExclusions = Object.freeze({
    ready,
    isExcluded,
    selectors: () => [...selectors],
    startPicker
  });
})();