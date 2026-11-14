# 浮译：权限、音频采集与模型来源说明

> 适用范围：浮译 Android 应用源码及 v0.7.6-dev2 测试分支。
> 这是一份**基于功能与代码的公开说明**，不等于独立安全审计。用户应按自己的使用场景开启权限、确认网络连接及第三方服务条款。

## 1. 为什么请求这些权限？

| 权限或能力 | 用户打开的功能 | 说明 |
| --- | --- | --- |
| `RECORD_AUDIO` | 麦克风翻译 | 读取麦克风采集的数据；不代表所有模式都会主动开启麦克风。 |
| `MediaProjection` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 系统内部声音 | Android 的 AudioPlaybackCapture 需要用户确认屏幕共享授权。关闭 OCR 时，此路径只需要音频 PCM，不需要主动读取画面帧。 |
| `SYSTEM_ALERT_WINDOW` | 实时字幕悬浮窗 | 在其他 APP 上层显示翻译文字。 |
| `BIND_ACCESSIBILITY_SERVICE`（用户在系统中主动开启） | 独立的全屏翻译 | 可以读取屏幕上的可访问文字；某些功能可申请无障碍截图以做 OCR。**不需要全屏翻译时，可保持关闭。** |
| ROOT / Shizuku（需要额外授权） | 实验性的内部 PCM/通话采集 | 高权限功能可能访问更多音频设备；请只在有权操作的设备上使用，并遵守通话参与者的知情同意。 |
| `INTERNET` | 下载模型、更新检测、在线翻译或 ASR | **是否联网取决于选择的具体功能**。有网权限不等于每段音频都会上传。 |

Manifest 源码位置：`app/src/main/AndroidManifest.xml`。

## 2. 声音/文字会发送到哪里？

- **本地模型**：当你选择 `sherpa-onnx`、Vosk 等手机本地 ASR 时，识别推理在设备本地执行。模型下载可能联网；**导入本地 ZIP / TAR.BZ2 文件不会上传模型压缩包**。
- **安卓系统内置识别**：识别实际由 Android 系统安装的 RecognitionService 提供。系统服务可能使用网络，浮译不能代替 OEM 或 Google 保证严格离线。
- **Groq / Cloudflare 在线 ASR**：需要将录制的音频片段发送到你选择的在线语音识别提供商。
- **微软 Azure Translator / 百度 / 阿里云等在线翻译**：通常发送**已经识别出的文字**以换取翻译；即使 ASR 完全在本机，**使用在线翻译仍会产生上传文字的流量**。
- **Google ML Kit 本机翻译**：语言包在本机运行，首次下载语言包会联网。当前「自动翻译引擎」可能在本地失败时尝试用户已配置的在线翻译服务，所以不等于强制离线。
- **其他联网操作**：检查更新、下载模型、打开官网等独立功能也可能联网。

用户可在 APP 的「设置 → 语音识别引擎」「翻译引擎 / API」「来源官网 · 权限与数据去向」分别查看和修改。

## 3. 自带 / 自行导入模型

可在「离线模型中心」选择内置 sherpa-onnx 模型，也可用 Android 的系统文件选择器将兼容的 `.zip` 或 `.tar.bz2` 包导入私有目录。自定义模型必须从已有 sherpa-onnx 架构中选择一种；**任意 ONNX / PyTorch / GGUF 文件不一定能被本程序识别**。导入过程检查基本模型文件结构和压缩包路径安全，但**不能保证第三方模型安全、许可证合规或真机兼容**。请仅导入可信文件。

每个内置模型提供两个不同链接：
1. **模型研发方官方项目**：原作者、模型用途、许可证与原始代码。
2. **实际部署包地址**：本 APP 使用的 sherpa-onnx 转换包；有些格式来自第三方转换，不能冒充官方原始模型。

导入的模型会明确标记「用户自备，参数量未验证」，不会伪称原作者公开背书。

## 4. 安全提醒 / 如何自行核实

- 本应用当前通过普通 SharedPreferences 保存某些 API Key；**不应声称这些 Key 已受到额外加密保护**。请避免泄露备份、截图及调试日志。
- 不使用 ROOT 通话翻译或全屏翻译时，请不要开启其额外高权限。
- 可通过 Android 权限管理及 PCAPdroid 等网络监视工具，确认软件和系统识别服务在实际使用时访问了哪些域名。
- 本文不是「绝无监控」「任何第三方永不看到数据」的担保。建议对发布 APK 的签名、构建来源和本开源代码做独立验证。

## 5. 官方代码、接口与模型清单

- 浮译源码：https://github.com/dulo11/fanyireal-time
- sherpa-onnx 模型索引：https://k2-fsa.github.io/sherpa/onnx/pretrained_models/
- Android SpeechRecognizer：https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android AudioPlaybackCapture：https://developer.android.com/media/platform/av-capture
- Groq 在线语音识别：https://console.groq.com/docs/speech-to-text
- Cloudflare Whisper Turbo：https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/
- Microsoft Translator：https://learn.microsoft.com/azure/ai-services/translator/
