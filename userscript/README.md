# 浮译 Lite 油猴版 v0.1.0（初期测试）

浏览器插件版继续在 `browser-extension/` 维护（1.3.9）；油猴版在 `userscript/Fuyi-Lite.user.js` 独立安装，**不是**将 CRX 改名为 user.js。

## 手机安装（Quetta / Titanium）

1. 在浏览器中安装 **Tampermonkey** 或 **Violentmonkey**；只有浏览器支持运行该管理器时才能使用。
2. 在油猴管理器中选择“安装本地脚本”或打开 [原始脚本](https://raw.githubusercontent.com/dulo11/fanyireal-time/feature/browser-translator-v1.3-signed-update/userscript/Fuyi-Lite.user.js) 后安装。
3. **暂时关闭原浮译 CRX/ZIP 扩展**，防止两套翻译器同时改写网页。
4. 新开标签页访问普通网页，等待加载后约 1 秒；点击「译」查看设置。悬浮球可拖动，拖动松开可自动贴边。
5. 如果关闭了悬浮球，请通过油猴管理器的「脚本菜单 → 浮译 Lite：显示/隐藏悬浮球」恢复。电脑还可用 Alt+Shift+T。

## 本阶段已经实现

- 独立油猴运行环境（不依赖 Chrome extension Service Worker）；
- Google Web 非正式免费接口；跨域使用 `GM_xmlhttpRequest`；
- 自动识别网页文字、仅译文/原文+译文两种显示；
- 页面 `load` 后延迟启动，最久等待 9 秒以兼容持续加载网站；
- **视区优先** 的 IntersectionObserver 观察；动态网页 MutationObserver；
- 每页最多 180 次网络请求、单个网络请求串行、限流冷却及 400 条内存缓存；
- 跳过链接、导航、代码、输入框、可编辑和 `translate=no` 区域；
- 悬浮球自由拖动、贴边、大小和透明度调整、隐藏后从油猴菜单恢复；
- 按站点禁止翻译、暂停/恢复原文。

## 当前没有实现（不能视为已经支持）

- Azure/百度/阿里云多 Key 池与 API 兼容迁移；
- AI 上下文翻译、流式 AI 输出、提示词自定义；
- PDF、EPUB、视频字幕翻译；
- 与原浏览器插件之间的设置/缓存自动同步；
- 按输入框空格快捷翻译、选中文本悬浮翻译；
- 完整运行时多浏览器实机测试。

这些属于下一阶段；和浏览器插件版相比，油猴版优先保证**网页加载和点击交互不被阻塞**。

## 隐私与稳定性

默认将待翻译的网页正文提交给 Google Web 第三方服务；不上传输入框内容。该接口**不是正式 API**，可能存在限流、地区不可用、接口变更等问题。某些动态框架可能把已翻译文字重绘为原文，本版不强行阻止，以避免无限 DOM 修改循环。

目前 GitHub Actions 的构建测试只是语法/契约测试，不等于已在 Quetta 或 Titanium 真机验证成功。建议先测试公开网页，不要在银行、医疗或包含秘密信息的网页启用。
