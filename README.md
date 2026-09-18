# 浮译 0.7.3 正式版

0.7.3 重点优化实时语音识别、非 AI 智能拼句、悬浮字幕和 ROOT / Shizuku 通话内部声音。

- 自动 ASR 优先尝试 Android 系统 SpeechRecognizer；麦克风直接使用系统识别，Android 13+ 的系统内部 PCM / ROOT / Shizuku PCM 会尝试通过外部音频源交给系统识别器。
- 系统识别不可用或不兼容时，可继续使用 Groq Free / Cloudflare Workers AI Free，再回退本地 Whisper / Qwen3 / SenseVoice / Vosk；付费/旧兼容语音兜底默认关闭并放在最后。
- 新增非 AI“智能拼句修正”：首段译文仍立即显示；明显被 ASR 提前切断的句子会保留短期上下文，后续片段到来后自动合并重译并覆盖临时字幕。
- 智能拼句不调用 LLM，不需要 AI Token。
- 新增“悬浮窗仅显示译文”：可隐藏原文、声音/ASR/翻译诊断、暂停/关闭按钮和独立 OCR 文本，仅保留译文本身。
- ROOT / Shizuku 通话兼容中心继续支持按 App 保存 ALSA card/device/采样率/声道并测试内部 PCM。
- 修复大模型下载完成后解压失败导致完整下载包被删除的问题；失败时保留完整缓存，可直接重新安装。
- 保留自动识别源语言 → 固定目标语言、连续面对面对话、全局翻译、智能 OCR、历史/TXT/SRT 等功能。
- API Key 继续按现有本地方式保存；不做 APK 瘦身。

## 推荐使用

普通视频/直播优先使用“自动推荐”ASR；如果设备系统识别对外部 PCM 兼容良好，会优先走 Android 系统识别。  
微信/Telegram/WhatsApp 等通话可先在“ROOT / Shizuku 通话内部声音兼容中心”测试 PCM，读到明显内部声音后再使用自动 ASR。  
想要最干净的字幕界面，可开启“悬浮窗仅显示译文”。

详细变更见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。
