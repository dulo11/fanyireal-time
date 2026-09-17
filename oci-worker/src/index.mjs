const OCI_API_PATH = "/20221001/actions/batchLanguageTranslation";
const MAX_RECORDS = 100;
const MAX_RECORD_CHARS = 4999;
const MAX_TOTAL_CHARS = 20000;

const SUPPORTED_LANGS = new Set([
  "ar", "pt-BR", "fr-CA", "hr", "cs", "da", "nl", "en", "fi", "fr",
  "de", "el", "he", "hu", "it", "ja", "ko", "no", "pl", "pt", "ro",
  "ru", "zh-CN", "sk", "sl", "es", "sv", "th", "zh-TW", "tr", "vi"
]);

let cachedPrivateKeyPem = "";
let cachedPrivateKeyPromise = null;

function corsHeaders() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "authorization,content-type",
    "access-control-max-age": "86400"
  };
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      ...corsHeaders(),
      ...extraHeaders
    }
  });
}

function normalizeLanguage(value, { allowAuto = false } = {}) {
  const raw = String(value || "").trim();
  if (!raw) return allowAuto ? "auto" : "";
  const lower = raw.toLowerCase().replaceAll("_", "-");

  if (allowAuto && lower === "auto") return "auto";
  if (["zh-cn", "zh-hans", "zh-sg"].includes(lower)) return "zh-CN";
  if (["zh-tw", "zh-hant", "zh-hk"].includes(lower)) return "zh-TW";
  if (lower === "pt-br") return "pt-BR";
  if (lower === "fr-ca") return "fr-CA";

  const short = lower.split("-")[0];
  if (SUPPORTED_LANGS.has(short)) return short;
  return raw;
}

function codePointLength(value) {
  return Array.from(String(value || "")).length;
}

function validateConfig(env) {
  const required = [
    "OCI_TENANCY_OCID",
    "OCI_USER_OCID",
    "OCI_FINGERPRINT",
    "OCI_PRIVATE_KEY",
    "OCI_REGION",
    "OCI_COMPARTMENT_OCID",
    "PROXY_TOKEN"
  ];
  return required.filter(name => !String(env[name] || "").trim());
}

function extractBearer(request) {
  const header = request.headers.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || "";
}

function timingSafeEqualString(a, b) {
  const left = new TextEncoder().encode(String(a || ""));
  const right = new TextEncoder().encode(String(b || ""));
  const max = Math.max(left.length, right.length);
  let diff = left.length ^ right.length;
  for (let i = 0; i < max; i++) {
    diff |= (left[i % Math.max(left.length, 1)] || 0) ^ (right[i % Math.max(right.length, 1)] || 0);
  }
  return diff === 0;
}

function concatBytes(...chunks) {
  const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

function encodeDerLength(length) {
  if (length < 128) return Uint8Array.of(length);
  const bytes = [];
  let value = length;
  while (value > 0) {
    bytes.unshift(value & 0xff);
    value >>>= 8;
  }
  return Uint8Array.of(0x80 | bytes.length, ...bytes);
}

function wrapPkcs1InPkcs8(pkcs1) {
  const version = Uint8Array.of(0x02, 0x01, 0x00);
  const rsaAlgorithmIdentifier = Uint8Array.of(
    0x30, 0x0d,
    0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01,
    0x05, 0x00
  );
  const privateKeyOctetString = concatBytes(
    Uint8Array.of(0x04),
    encodeDerLength(pkcs1.length),
    pkcs1
  );
  const body = concatBytes(version, rsaAlgorithmIdentifier, privateKeyOctetString);
  return concatBytes(Uint8Array.of(0x30), encodeDerLength(body.length), body);
}

function decodePem(pem) {
  const normalized = String(pem || "").replaceAll("\\n", "\n").trim();
  const isPkcs1 = normalized.includes("BEGIN RSA PRIVATE KEY");
  const base64 = normalized
    .replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\s+/g, "");
  if (!base64) throw new Error("OCI_PRIVATE_KEY 不是有效的 PEM 私钥");

  const binary = atob(base64);
  const raw = Uint8Array.from(binary, char => char.charCodeAt(0));
  return isPkcs1 ? wrapPkcs1InPkcs8(raw) : raw;
}

