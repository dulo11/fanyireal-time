# FloatingTranslator Browser v0.8 Complete

v0.8 继续只做 Chromium 主版本，暂不展开 X浏览器 / UserScript / Firefox / Safari 兼容层。目标是先把长网页、动态网页和日常使用稳定性做完整，再统一测试。

## v0.8 新增

### 1. 属性文字翻译

除了普通 DOM 文本节点，现在还会翻译：

- `placeholder`
- `title`
- `aria-label`
- 图片 `alt`
- `input[type=button|submit|reset]` 的 `value`

属性翻译同样使用语言判断、缓存、批处理和精确失败重试，不会把内部 bookkeeping 写进网页 DOM。

### 2. 暂停 / 继续

Popup 新增“暂停翻译 / 继续翻译”。

暂停时：

- 已经翻译好的正文保留
- 已经翻译好的属性文字保留
- 当前剩余队列保留
- Open Shadow DOM 的自动翻译暂停
- 不恢复整页原文

继续时会从剩余内容继续跑，并补扫暂停期间新增的网页内容。

### 3. 精确失败重试

正文、属性文字和 Open Shadow DOM 都改用详细翻译结果：

- 成功项立即应用
- 失败项单独进入重试
- 对可隔离的 400 / 413 / 422 类请求错误，会把批次拆小定位具体坏文本
- 不再因为一条坏文本，把已经成功的整批重新请求
- 网络故障、限流等全局错误仍按原来的退避和自动续跑策略处理

### 4. Azure / Google 用量统计

高级设置会显示：

- 今日 Azure 成功请求次数与字符数
- 本月 Azure 成功请求次数与字符数
- 今日 / 本月 Google Web 成功请求次数与字符数
- 今日 / 本月缓存命中条数

这里只统计扩展本机实际成功发出的请求，用于估算和排查，不替代 Azure / Google 官方账单。

### 5. 缓存自动清理

IndexedDB 缓存现在保存时间戳，并支持：

- 默认最多 30,000 条
- 默认保留 30 天
- 最大条数可在高级设置修改
- 保留天数可修改，`0` 表示不按日期过期
- 翻译过程中定期自动整理
- 手动“立即整理缓存”
- 一键清空全部缓存

旧版字符串缓存仍可读取；当缓存超过上限时，旧缓存会优先被淘汰。

## 已保留的完整能力

- Manifest V3
- Microsoft / Azure Translator 主引擎
- Google Web 可选故障回退
- Azure 批量请求、去重、长文本拆段
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
- 多输入框独立防抖，不互相抢
- 输入停止后实时翻译预览
- 一键替换 / 复制译文，不自动发送
- Alt + Enter 立即翻译输入框
- “补扫遗漏内容”不恢复已经翻好的正文
- 引擎诊断、缓存统计、清空缓存

## 自动构建

GitHub Actions 会：

1. 校验 Manifest 和所有声明的脚本路径。
2. 校验后台、正文、聊天、混合语言、属性翻译模块。
3. 对全部 JS / CJS 执行 `node --check`。
4. 运行语言与完整运行时回归测试。
5. 自动读取 Manifest 版本。
6. 生成 `FloatingTranslator-Browser-v0.8.0.zip`。
7. 上传 Actions Artifact。

## 当前测试范围

主版本稳定前只测试 Chromium：

- Quetta Android
- Chrome Desktop
- Edge Desktop
- Brave / Vivaldi / Opera 等 Chromium 桌面浏览器

兼容版本等主线测试稳定后再做。