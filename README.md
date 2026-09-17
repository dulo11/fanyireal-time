# 浮译 0.7.2-dev4 测试版

- 修复面对面连续对话中 Whisper / 多语言 ASR 偶发输出 `<|endoftext|>`、`<|startoftranscript|>` 等模型控制标记的问题：现在会在进入翻译、历史和界面之前统一过滤，纯控制标记结果直接丢弃。
- 针对“how are you 被识别成中文近音”这类短句语言漂移增加路由保护：当用户语言是中/日/韩，而短句里明显存在多个英文单词却被 ASR 标成用户语言时，不再直接把它判成“我说的中文/日语/韩语”。
- 面对面对话的“自动 ASR”优先级调整为 Qwen3-ASR → Whisper Medium → Whisper Small → Omnilingual → SenseVoice；已下载 Qwen3 时优先使用它处理日英中混说和短句切换。
- 用户手动指定 Whisper Small、Whisper Medium、Qwen3 等模型时仍严格使用手动选择，不会被自动替换。
- 连续开放麦克风、24 句待处理队列、TTS 不停麦、回声过滤、逐句自动语言判断继续保留。
- 普通实时 / ROOT / Shizuku / 全局翻译仍保持“自动识别源语言 → 固定目标语言”；只有面对面对话允许自动双向。
- API Key 存储方式不改，不做 APK 瘦身。

## 重点测试

1. 面对面 → 连续开放麦克风 → ASR 选择“自动”，已下载 Qwen3 的情况下应优先加载 Qwen3-ASR。
2. 连续说 `How are you?`、`Good morning`、日语短句、中文短句，观察每一句的 `[en] / [ja] / [zh]` 是否合理。
3. 重点复测 `OK OK, how are you?`，确认不再因为短句误标 `[zh]` 而被当成本方中文。
4. 长时间连续监听时，不应再在会话里出现 `<|endoftext|>`、`<|startoftranscript|>` 或其中文翻译“文本结束”。
5. 手动选择 Whisper Medium 时应继续使用 Whisper Medium，不因本版自动优先级而切换到 Qwen3。

说明：语言路由保护只能避免“错误语言标签继续带偏翻译方向”；如果某个 ASR 模型已经把英语声音本身识别成了错误的中文汉字，文本层无法凭空还原原始声音，所以 Auto 模式现在优先 Qwen3 来降低这类源头误识别。dev4 仍是测试构建，等这组混说场景验证稳定后再发布 0.7.2 正式版。
