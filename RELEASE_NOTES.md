# 浮译 0.7.3-dev2｜Android 内置优先 + 免费在线备用

按当前使用偏好重新调整 ASR 自动优先级：

- 麦克风来源：Android 系统 SpeechRecognizer 第一优先。
- 自动模式下 Android SpeechRecognizer 不再强制离线，允许系统联网识别以优先质量；手动选择系统识别器时仍可勾选“强制请求离线”。
- Android 内置发生非临时错误后，再尝试 Groq Free / Cloudflare Workers AI Free。
- 免费在线不可用或达到免费限额后，再回退本地 Whisper / Qwen3 / SenseVoice / Vosk。
- 有道等付费/旧兼容语音识别只作为最后兜底，而且默认关闭，只有用户显式打开开关才会自动调用。
- 系统内部声音和 ROOT/Shizuku PCM 无法直接喂给 Android SpeechRecognizer，因此这两种声音来源会自动跳过 Android 内置，从免费在线开始，再到本地，最后才是显式开启的付费兜底。
- 修复免费在线失败后与本地模型之间可能重复回跳的问题；服务停止时同时关闭在线 ASR 请求队列。

保留 dev1 的 Groq Free Whisper Large V3、Groq Free Whisper Large V3 Turbo、Cloudflare Workers AI Free Whisper Large V3 Turbo 支持。

这是开发测试版，不替代 0.7.2.4 稳定版。
