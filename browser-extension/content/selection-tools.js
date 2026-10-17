(() => {
  if (window.top !== window || window.__FT_SELECTION_TOOLS_V12__) return;
  window.__FT_SELECTION_TOOLS_V12__ = true;

  const options = { enabled: true, inputQuickTranslate: false, sourceLang: "auto", targetLang: "zh-CN", inputTargetLang: "en" };
  const host = document.createElement("div");
  host.dataset.ftOwned = "1";
  host.id = "ft-selection-action-host";
  host.style.cssText = "position:fixed;inset:0;z-index:2147483645;pointer-events:none;contain:style";
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `
    <style>
      *{box-sizing:border-box;font-family:system-ui,"Noto Sans SC",sans-serif}
      button{touch-action:manipulation;pointer-events:auto;border:0;border-radius:12px;padding:7px 11px;min-height:36px;font-size:13px;box-shadow:0 5px 20px #0003}
      #trigger{position:fixed;display:none;background:#6750e8;color:white;font-weight:700}
      #card{position:fixed;display:none;pointer-events:auto;width:min(320px,calc(100vw - 20px));max-height:min(260px,50vh);overflow:auto;border-radius:16px;background:#fff;color:#202124;padding:12px;box-shadow:0 14px 40px #0004;border:1px solid #ddd}
      #result{font-size:14px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0;user-select:text;-webkit-user-select:text}
      .bar{display:flex;align-items:center;gap:7px}.bar span{flex:1;font-weight:700;font-size:13px}
      .bar button{background:#eee;color:#333;min-height:30px}
      .hint{color:#666;font-size:11px}
      @media(prefers-color-scheme:dark){#card{background:#28282f;color:#f5f5f5;border-color:#444}.bar button{background:#444;color:#eee}.hint{color:#aaa}}
    </style>
    <button id="trigger" type="button" aria-label="翻译选中的文字">译</button>
    <section id="card" role="dialog" aria-label="选词翻译结果">
      <div class="bar"><span>浮译 · 划词翻译</span><button id="copy" type="button">复制</button><button id="close" type="button">×</button></div>
      <div id="result">翻译中…</div><div class="hint">选词翻译只在点击「译」后请求，不会自动上传你选中的内容。</div>
    </section>`;
  const trigger = root.getElementById("trigger");
  const card = root.getElementById("card");
  const result = root.getElementById("result");
  let selected = "";
  let selectedInput = null;
  let requestSeq = 0;
  let selectionTimer = null;
  let lastInputTap = { el: null, at: 0 };

  function place(el, rect) {
    const w = Math.min(el === card ? 320 : 44, Math.max(240, innerWidth - 20));
    const left = Math.max(10, Math.min(innerWidth - w - 10, rect?.left || 12));
    const top = Math.max(12, Math.min(innerHeight - (el === card ? 210 : 48), (rect?.bottom || 75) + 8));
    el.style.left = left + "px";
    el.style.top = top + "px";
  }
  function hideTrigger() { trigger.style.display = "none"; }
  function selectedText() {
    try {
      const selection = window.getSelection();
      const text = String(selection?.toString() || "").trim();
      const start = selection?.anchorNode?.parentElement;
      if (start?.closest?.("input,textarea,[contenteditable],#ft-selection-action-host")) return null;
      if (!text || text.length > 1500 || !selection?.rangeCount) return null;
      return { text, rect: selection.getRangeAt(0).getBoundingClientRect() };
    } catch { return null; }
  }
  function refreshSelection() {
    const selection = selectedText();
    if (!selection || card.style.display === "block") { hideTrigger(); return; }
    selected = selection.text;
    selectedInput = null;
    place(trigger, selection.rect);
    trigger.style.display = "block";
  }
  async function translateSelection(text, targetLang, rect) {
    const seq = ++requestSeq;
    card.style.display = "block";
    place(card, rect);
    result.textContent = "翻译中…";
    hideTrigger();
    if (options.enabled === false) {
      result.textContent = "浮译已停用";
      return;
    }
    try {
      const response = await Promise.race([
        chrome.runtime.sendMessage({
          type: "FT_TRANSLATE",
          texts: [text],
          options: { sourceLang: options.sourceLang || "auto", targetLang }
        }),
        new Promise((_, reject) => setTimeout(() => reject(new Error("翻译请求超时")), 18000))
      ]);
      if (seq !== requestSeq) return;
      if (!response?.ok) throw new Error(response?.error || "翻译失败");
      const translated = String(response.translations?.[0] || text);
      result.textContent = translated;
      // 快捷输入仅显示译文，由用户自行复制/粘贴，避免自动更改聊天内容。
    } catch (err) {
      if (seq === requestSeq) result.textContent = "翻译失败：" + String(err?.message || err);
    }
  }

  root.getElementById("close").addEventListener("click", () => {
    card.style.display = "none"; requestSeq++;
  });
  root.getElementById("copy").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(result.textContent || "");
      root.getElementById("copy").textContent = "已复制";
    } catch { root.getElementById("copy").textContent = "长按译文复制"; }
  });
  trigger.addEventListener("pointerdown", event => event.preventDefault());
  trigger.addEventListener("click", () => {
    if (!selected) return;
    void translateSelection(selected, options.targetLang || "zh-CN");
  });
  document.addEventListener("selectionchange", () => {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(refreshSelection, 280);
  }, { passive: true });
  document.addEventListener("pointerup", () => setTimeout(refreshSelection, 300), { passive: true });
  document.addEventListener("scroll", hideTrigger, { capture: true, passive: true });
  document.addEventListener("pointerdown", event => {
    if (!options.inputQuickTranslate || options.enabled === false) return;
    const el = event.target;
    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) return;
    if (el instanceof HTMLInputElement && !["text", "search", "email", "url"].includes(el.type)) return;
    const now = Date.now();
    if (lastInputTap.el === el && now - lastInputTap.at < 320 && el.value.trim()) {
      lastInputTap = { el: null, at: 0 };
      const rect = el.getBoundingClientRect();
      void translateSelection(el.value.trim().slice(0, 1500), options.inputTargetLang || "en", rect);
    } else lastInputTap = { el, at: now };
  }, { passive: true });
  document.addEventListener("input", event => {
    if (!options.inputQuickTranslate || options.enabled === false || !event.isTrusted) return;
    const el = event.target;
    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) return;
    if (el instanceof HTMLInputElement && !["text", "search", "email", "url"].includes(el.type)) return;
    if (!el.value.endsWith("   ")) return;
    const text = el.value.trim().slice(0, 1500);
    if (text) void translateSelection(text, options.inputTargetLang || "en", el.getBoundingClientRect());
  }, true);

  async function refreshOptions() {
    try { Object.assign(options, await chrome.storage.sync.get(options)); } catch {}
    if (options.enabled === false) { card.style.display = "none"; hideTrigger(); }
  }
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "sync") return;
    for (const [key, change] of Object.entries(changes)) if (key in options) options[key] = change.newValue;
    if (options.enabled === false) { card.style.display = "none"; hideTrigger(); }
  });
  if (document.documentElement) document.documentElement.appendChild(host);
  else document.addEventListener("DOMContentLoaded", () => document.documentElement?.appendChild(host), { once: true });
  void refreshOptions();
})();