async function importPrivateKey(pem) {
  if (cachedPrivateKeyPromise && cachedPrivateKeyPem === pem) return cachedPrivateKeyPromise;
  cachedPrivateKeyPem = pem;
  cachedPrivateKeyPromise = crypto.subtle.importKey(
    "pkcs8",
    decodePem(pem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return cachedPrivateKeyPromise;
}

function bytesToBase64(value) {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

async function sha256Base64(bytes) {
  return bytesToBase64(await crypto.subtle.digest("SHA-256", bytes));
}

async function signOciRequest({ env, host, bodyBytes }) {
  const xDate = new Date().toUTCString();
  const contentType = "application/json";
  const contentLength = bodyBytes.byteLength;
  const contentSha256 = await sha256Base64(bodyBytes);
  const signedHeaders = "(request-target) host x-date x-content-sha256 content-type content-length";
  const signingString = [
    `(request-target): post ${OCI_API_PATH}`,
    `host: ${host}`,
    `x-date: ${xDate}`,
    `x-content-sha256: ${contentSha256}`,
    `content-type: ${contentType}`,
    `content-length: ${contentLength}`
  ].join("\n");

  const key = await importPrivateKey(env.OCI_PRIVATE_KEY);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingString)
  );

  const keyId = `${env.OCI_TENANCY_OCID}/${env.OCI_USER_OCID}/${env.OCI_FINGERPRINT}`;
  const authorization = [
    'Signature version="1"',
    `keyId="${keyId}"`,
    'algorithm="rsa-sha256"',
    `headers="${signedHeaders}"`,
    `signature="${bytesToBase64(signature)}"`
  ].join(",");

  return {
    authorization,
    xDate,
    contentSha256,
    contentType
  };
}

function normalizeIncomingBody(body) {
  const texts = Array.isArray(body?.texts)
    ? body.texts
    : body?.text !== undefined
      ? [body.text]
      : [];

  const clean = texts.map(value => String(value ?? ""));
  const sourceLang = normalizeLanguage(body?.sourceLang || "auto", { allowAuto: true });
  const targetLang = normalizeLanguage(body?.targetLang || "zh-CN");
  return { texts: clean, sourceLang, targetLang };
}

function validateTranslationRequest({ texts, sourceLang, targetLang }) {
  if (!texts.length) return "缺少 text 或 texts";
  if (texts.length > MAX_RECORDS) return `一次最多 ${MAX_RECORDS} 条文本`;
  if (!SUPPORTED_LANGS.has(targetLang)) return `OCI 暂不支持目标语言：${targetLang}`;
  if (sourceLang !== "auto" && !SUPPORTED_LANGS.has(sourceLang)) return `OCI 暂不支持源语言：${sourceLang}`;

  let total = 0;
  for (const text of texts) {
    const length = codePointLength(text);
    if (!text.trim()) return "文本不能为空";
    if (length > MAX_RECORD_CHARS) return `单条文本必须少于 5000 字符，当前 ${length}`;
    total += length;
  }
  if (total > MAX_TOTAL_CHARS) return `单次总字符数不能超过 ${MAX_TOTAL_CHARS}，当前 ${total}`;
  return "";
}

async function callOciLanguage(env, requestData) {
  const endpoint = String(env.OCI_LANGUAGE_ENDPOINT || "").trim().replace(/\/$/, "") ||
    `https://language.aiservice.${env.OCI_REGION}.oci.oraclecloud.com`;
  const endpointUrl = new URL(endpoint);
  const host = endpointUrl.host;

  const documents = requestData.texts.map((text, index) => {
    const document = { key: String(index), text };
    if (requestData.sourceLang !== "auto") document.languageCode = requestData.sourceLang;
    return document;
  });

  const payload = {
    compartmentId: env.OCI_COMPARTMENT_OCID,
    targetLanguageCode: requestData.targetLang,
    documents
  };

  const bodyText = JSON.stringify(payload);
  const bodyBytes = new TextEncoder().encode(bodyText);
  const signed = await signOciRequest({ env, host, bodyBytes });

  const response = await fetch(`${endpoint}${OCI_API_PATH}`, {
    method: "POST",
    headers: {
      authorization: signed.authorization,
      "x-date": signed.xDate,
      "x-content-sha256": signed.contentSha256,
      "content-type": signed.contentType,
      accept: "application/json"
    },
    // Uint8Array makes Cloudflare set an exact Content-Length automatically.
    body: bodyBytes
  });

  const responseText = await response.text();
  let data = null;
  try {
    data = responseText ? JSON.parse(responseText) : {};
  } catch {
    data = { raw: responseText };
  }

  if (!response.ok) {
    const error = new Error(`OCI Language HTTP ${response.status}`);
    error.status = response.status;
    error.ociRequestId = response.headers.get("opc-request-id") || "";
    error.ociBody = data;
    throw error;
  }

  const translatedByKey = new Map(
    (Array.isArray(data?.documents) ? data.documents : [])
      .map(item => [String(item?.key ?? ""), item?.translatedText])
  );

  const translations = requestData.texts.map((original, index) => {
    const translated = translatedByKey.get(String(index));
    return typeof translated === "string" ? translated : original;
  });

  if (Array.isArray(data?.errors) && data.errors.length) {
    const error = new Error("OCI Language 部分文本翻译失败");
    error.status = 502;
    error.ociRequestId = response.headers.get("opc-request-id") || "";
    error.ociBody = { errors: data.errors };
    throw error;
  }

  return {
    translations,
    sourceLanguages: (data?.documents || []).map(item => item?.sourceLanguageCode || null),
    targetLanguage: requestData.targetLang,
    opcRequestId: response.headers.get("opc-request-id") || ""
  };
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });

    const url = new URL(request.url);
    const missing = validateConfig(env);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      return json({
        ok: missing.length === 0,
        service: "FloatingTranslator OCI Language Proxy",
        configured: missing.length === 0,
        missing
      }, missing.length ? 503 : 200);
    }

    if (request.method !== "POST" || url.pathname !== "/translate") {
      return json({ ok: false, error: "Not Found" }, 404);
    }

    if (missing.length) {
      return json({ ok: false, error: "Worker 尚未配置完成", missing }, 503);
    }

    const token = extractBearer(request);
    if (!token || !timingSafeEqualString(token, env.PROXY_TOKEN)) {
      return json({ ok: false, error: "Unauthorized" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ ok: false, error: "请求体必须是 JSON" }, 400);
    }

    const requestData = normalizeIncomingBody(body);
    const validationError = validateTranslationRequest(requestData);
    if (validationError) return json({ ok: false, error: validationError }, 400);

    try {
      const result = await callOciLanguage(env, requestData);
      return json({
        ok: true,
        provider: "oci-language",
        translatedText: result.translations[0] || "",
        translations: result.translations,
        sourceLanguages: result.sourceLanguages,
        targetLanguage: result.targetLanguage,
        opcRequestId: result.opcRequestId
      });
    } catch (error) {
      console.error("[OCI Language]", error);
      return json({
        ok: false,
        error: error?.message || "OCI Language 调用失败",
        oracleStatus: error?.status || null,
        opcRequestId: error?.ociRequestId || "",
        oracle: error?.ociBody || null
      }, error?.status && error.status >= 400 && error.status < 600 ? 502 : 500);
    }
  }
};
