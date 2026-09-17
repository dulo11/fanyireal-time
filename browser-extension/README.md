# FloatingTranslator Browser v0.3

浏览器持续网页翻译版。目标是像 Chrome / Edge 自带网页翻译一样：页面加载多少内容，就持续翻译多少内容；动态加载的新帖子、评论和聊天消息也会自动加入翻译队列。

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
- Oracle OCI Language（通过安全代理）
- 主引擎失败时可自动回退 Google Web
- API Key / Token 只保存到浏览器本地扩展存储，不写入仓库
- GitHub Actions 自动校验 JavaScript 并打包 ZIP

## Oracle OCI Language

OCI Language 提供正式的机器翻译 API。OCI REST 请求需要 RSA API Signing Key 签名，因此不建议把 OCI 私钥直接存进浏览器插件。

v0.3 使用更安全的结构：

`浏览器插件 -> 你自己的 Worker/服务器代理 -> OCI Language`

扩展设置里只保存代理地址和可选的代理 Token。OCI 的 Tenancy OCID、User OCID、Fingerprint 和 RSA 私钥应该保存在代理服务器的 Secret 中，不能提交到公开 GitHub 仓库。

扩展发送给代理的请求格式：

```json
{
  "text": "Hello world",
  "sourceLang": "auto",
  "targetLang": "zh-CN"
}
```

代理返回以下任意一种格式即可：

```json
{"translatedText":"你好，世界"}
```

或：

```json
{"translation":"你好，世界"}
```

## 翻译引擎回退

在高级设置中可开启“主引擎失败时自动回退到 Google Web”。例如 OCI 免费额度用完、Azure Key 临时失效或接口网络失败时，页面翻译不会立刻全部停止。

如果不希望调用实验性 Google Web 接口，可以关闭该选项。

## 自动生成安装 ZIP

`.github/workflows/build-browser-extension.yml` 会自动：

1. 校验 `manifest.json`。
2. 检查核心文件。
3. 对所有 JavaScript 执行 `node --check`。
4. 从 manifest 自动读取版本号。
5. 打包 `FloatingTranslator-Browser-v版本号.zip`。
6. 上传到 GitHub Actions Artifacts。

## 安装

### Chrome / Edge 桌面版

1. 下载 Actions 生成的 ZIP 并解压。
2. 打开扩展管理页面并开启开发者模式。
3. 选择“加载已解压的扩展程序”。
4. 选择包含 `manifest.json` 的目录。

### Quetta Android

使用 Actions 自动生成的 ZIP 测试。手机弹窗已按窄屏布局处理；具体导入入口以当前 Quetta 扩展管理页为准。

## 后续

- OCI / Cloudflare Worker 签名代理模板
- 长页面批处理进一步优化
- 混合语言页面逐段识别
- Telegram Web / WhatsApp Web / Discord 等专项规则
- PDF 翻译
- YouTube / 网页视频双语字幕
- 图片 OCR
- Android 与 Browser 共用规则、术语表和语言偏好
