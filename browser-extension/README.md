# FloatingTranslator Browser v0.5

浏览器持续网页翻译版。v0.5 起收敛翻译引擎路线：**Microsoft / Azure Translator 作为主引擎，Google Web 作为可选故障回退**，不再继续维护 Oracle OCI 翻译链路。

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
- Microsoft / Azure Translator 主引擎
- Google Web 实验性备用引擎
- Azure 失败时可选自动回退 Google Web
- API Key 只保存在当前浏览器本地扩展存储，不写入仓库
- GitHub Actions 自动校验并打包浏览器插件 ZIP

## v0.5 Azure 优化

相比 v0.4，v0.5 已移除 OCI 设置、OCI Worker 构建和相关代理代码，并对 Azure 调用做了专项优化。

### Azure 批量翻译

网页扫描出来的多个文本节点不再全部逐条请求 Azure。后台会先检查本地缓存，再把未命中的文本按保守阈值合成批量请求：

```text
网页文本节点
   ↓
IndexedDB 缓存命中检查
   ↓
未命中内容合批
   ↓
Azure Translator
   ↓
按原顺序写回网页
```

这样可以减少大量短句造成的网络请求次数，同时保留每段文本独立缓存。

### 升级迁移

如果浏览器之前安装过带 OCI 测试配置的版本，v0.5 会：

- 将旧的 `oci-proxy` 主引擎自动迁回 `azure`
- 删除本机旧 `ociProxyEndpoint`
- 删除本机旧 `ociProxyToken`

不会把旧 OCI 配置继续带到后续版本。

## Azure 设置

打开“翻译引擎与高级设置”，填写：

- Endpoint
- Region（按你的 Azure Translator 资源要求填写）
- Key

Key 使用 `chrome.storage.local` 仅保存在当前浏览器扩展本地数据中，不会提交到 GitHub。

如果开启“Azure 失败时自动回退到 Google Web”，遇到 Azure Key、网络或接口异常时，当前批次会尝试继续翻译；如果不想使用非正式 Google Web 接口，可以关闭该选项。

## 自动生成安装包

`.github/workflows/build-browser-extension.yml` 会自动：

1. 校验 `manifest.json`。
2. 检查浏览器插件核心文件。
3. 对全部浏览器 JavaScript 执行 `node --check`。
4. 自动读取版本号。
5. 生成 `FloatingTranslator-Browser-v0.5.0.zip`。
6. 上传到 GitHub Actions Artifacts。

## 安装

### Chrome / Edge 桌面版

1. 下载 Actions 生成的 ZIP 并解压。
2. 打开扩展管理页面并开启开发者模式。
3. 选择“加载已解压的扩展程序”。
4. 选择包含 `manifest.json` 的目录。

### Quetta Android

使用 Actions 自动生成的浏览器插件 ZIP 测试。手机弹窗已按窄屏布局处理；具体扩展导入入口以当前 Quetta 版本为准。

## 下一步

- 长页面调度、取消和并发优化
- 混合语言页面逐段识别
- Telegram Web / WhatsApp Web / Discord 专项适配
- Azure 请求失败重试与节流
- PDF 翻译
- YouTube / 网页视频双语字幕
- 图片 OCR
- Android 与 Browser 共用规则、术语表和语言偏好
