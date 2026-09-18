# 浮译 0.7.3 正式版

本版整合 0.7.3-dev1 ～ dev4 的实时识别与字幕改进，并正式加入非 AI 智能拼句和“悬浮窗仅显示译文”。

## 实时 ASR

- 自动 ASR 优先使用 Android 系统 SpeechRecognizer。
- 麦克风来源直接调用系统识别器，并允许自动模式使用联网识别能力。
- Android 13+ 的系统内部声音、ROOT PCM、Shizuku PCM 会尝试通过 `RecognizerIntent.EXTRA_AUDIO_SOURCE` + `ParcelFileDescriptor` pipe 交给系统 RecognitionService。
- 厂商识别服务不支持外部 PCM 时，会自动退出该路线，不反复死循环。
- 系统识别失败后依次尝试已配置的免费在线 ASR、本地离线模型；付费/旧兼容语音兜底默认关闭并保持最后。
- 免费在线支持 Groq Free Whisper Large V3 / Large V3 Turbo，以及 Cloudflare Workers AI Free Whisper Large V3 Turbo。
- ROOT / Shizuku 通话内部声音仍先由 tinycap / ALSA 读取；Shizuku 是否能读取微信、Telegram、WhatsApp 等通话 PCM 取决于 ROM、shell 权限、SELinux 和音频 HAL。

## 非 AI 智能拼句

- 新增“智能拼句修正”，默认开启。
- 不接入任何 LLM，不需要 AI Token。
- ASR 一段结果出来后仍立即翻译，首屏字幕不额外等待。
- 对逗号、冒号、连接词结尾或疑似提前断句的片段保存短期上下文。
- 下一段在约 8 秒内到来时，可将两段合并为更完整的句子重新交给当前翻译引擎，并用修正译文覆盖临时译文。
- 流式/临时结果不重复写入历史；最终完整句再保存，减少历史重复。

## 悬浮字幕

- 新增“悬浮窗仅显示译文”开关。
- 开启后隐藏原文、声音/ASR/翻译诊断、暂停/关闭按钮和独立 OCR 文本。
- 唯一字幕区域只显示译文本身，不显示“译文：”前缀。
- OCR 启用时也复用同一个译文区域。
- 仅译文模式下，状态和错误提示不会覆盖正在显示的字幕；停止服务可回 App 操作。

## 模型下载与现有功能

- 保留大模型下载缓存修复：完整下载包只有在解压、ONNX 检查、目录切换和 `.ready` 写入全部成功后才删除；安装失败时保留完整包，可直接重试。
- 保留自动识别源语言 → 固定目标语言、面对面自动双向、连续开放麦克风、全局无障碍翻译、智能 OCR、历史/TXT/SRT 等功能。
- API Key 继续按现有本地方式保存。
- 本版不做 APK 瘦身。

这是 v0.7.3 正式稳定版，继续使用既有固定签名，可覆盖安装此前正式版和 0.7.3-dev 测试版。
