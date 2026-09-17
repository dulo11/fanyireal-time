# FloatingTranslator v1.1 跨浏览器兼容层

当前主线仍以 Chromium 完整版为核心，兼容层只改变浏览器运行环境和打包方式，不复制第二套翻译逻辑。

## Chromium 通用包

同一个 Chromium ZIP 继续面向：

- Google Chrome
- Microsoft Edge
- Brave
- Vivaldi
- Opera
- Quetta Android
- 其他支持 Manifest V3 Chrome 扩展的 Chromium 浏览器

核心后台仍使用 `background.service_worker`。

## Firefox 包

Firefox 使用单独 Manifest：`compat/firefox/manifest.json`。

主要差异：

- Firefox Manifest V3 当前使用 `background.scripts`，不依赖 Chromium 的 `background.service_worker`。
- 后台脚本按与 Chromium `background/main.js` 相同的顺序直接加载。
- `compat/browser-api.js` 在 Firefox 中优先使用标准 `browser.*` Promise API，并让现有 `chrome.*` 调用复用同一套核心逻辑。
- 内容脚本、Popup、Options 页面都先加载兼容层，再加载现有功能代码。
- Firefox 包包含固定 Gecko ID：`floatingtranslator@966405.xyz`。
- 当前最低 Firefox 版本设为 121，以避开更老版本的 Manifest V3 后台兼容差异。

## X浏览器 / UserScript

X浏览器不是完整 WebExtensions 运行环境，因此不能只换 Manifest。后续兼容模块会把稳定核心抽成 UserScript 可复用部分，重点保留：正文持续翻译、动态内容、输入翻译、Azure / Google Web 引擎和基础缓存；浏览器后台、右键菜单、部分权限能力会采用 UserScript API 替代。

## Safari

Safari Web Extension 需要额外的 Safari/Xcode 打包流程，不与当前 ZIP 直接通用。等 Firefox 与 UserScript 兼容层稳定后再处理。
