# 浮译 0.7.3-dev1｜免费在线 ASR 优先测试版

本版针对实时视频中“本地 Qwen/Whisper 识别延迟高、英文误识别导致后续翻译全错”的问题，引入免费在线 ASR，并把自动识别策略改成在线优先。

- 新增 Groq Free Speech-to-Text：
  - Whisper Large V3（自动模式默认，准确率优先）
  - Whisper Large V3 Turbo（可手动选择，低延迟）
  - 支持系统内部声音、麦克风和 ROOT/Shizuku PCM。
- 新增 Cloudflare Workers AI Free：
  - @cf/openai/whisper-large-v3-turbo
  - 直接使用 Account ID + Workers AI API Token，不需要额外服务器。
- 自动 ASR 新顺序：已配置免费在线 → 本地高精度模型 → Vosk → 系统识别（麦克风可用时）。
- 免费在线自动模式：Groq Free 优先；遇到 429/网络失败连续两次后尝试 Cloudflare；两条免费在线线路都不可用时回退本地。
- 自动流程不再使用有道等付费语音 API；有道仅保留旧兼容手动选项。
- 在线 PCM 使用停顿切句 + 最长约 4.2 秒强制切句，识别请求和后续录音并行，减少本地 0.6B 模型整句解码造成的等待。
- 在线返回语言码时直接交给自动语言路由；没有语言码时继续由本机 ML Kit Language ID 判断。
- API 配置页新增 Groq Free Key、Cloudflare Account ID、Cloudflare Workers AI Token，仍只保存在本机。

这是开发测试版，不替代 0.7.2.4 稳定版。重点测试：英语视频连续对白、日英混说、内部声音实时字幕，以及免费额度到限后的自动回退。
