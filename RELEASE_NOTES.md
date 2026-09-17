# 浮译 0.7.2 正式版

- 实时翻译、ROOT / Shizuku 通话翻译统一为“自动识别源语言 → 固定目标语言”，不再因识别到中文而错误反向成中文→日语等方向。
- 面对面对话保留自动双向：对方语言 → 我的语言；我的语言 → 最近识别到的对方语言。
- 面对面连续模式改成真正开放麦克风：同一方可连续说多句，不要求一问一答；上一句翻译/朗读时，下一句仍可继续录入并进入待处理队列。
- Whisper / Qwen3 / Omnilingual / SenseVoice 等本地多语言模型可持续监听并逐句重新判断语言；自动模式优先 Qwen3-ASR → Whisper Medium → Whisper Small → Omnilingual → SenseVoice。
- 修复 `How are you?` 等短英语在中英日混说场景下被错误贴成中文标签后带偏翻译方向的问题；对明显英语短句增加语言路由保护。
- 过滤 `<|endoftext|>`、`<|startoftranscript|>` 等 ASR 控制标记，纯控制标记结果不会进入翻译、历史和界面。
- 多语言 ASR 返回的语言码优先；无可靠语言码时再使用本地 ML Kit Language ID。
- 修复“土 / 東京”等短汉字在普通实时翻译里被错误反向的问题，短文本会结合备用源语言处理。
- 全局无障碍翻译在未配置 Azure 时也支持本地自动识别源语言，并动态选择 ML Kit 翻译方向。
- 保留 Telegram / 网页长消息去重、长文本显示、智能 OCR、增量翻译缓存、TTS 回声过滤和连续对话历史。
- Android 系统 SpeechRecognizer 仍按单句工作，但在连续模式里会自动重启下一轮，不要求双方轮流。
- API Key 继续按现有本地方式保存；本版不做 APK 瘦身。

## 建议配置

- 中英日混说、连续对话：优先 Qwen3-ASR 0.6B INT8。
- 综合多语言与稳定性：Whisper Medium INT8。
- 性能与准确率平衡：Whisper Small INT8。
- 纯日语可按需要使用 ReazonSpeech / Parakeet 日语模型。

这是 0.7.2 正式稳定版。GitHub Release 使用固定签名构建，并继续校验与既有版本的签名连续性。
