(() => {
  const DEFAULT_ORDER = ["azure", "baidu", "aliyun", "google-web"];
  const PROVIDERS = {
    azure: { label: "Azure", key: "azureCredentials" },
    baidu: { label: "百度翻译", key: "baiduCredentials" },
    aliyun: { label: "阿里云翻译", key: "aliyunCredentials" },
    "google-web": { label: "Google Web", key: "" }
  };

  const DEFAULTS = {
    providerPoolEnabled: true,
    providerOrder: DEFAULT_ORDER,
    providerCooldownMs: 60000,
    azureCredentials: [],
    baiduCredentials: [],
    aliyunCredentials: [],
    azureKey: "",
    azureRegion: "",
    azureEndpoint: "https://api.cognitive.microsofttranslator.com"
  };

  const state = {
    providerOrder: [...DEFAULT_ORDER],
    azureCredentials: [],
    baiduCredentials: [],
    aliyunCredentials: []
  };

  const $ = id => document.getElementById(id);

  function newId(provider) {
    return `${provider}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  }

  function normalizeOrder(order) {
    const clean = [];
    for (const item of Array.isArray(order) ? order : []) {
      if (DEFAULT_ORDER.includes(item) && !clean.includes(item)) clean.push(item);
    }
    for (const item of DEFAULT_ORDER) if (!clean.includes(item)) clean.push(item);
    return clean;
  }

  function makeCredential(provider, index) {
    if (provider === "azure") {
      return { id: newId(provider), label: `Azure Key ${index + 1}`, enabled: true, key: "", region: "", endpoint: "https://api.cognitive.microsofttranslator.com" };
    }
    if (provider === "baidu") {
      return { id: newId(provider), label: `百度凭据 ${index + 1}`, enabled: true, appId: "", apiKey: "", secret: "", endpoint: "https://fanyi-api.baidu.com/ait/api/aiTextTranslate" };
    }
    return { id: newId(provider), label: `阿里云凭据 ${index + 1}`, enabled: true, accessKeyId: "", accessKeySecret: "", endpoint: "https://mt.cn-hangzhou.aliyuncs.com" };
  }

  function secretInput(credential, field, placeholder) {
    const input = document.createElement("input");
    input.type = "password";
    input.autocomplete = "off";
    input.placeholder = credential[field] ? `${placeholder}（已保存，留空不修改）` : placeholder;
    input.addEventListener("input", () => {
      if (input.value) credential[field] = input.value;
    });
    return input;
  }

  function textInput(credential, field, placeholder, type = "text") {
    const input = document.createElement("input");
    input.type = type;
    input.value = credential[field] || "";
    input.placeholder = placeholder;
    input.addEventListener("input", () => { credential[field] = input.value.trim(); });
    return input;
  }

  function labelWrap(title, input) {
    const label = document.createElement("label");
    const span = document.createElement("span");
    span.textContent = title;
    label.append(span, input);
    return label;
  }

  function actionButton(text, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = text;
    button.addEventListener("click", onClick);
    return button;
  }

  function renderCredentialCard(provider, credential, index) {
    const card = document.createElement("div");
    card.className = "credential-card";

    const top = document.createElement("div");
    top.className = "credential-head";
    const title = document.createElement("strong");
    title.textContent = credential.label || `${PROVIDERS[provider].label} ${index + 1}`;
    const enabledLabel = document.createElement("label");
    enabledLabel.className = "check-row compact-check";
    const enabledBox = document.createElement("input");
    enabledBox.type = "checkbox";
    enabledBox.checked = credential.enabled !== false;
    enabledBox.addEventListener("change", () => { credential.enabled = enabledBox.checked; });
    const enabledText = document.createElement("span");
    enabledText.textContent = "启用";
    enabledLabel.append(enabledBox, enabledText);
    top.append(title, enabledLabel);
    card.append(top);

    const labelInput = textInput(credential, "label", "备注名称");
    labelInput.addEventListener("input", () => { title.textContent = labelInput.value || `${PROVIDERS[provider].label} ${index + 1}`; });
    card.append(labelWrap("名称", labelInput));

    if (provider === "azure") {
      card.append(
        labelWrap("Azure Key", secretInput(credential, "key", "Ocp-Apim-Subscription-Key")),
        labelWrap("Region", textInput(credential, "region", "例如 eastasia")),
        labelWrap("Endpoint", textInput(credential, "endpoint", "https://api.cognitive.microsofttranslator.com", "url"))
      );
    } else if (provider === "baidu") {
      card.append(
        labelWrap("APPID", textInput(credential, "appId", "百度翻译 APPID")),
        labelWrap("API Key（推荐）", secretInput(credential, "apiKey", "Bearer API Key")),
        labelWrap("传统密钥（可选）", secretInput(credential, "secret", "没有 API Key 时使用 APPID + 密钥签名")),
        labelWrap("Endpoint", textInput(credential, "endpoint", "https://fanyi-api.baidu.com/ait/api/aiTextTranslate", "url"))
      );
    } else if (provider === "aliyun") {
      card.append(
        labelWrap("AccessKey ID", textInput(credential, "accessKeyId", "LTAI...")),
        labelWrap("AccessKey Secret", secretInput(credential, "accessKeySecret", "AccessKey Secret")),
        labelWrap("Endpoint", textInput(credential, "endpoint", "https://mt.cn-hangzhou.aliyuncs.com", "url"))
      );
    }

    const actions = document.createElement("div");
    actions.className = "actions credential-actions";
    const clearSecrets = actionButton("清除密钥", () => {
      if (provider === "azure") credential.key = "";
      if (provider === "baidu") { credential.apiKey = ""; credential.secret = ""; }
      if (provider === "aliyun") credential.accessKeySecret = "";
      renderProvider(provider);
    });
    const remove = actionButton("删除此凭据", () => {
      const key = PROVIDERS[provider].key;
      state[key].splice(index, 1);
      renderProvider(provider);
    });
    actions.append(clearSecrets, remove);
    card.append(actions);
    return card;
  }

  function renderProvider(provider) {
    const container = $(`${provider}Credentials`);
    if (!container) return;
    container.innerHTML = "";
    const key = PROVIDERS[provider].key;
    const list = state[key] || [];
    if (!list.length) {
      const empty = document.createElement("p");
      empty.className = "note";
      empty.textContent = "还没有凭据。";
      container.append(empty);
      return;
    }
    list.forEach((credential, index) => container.append(renderCredentialCard(provider, credential, index)));
  }

  function renderOrder() {
    state.providerOrder = normalizeOrder(state.providerOrder);
    for (let i = 0; i < 4; i++) {
      const select = $(`providerOrder${i + 1}`);
      if (!select) continue;
      select.innerHTML = "";
      for (const provider of DEFAULT_ORDER) {
        const option = document.createElement("option");
        option.value = provider;
        option.textContent = PROVIDERS[provider].label;
        select.append(option);
      }
      select.value = state.providerOrder[i];
      select.onchange = () => {
        state.providerOrder[i] = select.value;
        state.providerOrder = normalizeOrder(state.providerOrder);
        renderOrder();
      };
    }
  }

  async function loadPool() {
    const local = await chrome.storage.local.get(DEFAULTS);
    $("providerPoolEnabled").checked = local.providerPoolEnabled !== false;
    $("providerCooldownSeconds").value = String(Math.max(5, Math.round(Number(local.providerCooldownMs || 60000) / 1000)));
    state.providerOrder = normalizeOrder(local.providerOrder);
    state.azureCredentials = Array.isArray(local.azureCredentials) ? structuredClone(local.azureCredentials) : [];
    state.baiduCredentials = Array.isArray(local.baiduCredentials) ? structuredClone(local.baiduCredentials) : [];
    state.aliyunCredentials = Array.isArray(local.aliyunCredentials) ? structuredClone(local.aliyunCredentials) : [];

    if (!state.azureCredentials.length && local.azureKey) {
      state.azureCredentials.push({
        id: "azure-legacy",
        label: "Azure 原有 Key",
        enabled: true,
        key: local.azureKey,
        region: local.azureRegion || "",
        endpoint: local.azureEndpoint || DEFAULTS.azureEndpoint
      });
    }

    renderOrder();
    renderProvider("azure");
    renderProvider("baidu");
    renderProvider("aliyun");
    await refreshPoolStatus();
  }

  async function savePool() {
    state.providerOrder = normalizeOrder([
      $("providerOrder1").value,
      $("providerOrder2").value,
      $("providerOrder3").value,
      $("providerOrder4").value
    ]);
    const cooldownSeconds = Math.max(5, Math.min(3600, Number($("providerCooldownSeconds").value) || 60));
    await chrome.storage.local.set({
      providerPoolEnabled: $("providerPoolEnabled").checked,
      providerOrder: state.providerOrder,
      providerCooldownMs: cooldownSeconds * 1000,
      azureCredentials: state.azureCredentials,
      baiduCredentials: state.baiduCredentials,
      aliyunCredentials: state.aliyunCredentials
    });
    await chrome.runtime.sendMessage({ type: "FT_PROVIDER_POOL_RESET_HEALTH" }).catch(() => null);
    $("providerPoolSaveStatus").textContent = "多引擎配置已保存";
    setTimeout(() => { $("providerPoolSaveStatus").textContent = ""; }, 3000);
    await refreshPoolStatus();
  }

  function statusLine(provider, item) {
    if (item.authBlocked) return `${item.label}：认证错误，已停用到你修改凭据`;
    if (item.cooldownUntil > Date.now()) {
      const seconds = Math.max(1, Math.ceil((item.cooldownUntil - Date.now()) / 1000));
      return `${item.label}：冷却中，约 ${seconds}s 后再试${item.lastError ? ` · ${item.lastError}` : ""}`;
    }
    if (item.lastError) return `${item.label}：可用（上次错误已过冷却） · ${item.lastError}`;
    return `${item.label}：可用`;
  }

  async function refreshPoolStatus() {
    const node = $("providerPoolStatus");
    if (!node) return;
    const response = await chrome.runtime.sendMessage({ type: "FT_PROVIDER_POOL_STATUS" }).catch(error => ({ ok: false, error: error?.message || error }));
    if (!response?.ok) {
      node.textContent = `凭据池状态：读取失败${response?.error ? ` · ${response.error}` : ""}`;
      return;
    }
    const status = response.status || {};
    const lines = [`凭据池：${status.enabled ? "已启用" : "已关闭"} · 顺序 ${status.order?.map(item => PROVIDERS[item]?.label || item).join(" → ") || "-"}`];
    for (const provider of ["azure", "baidu", "aliyun"]) {
      for (const item of status.providers?.[provider] || []) lines.push(statusLine(provider, item));
    }
    node.textContent = lines.join("\n");
  }

  function bindAdd(provider) {
    $(`add${provider[0].toUpperCase()}${provider.slice(1)}Credential`)?.addEventListener("click", () => {
      const key = PROVIDERS[provider].key;
      state[key].push(makeCredential(provider, state[key].length));
      renderProvider(provider);
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (!$("providerPoolEnabled")) return;
    bindAdd("azure");
    bindAdd("baidu");
    bindAdd("aliyun");
    $("saveProviderPool")?.addEventListener("click", () => savePool().catch(error => { $("providerPoolSaveStatus").textContent = `保存失败：${error?.message || error}`; }));
    $("refreshProviderPoolStatus")?.addEventListener("click", () => refreshPoolStatus());
    $("resetProviderPoolHealth")?.addEventListener("click", async () => {
      await chrome.runtime.sendMessage({ type: "FT_PROVIDER_POOL_RESET_HEALTH" });
      await refreshPoolStatus();
    });
    loadPool().catch(error => { $("providerPoolStatus").textContent = `加载失败：${error?.message || error}`; });
    setInterval(refreshPoolStatus, 5000);
  });
})();