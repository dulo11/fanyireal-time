# FloatingTranslator Browser v0.9 Complete

v0.9 继续只做 Chromium 主版本，不展开 X浏览器 / UserScript / Firefox / Safari 兼容层。目标是先把主版本的日常使用能力做完整，再统一进行 Quetta / Chrome / Edge 实测。

## v0.9 新增

### 1. 术语表 / 固定翻译

高级设置新增术语表，每行使用：

```text
原文 => 固定译文
```

例如：

```text
OpenAI => OpenAI
ChatGPT => ChatGPT
Cloudflare Workers => Cloudflare Workers
```

支持：

- 最多 300 条
- 可选择是否区分英文大小写
- 人名、品牌、产品名固定不翻
- 专业术语固定成指定译文
- 术语之外的文字继续交给 Azure / Google
- 正文、属性文字、聊天输入、Shadow DOM、右键翻译共用同一套术语表

术语固定片段不会单独发给翻译服务；高级设置会检查格式错误行。

### 2. 页面区域排除

Popup 新增“选择不翻译区域”。点击后回到网页，直接点想排除的区域即可。

适合：

- 代码区
- 在线编辑器
- 用户名 / ID
- 金额、订单信息附近的特殊控件
- 某些翻译后会破坏功能的网页组件

排除规则按网站保存，并支持一键清除此网站全部排除。页面原本带有 `translate="no"`、`.notranslate`、`data-no-translate` 等标记时也会自动尊重。

### 3. 输入翻译增强

聊天输入预览新增：

- 仅复制译文，不修改输入框
- 交换输入语言 / 发送语言
- 当前输入框临时暂停实时预览
- 多输入框仍保持独立防抖和请求序号
- 不自动点击发送

当源语言为“自动检测”时，交换语言按钮会要求先指定具体源语言，避免把 `auto` 当成目标语言。

### 4. 译文防覆盖

某些 React / Vue / 聊天网页会周期性重绘 DOM，把扩展写入的译文重新覆盖成原文。v0.9 会记住已经完成的翻译：

- 网页把正文恢复成原文时，直接恢复已有译文
- 不重新调用 Azure / Google
- 双语模式的译文节点被移除时会重新补上
- Open Shadow DOM 同样保护
- `placeholder / title / aria-label / alt / 按钮 value` 等属性被恢复成原文时也会直接恢复译文
- Popup 会显示正文“防覆盖恢复”次数，便于判断网站是否频繁重绘

如果网页把文字真正改成了新的内容，则不会强行覆盖新内容，而是按新文本重新进入翻译流程。

## v0.8 能力继续保留

- `placeholder / title / aria-label / alt / 按钮 value` 属性翻译
- 暂停 / 继续，保留已有译文和剩余队列
- 精确失败重试，只重试失败文本
- Azure / Google Web 今日与本月字符、请求统计
- 本地缓存命中统计
- IndexedDB 默认 30,000 条 / 30 天自动清理
- 手动整理 / 清空缓存

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
- “补扫遗漏内容”不恢复已经翻好的正文
- 引擎诊断、用量统计、缓存统计

## 自动构建与回归测试

GitHub Actions 会：

1. 校验 Manifest 和所有声明的脚本路径。
2. 校验术语表核心、术语运行时、区域排除、正文、聊天、属性翻译等模块。
3. 对全部 JS / CJS 执行 `node --check`。
4. 运行语言识别、术语表和完整运行时回归测试。
5. 自动读取 Manifest 版本。
6. 生成 `FloatingTranslator-Browser-v0.9.0.zip`。
7. 上传 Actions Artifact。

## 当前测试范围

主版本稳定前只测试 Chromium：

- Quetta Android
- Chrome Desktop
- Edge Desktop
- Brave / Vivaldi / Opera 等 Chromium 桌面浏览器

X浏览器 UserScript、Firefox、Safari 等兼容版本继续暂缓，等 Chromium 完全版集中测试稳定后再做。