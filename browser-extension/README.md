# FloatingTranslator Browser v0.1

浏览器持续网页翻译版。目标是像 Chrome / Edge 自带网页翻译一样：页面加载多少内容，就持续翻译多少内容；动态加载的新帖子、评论和聊天消息也会自动加入翻译队列。

## 当前功能

- Manifest V3
- 自动持续翻译网页 DOM 文本
- MutationObserver 监听动态新增/变化内容
- 当前可视区域优先处理
- 仅译文 / 原文+译文
- 始终翻译此网站 / 永不翻译此网站
- 翻译缓存（IndexedDB）
- 选中文字右键翻译
- 输入框 `Alt + Enter` 翻译
- Google Web 实验性引擎（无需 Key，可能限流/失效）
- Microsoft / Azure Translator 官方接口配置
- API Key 只保存到浏览器本地扩展存储，不写入仓库
- 手机浏览器友好的弹窗布局
- GitHub Actions 自动校验并打包可安装 ZIP

## 自动生成安装 ZIP

仓库已经加入 `.github/workflows/build-browser-extension.yml`。

当浏览器插件代码更新时，GitHub Actions 会自动：

1. 校验 `manifest.json` 是否为合法 JSON。
2. 检查 service worker、content script、popup 和 options 等核心文件是否存在。
3. 从 `manifest.json` 自动读取版本号。
4. 将 `browser-extension` 目录内容打包，并保证 `manifest.json` 位于 ZIP 根目录。
5. 生成名为 `FloatingTranslator-Browser-v版本号` 的 Actions Artifact。

例如当前版本会生成：

`FloatingTranslator-Browser-v0.1.0`

进入 GitHub 仓库的 **Actions → Build Browser Extension → 对应运行记录 → Artifacts** 即可下载。

## 手动安装

### Chrome / Edge 桌面版

1. 下载 Actions 生成的 ZIP 并解压。
2. 打开浏览器扩展管理页面并开启“开发者模式”。
3. 选择“加载已解压的扩展程序”。
4. 选择解压后、包含 `manifest.json` 的目录。
5. 打开任意普通 `http/https` 网页，点击 FloatingTranslator 图标。

也可以直接克隆仓库，然后加载 `browser-extension` 文件夹。

### Quetta Android

优先使用 Actions 自动生成的 `FloatingTranslator-Browser-v版本号` ZIP 进行测试。若当前 Quetta 版本要求导入解压目录或通过其扩展管理页安装，则按浏览器界面提示操作。手机端弹窗已按窄屏布局处理。

## 使用

- 总开关开启后，默认自动检测页面语言并持续翻译到简体中文。
- 当前网站可以单独设置“始终翻译”或“永不翻译”。
- “仅译文”会直接显示译文；“原文 + 译文”会保留原文并在旁边/下方插入译文。
- 网页后续动态加载的新 DOM 文本会自动继续翻译。
- 在普通输入框/文本框里输入内容后按 `Alt + Enter`，会翻译成当前目标语言并替换输入内容。
- 选中网页文字后使用右键菜单“FloatingTranslator：翻译选中文字”可快速翻译。

## 翻译引擎

### Google Web（实验性）

默认用于零配置测试。它不是正式稳定 API，因此可能受限流、网络环境或接口变化影响，不适合承诺长期稳定服务。

### Microsoft / Azure Translator

在“翻译引擎与高级设置”中填写 Endpoint、Region 和 Key。Key 通过 `chrome.storage.local` 仅保存到当前浏览器扩展数据，不提交到 GitHub。

## v0.2 计划

- 网页聊天消息流专项适配
- 输入框实时预翻译 / 一键替换并发送
- 更强的自动语言检测与短句容错
- Shadow DOM 深度扫描
- iframe 策略优化
- PDF 翻译
- YouTube / 网页视频双语字幕
- 图片 OCR 翻译
- 与 Android FloatingTranslator 共用网站规则、术语表和语言偏好
