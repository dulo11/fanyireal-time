# FloatingTranslator Browser v0.4

浏览器持续网页翻译版。目标是像 Chrome / Edge 自带网页翻译一样：页面加载多少内容，就持续翻译多少内容；动态加载的新帖子、评论和聊天消息也会自动进入翻译队列。

## 当前功能

- Manifest V3
- 自动持续翻译网页 DOM 文本
- MutationObserver 监听动态新增/变化内容
- 当前可视区域优先处理
- 仅译文 / 原文+译文
- 始终翻译此网站 / 永不翻译此网站
- IndexedDB 翻译缓存
- 选中文字右键翻译
- 输入框 `Alt + Enter` 翻译
- 网页聊天输入实时预翻译
- Open Shadow DOM 扫描与动态监听
- Google Web 实验性引擎
- Microsoft / Azure Translator 官方接口
- Oracle OCI Language 正式接口
- Cloudflare Worker -> OCI RSA-SHA256 完整安全代理
- 主引擎失败时可自动回退 Google Web
- API Key / Token 只保存到浏览器本地扩展存储，不写入仓库
- GitHub Actions 自动校验并分别打包浏览器插件和 OCI Worker

## Oracle OCI Language v0.4

v0.4 已不再只是预留 OCI 代理入口，仓库现在直接包含可部署的 Cloudflare Worker：

```text
oci-worker/
├── src/index.mjs
├── wrangler.toml
└── README.md
```

完整链路：

```text
FloatingTranslator Browser
        ↓ Bearer Token
Cloudflare Worker
        ↓ OCI RSA-SHA256 Request Signature
Oracle OCI Language
```

OCI 的 Tenancy OCID、User OCID、Fingerprint、Compartment OCID 和 RSA 私钥全部放在 Worker Secret 中。浏览器扩展只保存 Worker `/translate` 地址和代理 Token。

Worker 支持：

- OCI `/20221001/actions/batchLanguageTranslation`
- `x-date` / `host` / `x-content-sha256` / `content-type` / `content-length` 签名
- RSA-SHA256
- PKCS#8 `BEGIN PRIVATE KEY`
- PKCS#1 `BEGIN RSA PRIVATE KEY`
- 单条和批量文本
- OCI 语言范围检查
- 单条 5000 字符以内、每批 100 条、总计 20000 字符限制
- `/health` 配置检查
- Bearer Token 防止公开 Worker 被别人直接滥用

部署说明见仓库 `oci-worker/README.md`。

## 翻译引擎回退

高级设置中可以开启“主引擎失败时自动回退到 Google Web”。例如：

- OCI 当前不支持某个语言
- OCI 权限/额度/网络临时异常
- Azure Key 临时失效

出现这些情况时，插件仍可以尝试备用引擎继续网页翻译。

如果不希望调用实验性的 Google Web 接口，可关闭回退。

## 自动生成安装包

`.github/workflows/build-browser-extension.yml` 当前会自动：

1. 校验 `manifest.json`。
2. 检查浏览器插件核心文件。
3. 校验所有浏览器 JavaScript 语法。
4. 校验 `oci-worker/src/index.mjs` 语法。
5. 自动读取版本号。
6. 生成 `FloatingTranslator-Browser-v0.4.0.zip`。
7. 生成 `FloatingTranslator-OCI-Worker-v0.4.0.zip`。
8. 上传到 GitHub Actions Artifacts。

## 安装

### Chrome / Edge 桌面版

1. 下载 Actions 生成的浏览器插件 ZIP 并解压。
2. 打开扩展管理页面并开启开发者模式。
3. 选择“加载已解压的扩展程序”。
4. 选择包含 `manifest.json` 的目录。

### Quetta Android

使用 Actions 自动生成的浏览器插件 ZIP 测试。手机弹窗已按窄屏布局处理；具体导入入口以当前 Quetta 扩展管理页为准。

## 下一步

- OCI 请求批处理进一步减少网络开销
- 长页面调度/取消与并发优化
- 混合语言页面逐段识别
- Telegram Web / WhatsApp Web / Discord 专项适配
- PDF 翻译
- YouTube / 网页视频双语字幕
- 图片 OCR
- Android 与 Browser 共用规则、术语表和语言偏好
