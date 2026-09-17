# FloatingTranslator Browser v1.0 RC

v1.0 RC 是 Chromium 主版本的正式测试候选版。此阶段继续不展开 X浏览器 / UserScript / Firefox / Safari 兼容层，先集中验证 Quetta / Chrome / Edge 等 Chromium 环境。

## v1.0 RC 新增

### 实际翻译路径诊断

高级设置和 Popup 现在会记录最近一次翻译实际走的路径：

- Azure
- Google Web 直接翻译
- Azure 失败后的 Google 回退
- 纯缓存命中

同时记录耗时、时间、源/目标语言、文本数量和最近错误。诊断信息可一键复制，且不会包含 Azure Key。

### 设置备份 / 恢复

高级设置支持导出和导入 JSON：

- 全局翻译设置
- Azure Endpoint / Region（默认不导出 Key）
- 缓存设置
- 术语表
- 网站规则
- 页面区域排除
- 按网站保存的输入语言组合

Azure Key 默认不会进入备份文件。只有用户主动勾选“包含 Azure Key”时才会导出。

### 按网站记住输入翻译语言

聊天输入语言会按 hostname 保存。例如：

- `web.telegram.org`：中文 → 英语
- `web.whatsapp.com`：中文 → 日语
- 另一个网站：自动检测 → 中文

重新进入网站后会自动恢复该网站最近一次输入语言组合。

## v0.9 Complete 能力继续保留

- 术语表 / 固定翻译，最多 300 条
- 正文、属性、聊天输入、Shadow DOM、右键翻译共用术语表
- 页面区域点选排除
- `translate="no"` / `.notranslate` / `data-no-translate` 等原生排除标记
- 输入预览：仅复制译文、交换语言、本次输入框暂停预览
- 正文译文防覆盖
- 双语译文节点自动补回
- Open Shadow DOM 防覆盖
- `placeholder / title / aria-label / alt / button value` 属性防覆盖

## v0.8 Complete 能力继续保留

- 暂停 / 继续，保留已有译文和剩余队列
- 精确失败重试，只重试失败文本
- Azure / Google 今日与本月字符和请求统计
- IndexedDB 缓存统计、自动整理、手动整理与清空
- 默认最多 30,000 条、30 天缓存

## 主版本完整能力

- Manifest V3
- Microsoft / Azure Translator 主引擎
- Google Web 可选故障回退
- Azure 批量请求、文本去重、长文本拆段
- 408 / 429 / 5xx / 网络超时重试与指数退避
- Azure 连续失败短暂熔断
- Google 回退结果与 Azure 缓存隔离
- 长网页分片增量扫描
- 当前可视区域优先
- 动态帖子 / 评论 / 无限滚动持续监听
- SPA 页面切换补扫
- 页面重新获得焦点 / 重新可见时安全补扫
- Open Shadow DOM 持续翻译
- 混合语言页面自动补翻
- 纯汉字段不强行判中文或日文
- 英文不会因为页面标记为中文 / 日文就被误跳过
- 仅译文 / 原文 + 译文
- 始终翻译 / 永不翻译网站规则
- 选中文字右键翻译
- WhatsApp Web / Telegram Web / Discord 聊天区域优先
- 多输入框独立实时预览
- 一键替换 / 复制译文
- Alt + Enter 立即翻译输入框
- 补扫遗漏内容
- 引擎诊断、用量统计、缓存统计

## 自动构建与回归测试

GitHub Actions 会：

1. 校验 Manifest 与所有脚本路径。
2. 校验术语表、运行时遥测、网站输入语言、区域排除、正文、聊天和属性模块。
3. 对全部 JS / CJS 执行 `node --check`。
4. 运行语言识别、术语表与完整运行时回归测试。
5. 自动读取 Manifest 版本。
6. 生成 `FloatingTranslator-Browser-v1.0.0.zip`。
7. 上传 Actions Artifact。

## 测试范围

优先集中测试：

- Quetta Android
- Chrome Desktop
- Edge Desktop
- Brave / Vivaldi / Opera 等 Chromium 桌面浏览器

兼容版本等 v1.0 主线实际测试稳定后再做。