(() => {
  if (globalThis.__FT_SITE_INPUT_PROFILE__) return;
  globalThis.__FT_SITE_INPUT_PROFILE__ = true;

  const STORAGE_KEY = "siteInputLanguagesV1";
  const host = String(location.hostname || "").toLowerCase();
  if (!host) return;

  async function applyProfile() {
    try {
      const [local, sync] = await Promise.all([
        chrome.storage.local.get({ [STORAGE_KEY]: {} }),
        chrome.storage.sync.get({ inputSourceLang: "auto", inputTargetLang: "en" })
      ]);
      const profile = local[STORAGE_KEY]?.[host];
      if (!profile || typeof profile !== "object") return;
      const source = String(profile.sourceLang || "auto");
      const target = String(profile.targetLang || "en");
      const patch = {};
      if (sync.inputSourceLang !== source) patch.inputSourceLang = source;
      if (sync.inputTargetLang !== target) patch.inputTargetLang = target;
      if (Object.keys(patch).length) await chrome.storage.sync.set(patch);
    } catch {}
  }

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[STORAGE_KEY]) applyProfile();
  });

  applyProfile();
})();