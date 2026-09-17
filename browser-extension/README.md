# FloatingTranslator Browser v0.6

浏览器持续网页翻译版。当前路线继续以 **Microsoft / Azure Translator 为主引擎，Google Web 为可选故障回退**。v0.6 新增了并行维护的 **UserScript 版**，用于 X浏览器、Tampermonkey、Violentmonkey 等不能完整安装 Chromium 扩展的环境。

## Chromium 扩展版当前功能

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
- Azure 批量翻译
- API Key 只保存在当前浏览器本地扩展存储，不写入仓库

## v0.6 新增 UserScript 版

仓库现在新增：

```text
userscript/
├── FloatingTranslator.user.js
└── README.md
```

UserScript 版面向：

- X浏览器
- Tampermonkey
- Violentmonkey
- 其他支持 GM/Tampermonkey 风格用户脚本的浏览器

它实现了普通网页持续翻译、动态 DOM 监听、Azure 批量翻译、Google Web 故障回退、双语显示、站点规则、输入框实时预翻译、一键替换/复制，以及右下角 FT 设置面板。

UserScript 版不是完整 Chromium 扩展，因此 Service Worker、浏览器右键菜单、统一扩展来源缓存、部分 iframe/扩展权限能力仍以 Chromium 扩展版更完整。

## Azure 设置

Chromium 扩展版打开“翻译引擎与高级设置”，填写：

- Endpoint
- Region（按你的 Azure Translator 资源要求填写）
- Key

Key 使用 `chrome.storage.local` 仅保存在当前浏览器扩展本地数据中，不会提交到 GitHub。

UserScript 版通过右下角 `FT` 面板填写 Azure Key / Region；Key 保存在脚本管理器本地存储中。

## 自动生成安装包

`.github/workflows/build-browser-extension.yml` 现在会自动：

1. 校验 `manifest.json`。
2. 检查浏览器插件核心文件。
3. 对全部 Chromium 扩展 JavaScript 执行 `node --check`。
4. 校验 `FloatingTranslator.user.js` JavaScript 语法和关键 UserScript metadata。
5. 自动读取版本号。
6. 生成 `FloatingTranslator-Browser-v0.6.0.zip`。
7. 生成 `FloatingTranslator-UserScript-v0.6.0.zip`。
8. 同时保留可直接安装的 `FloatingTranslator-v0.6.0.user.js`。

## 安装

### Chrome / Edge / Brave / Vivaldi / Opera 桌面版

1. 下载 Browser ZIP 并解压。
2. 打开扩展管理页面并开启开发者模式。
3. 选择“加载已解压的扩展程序”。
4. 选择包含 `manifest.json` 的目录。

### Quetta Android

使用 Browser ZIP 测试；具体 ZIP / 解压目录导入方式以当前 Quetta 版本扩展管理页为准。

### X浏览器 / Tampermonkey / Violentmonkey

优先安装 `userscript/FloatingTranslator.user.js`。打开 Raw 文件地址后，由浏览器或脚本管理器识别并进入安装页面。

更详细的 UserScript 说明见 `userscript/README.md`。

## 下一步

- 长页面调度、取消和并发优化
- 混合语言页面逐段识别
- Telegram Web / WhatsApp Web / Discord 专项适配
- Azure 请求失败重试与节流
- UserScript 对更多移动浏览器兼容测试
- PDF 翻译
- YouTube / 网页视频双语字幕
- 图片 OCR
- Android / Browser / UserScript 共用规则和语言偏好
