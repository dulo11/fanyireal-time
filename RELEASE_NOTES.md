# 浮译 0.7.3-dev3｜Android 系统识别直接吃内部 PCM

这一版开始实装前面讨论的 Android 13+ `RecognizerIntent.EXTRA_AUDIO_SOURCE` 路线，不再把“系统 SpeechRecognizer 只能用麦克风”当成固定限制。

- 新增 `AndroidPcmSpeechEngine`：
  - 接收浮译已经抓到的 16 kHz 单声道 PCM。
  - 通过 `ParcelFileDescriptor` pipe + `EXTRA_AUDIO_SOURCE` 直接交给系统安装的 RecognitionService。
  - 同时声明采样率 16000、1 声道、PCM16。
  - 自动按停顿切句，连续讲话最长约 4.8 秒切段；上一段识别时继续缓存后续声音。
- 普通“系统内部声音｜直播/视频”自动 ASR 顺序改为：
  Android 系统外部 PCM → Groq Free / Cloudflare Free → 本地 Whisper/Qwen/SenseVoice/Vosk → 显式开启的付费兜底。
- ROOT / Shizuku 通话 PCM 也使用同一条优先级：
  tinycap/ALSA 读到 PCM → Android 系统外部 PCM → 免费在线 → 本地 → 付费最后。
- 手动选择“Android 系统 SpeechRecognizer”时，系统内部声音以及 ROOT/Shizuku PCM 不再直接拦截；会实际尝试外部 PCM 注入。
- 厂商 RecognitionService 如果不支持外部音频源，连续失败后自动退出这条路，不会反复死循环。
- Shizuku 通话兼容页增加说明：微信/Telegram/WhatsApp 是否能读到内部 PCM 仍由 ROM、shell 权限、SELinux 和音频 HAL 决定；只要 Shizuku 测试读到明显 PCM，就可继续尝试系统内置识别。
- 麦克风场景继续直接使用 Android SpeechRecognizer 自己的麦克风链路，不绕 PCM 注入。

注意：Android 官方文档说明，如果识别器实现不支持 `EXTRA_AUDIO_SOURCE`，它可能忽略该参数并自行打开麦克风。因此这版仍属于真机兼容测试版，需要在一加 Ace 6 / ColorOS 上确认系统 RecognitionService 的实际行为。

稳定版仍保持 0.7.2.4；本版为开发测试版。
