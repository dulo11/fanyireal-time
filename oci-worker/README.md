# FloatingTranslator OCI Language Proxy

这个 Cloudflare Worker 是 FloatingTranslator Browser v0.4 的 Oracle OCI Language 安全代理。

浏览器插件不会保存 OCI RSA 私钥。插件只向你自己的 Worker 发送待翻译文本；Worker 在服务端完成 OCI Request Signature，然后调用 OCI Language 的 `/20221001/actions/batchLanguageTranslation`。

## 目录

```text
oci-worker/
├── src/index.mjs
├── wrangler.toml
└── README.md
```

## 需要准备

在 OCI 中准备：

- Tenancy OCID
- User OCID
- API Key Fingerprint
- 对应 API Signing Private Key（PEM）
- Region，例如 `us-ashburn-1`
- Compartment OCID

你的 OCI 用户还需要拥有 Language 翻译权限。常见最小策略可按实际用户组和 compartment 改成：

```text
allow group <group-name> to use ai-service-language-family in compartment <compartment-name>
```

## 部署

进入本目录：

```bash
cd oci-worker
```

登录 Cloudflare：

```bash
npx wrangler login
```

把下面 7 项全部保存为 Worker Secret：

```bash
npx wrangler secret put OCI_TENANCY_OCID
npx wrangler secret put OCI_USER_OCID
npx wrangler secret put OCI_FINGERPRINT
npx wrangler secret put OCI_PRIVATE_KEY
npx wrangler secret put OCI_REGION
npx wrangler secret put OCI_COMPARTMENT_OCID
npx wrangler secret put PROXY_TOKEN
```

`OCI_PRIVATE_KEY` 可以粘贴完整多行 PEM。Worker 同时兼容 `BEGIN PRIVATE KEY`（PKCS#8）和 `BEGIN RSA PRIVATE KEY`（PKCS#1）。

`PROXY_TOKEN` 请自己设置一串足够长的随机字符串。浏览器插件调用 Worker 时会以 `Authorization: Bearer <token>` 发送。

部署：

```bash
npx wrangler deploy
```

部署完成后会得到类似：

```text
https://floatingtranslator-oci-proxy.<你的子域>.workers.dev
```

## 健康检查

浏览器打开：

```text
https://你的-worker.workers.dev/health
```

配置完整时应返回：

```json
{
  "ok": true,
  "service": "FloatingTranslator OCI Language Proxy",
  "configured": true,
  "missing": []
}
```

健康检查不会返回任何 OCID、私钥、Fingerprint 或 Token 的真实值。

## 插件设置

FloatingTranslator Browser 设置页：

1. 翻译引擎选择 `Oracle OCI Language（通过安全代理）`。
2. 代理地址填写 `https://你的-worker.workers.dev/translate`。
3. 代理 Token 填写和 `PROXY_TOKEN` 完全相同的值。
4. 建议保留“主引擎失败时回退 Google Web”，这样 OCI 不支持某语言或临时失败时网页仍可继续翻译。

## 请求格式

单条：

```json
{
  "text": "Hello world",
  "sourceLang": "auto",
  "targetLang": "zh-CN"
}
```

批量：

```json
{
  "texts": ["Hello", "Good morning"],
  "sourceLang": "en",
  "targetLang": "zh-CN"
}
```

返回：

```json
{
  "ok": true,
  "provider": "oci-language",
  "translatedText": "你好",
  "translations": ["你好", "早上好"],
  "sourceLanguages": ["en", "en"],
  "targetLanguage": "zh-CN",
  "opcRequestId": "..."
}
```

## 安全设计

- OCI 私钥只存在 Cloudflare Worker Secret。
- 浏览器扩展和 GitHub 仓库都不保存 OCI 私钥。
- Worker `/translate` 强制 Bearer Token。
- 单条文本、总字符数、批次数量都按 OCI Batch Translation 限制校验。
- Worker 使用 `x-date`、`host`、`x-content-sha256`、`content-type`、`content-length` 等字段构造 OCI RSA-SHA256 签名。
- 请求体用固定长度 `Uint8Array` 发给 OCI，让 Cloudflare Runtime 自动生成与签名计算一致的 `Content-Length`。

## 当前 OCI 语言范围

Worker 内置校验包括：中文简繁、英语、日语、韩语、越南语、泰语、法语、德语、西语、葡语、俄语、阿拉伯语等 OCI Language 当前翻译语言。

马来语、印尼语、菲律宾语等不在这组 OCI 翻译语言时，Worker 会返回 400；FloatingTranslator 如果开启 Google 回退，会自动切到备用引擎。

## 可选自定义 OCI Endpoint

标准商业区域默认自动使用：

```text
https://language.aiservice.<region>.oci.oraclecloud.com
```

如果以后使用特殊 Realm，可额外设置：

```bash
npx wrangler secret put OCI_LANGUAGE_ENDPOINT
```

值填写不带 `/20221001/actions/batchLanguageTranslation` 的服务根地址即可。
