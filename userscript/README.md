# FloatingTranslator UserScript v0.6

这是给 **X浏览器 / Tampermonkey / Violentmonkey** 等用户脚本环境准备的轻量版，与 Chromium 扩展版并行维护。

## 主要功能

- 持续扫描网页 DOM 文本并自动翻译
- MutationObserver 监听动态加载内容
- 当前可视区域优先
- Microsoft / Azure Translator 主引擎
- Google Web 可选故障回退
- Azure 批量翻译，减少大量短请求
- 同一批次相同文本去重
- IndexedDB 缓存（按当前网站来源保存）
- 仅译文 / 双语显示
- 当前网站：默认 / 始终翻译 / 永不翻译
- 输入框停止输入后自动预翻译
- 输入译文一键替换或复制
- 不会自动发送聊天消息
- 页面右下角 `FT` 悬浮按钮打开设置
- 脚本管理器菜单可打开设置、翻译当前页、恢复原文

## 安装

### X浏览器

1. 在 X浏览器中打开 `FloatingTranslator.user.js` 的 Raw 地址。
2. 浏览器识别到 UserScript 后选择安装。
3. 打开普通网页，右下角会出现 `FT` 按钮。
4. 打开设置，填写 Azure Translator Key 和 Region。

### Tampermonkey / Violentmonkey

1. 先安装 Tampermonkey 或 Violentmonkey。
2. 打开 `FloatingTranslator.user.js` 的 Raw 地址。
3. 在脚本管理器安装页面确认安装。
4. 打开网页后使用右下角 `FT` 按钮。

## Azure 设置

UserScript 版默认调用：

`https://api.cognitive.microsofttranslator.com`

需要填写：

- Azure Translator Key
- Azure Region（如果你的资源要求）

Key 保存在用户脚本管理器自己的本地存储中，不会写入 GitHub。它不是端到端加密保险箱；不要在不可信浏览器或共享设备上保存云密钥。

## 与浏览器扩展版的区别

UserScript 版尽量保持普通网页翻译体验接近扩展版，但浏览器扩展仍然拥有更完整的后台能力，例如独立 Service Worker、扩展 popup/options、浏览器右键菜单、跨页面扩展存储以及更完整的 iframe/扩展权限控制。

UserScript 版的优势是安装范围更广，尤其适合不支持完整 Chrome 扩展但支持 UserScript 的移动浏览器。

## 已知限制

- Closed Shadow DOM 无法进入。
- 部分跨域 iframe 受浏览器/脚本管理器限制。
- 富文本 contenteditable 只对简单编辑框提供输入替换，避免破坏复杂编辑器结构。
- Google Web 是实验性备用接口，可能限流或变化。
- IndexedDB 缓存按当前网站来源隔离，不像扩展版那样天然拥有统一扩展来源缓存。

## 文件

- `FloatingTranslator.user.js`：可直接安装的 UserScript
- `README.md`：本说明
