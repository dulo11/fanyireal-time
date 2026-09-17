# 浮译 0.7.2 正式版

0.7.2 重点完成自动语言、连续面对面对话和全局翻译完整度修复。

- 普通实时 / ROOT / Shizuku / 全局翻译：自动识别源语言 → 固定目标语言。
- 面对面对话：真正连续开放麦克风，同一方可以连续说多句，不要求你一句我一句。
- 本地多语言 ASR 可逐句自动判断语言；自动模式优先 Qwen3-ASR → Whisper Medium → Whisper Small → Omnilingual → SenseVoice。
- 修复 `How are you?` 一类短英语被错误标成中文后带偏翻译方向的问题。
- 过滤 `<|endoftext|>`、`<|startoftranscript|>` 等 ASR 控制标记。
- 保留 24 句连续待处理队列、TTS 不停麦与回声过滤。
- 全局翻译支持本地 ML Kit 自动识别源语言，并保留 Telegram / 网页长消息、智能 OCR 和增量翻译优化。
- API Key 继续按现有本地方式保存；不做 APK 瘦身。

推荐：中英日混说优先 Qwen3-ASR 0.6B INT8；综合多语言可用 Whisper Medium INT8；性能与准确率平衡可用 Whisper Small INT8。

## 0.7.2.3 连续对话语言修复

新增自动双向、限定双方语言双向、固定输入三种模式。英语短句易误识别时可固定输入为英语、我的语言为中文。详细支持范围和测试限制见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。
