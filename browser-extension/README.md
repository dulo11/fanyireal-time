# FloatingTranslator Browser v0.7 Complete

v0.7 开始先集中做 Chromium 主版本，不继续扩展 X浏览器 / UserScript / Firefox / Safari 兼容层。目标是先把真正影响日常使用的网页翻译体验做完整、稳定，再进行跨浏览器适配。

## 完整版核心能力

- Manifest V3
- Microsoft / Azure Translator 主引擎
- Google Web 可选故障回退
- 整页持续 DOM 翻译
- 超长页面增量扫描，不再只处理最前面的少量文本
- 当前可视区域优先
- 动态帖子 / 评论 / 聊天消息持续监听
- Open Shadow DOM 扫描与持续监听
- SPA 页面切换后继续工作
- 仅译文 / 原文 + 译文
- 始终翻译 / 永不翻译网站规则
- 选中文字右键翻译
- 输入框 Alt + Enter 立即翻译
- 聊天输入停止后实时预览译文
- 输入译文一键替换 / 复制，不自动发送
- IndexedDB 翻译缓存
- 同批文本自动去重
- Azure 批量请求
- Azure 超长文本拆段后自动合并
- 429 / 5xx / 网络超时自动重试与指数退避
- Azure 连续失败短暂熔断，避免整页持续卡死
- Azure 失败时回退 Google Web 不再污染 Azure 缓存
- 可在高级设置中测试引擎、调整超时/重试、清空缓存

## v0.7 混合语言识别

旧版曾经把“只要出现汉字”直接判断为中文，这会造成日文汉字、英语 + 日语混合页面等场景误判。

v0.7 新增 `shared/language-core.js`：

- 平假名 / 片假名明确识别为日语
- 韩文、泰文、阿拉伯文、西里尔文、印地文单独识别
- 越南语重音字符单独处理
- 纯汉字段标记为“汉字歧义”，不再强制判断为中文或日文
- 英文不会因为页面里其他日文内容被直接识别成日语
- 手动选择源语言时，手动设置优先于自动判断
- 可开启“自动跳过已经是目标语言的文本”

仓库内有回归测试，专门防止“英语被当成日语”和“纯汉字强制中文”的问题重新出现。

## 长网页与动态网页

正文扫描改为分片增量执行，避免一次 TreeWalker 扫完整个超长页面造成卡顿。文本会进入优先队列：

```text
当前可视区域
    ↓
附近内容
    ↓
页面更远位置
```

每批同时限制节点数量和字符总量。无限滚动、新增评论、聊天新消息继续由 MutationObserver 加入队列。

## Azure 稳定性

后台会先查缓存并去重，再执行 Azure 批量翻译。长文本会自动拆段。对于 408 / 429 / 5xx 等临时错误，会按退避策略重试。

如果 Azure 连续失败多次，会短暂进入熔断状态，避免长网页不断重复打失败请求。启用 Google Web 回退时，熔断期间继续尝试备用引擎。

Google 回退结果只写入 Google 缓存，不会伪装成 Azure 结果写进 Azure 缓存，因此 Azure 恢复后可以重新正常调用。

## 网页聊天

聊天正文和聊天输入分开处理：

- 网页中的对方消息持续进入正文翻译队列
- 你可以连续输入，不需要“一人一句”交替
- 停止输入约 0.55 秒后刷新译文预览
- 只有点击“替换输入框”才会修改输入内容
- 不自动点击发送
- Alt + Enter 使用“我输入的语言 → 我发送为”立即替换
- Clipboard API 不可用时提供传统复制回退
- 对 contenteditable 尽量使用浏览器原生插入文本事件，提升 React / Vue / 聊天网页兼容性

## 高级设置

可设置：

- Azure Endpoint
- Azure Region
- Azure Key
- Azure 请求超时时间
- 自动重试次数
- Azure 失败是否回退 Google Web
- 测试翻译引擎
- 清空翻译缓存

Key 只保存在 `chrome.storage.local`，不会提交到 GitHub。

## 自动构建

GitHub Actions 会执行：

1. 校验 manifest。
2. 校验核心文件。
3. 对全部 JS / CJS 执行 `node --check`。
4. 执行混合语言回归测试。
5. 自动读取 manifest 版本号。
6. 生成 `FloatingTranslator-Browser-v0.7.0.zip`。
7. 上传 Actions Artifact。

## 当前测试范围

先测试 Chromium 主版本：

- Quetta Android
- Chrome Desktop
- Edge Desktop
- Brave / Vivaldi / Opera 等 Chromium 桌面浏览器

X浏览器 UserScript、Firefox、Safari 等兼容版本暂缓。等主版本功能和稳定性测试通过后，再基于稳定核心做适配，避免多个版本同时修同一批 bug。

## 后续主版本功能

主版本测试通过前，优先继续处理实际测试发现的问题。兼容层之后再做。
