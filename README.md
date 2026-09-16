# 浮译 FloatingTranslator v0.7.0

一个面向 Android 13+ 的实时翻译工具。支持实时语音翻译、无障碍全屏翻译、OCR、离线 ASR、Azure Translator 多账号池，以及 ROOT/Shizuku 通话实验能力。

## 当前支持

- Azure Translator、ML Kit、百度、阿里云等多翻译引擎
- Azure Translator 动态多账号池，支持轮番调用与额度/限流后自动切换
- ML Kit 设备端翻译支持的全部 59 种语言
- 常用语言优先：中文、英语、日语、越南语、菲律宾语、马来语、韩语、法语等
- 任意受支持语言互译，默认翻译成中文
- 系统内部音频捕获
- 麦克风/免提电话翻译实验模式
- Vosk / sherpa-onnx 等离线 ASR 能力
- 原文与译文悬浮字幕
- 无障碍全屏翻译：优先直接读取页面文字和坐标
- 页面没有无障碍文字节点时，可使用 AccessibilityService 截图 + ML Kit OCR 兜底
- 全屏翻译不依赖 MediaProjection，因此不会占用系统录屏会话
- 全屏翻译悬浮球：轻点翻译、长按连续翻译、拖动移动
- 字号调整、设置自动保存、语言一键互换
- 最近原文和译文记录、一键复制与清空
- 自定义“浮译”应用图标
- 模型下载后可离线翻译

## 全屏翻译 v0.7.0

流程：

`目标 App → AccessibilityService 读取文字节点/坐标 → Azure 批量自动识别源语言并翻译 → TYPE_ACCESSIBILITY_OVERLAY 原位置覆盖译文`

如果页面没有可读取的无障碍文字节点：

`AccessibilityService.takeScreenshot() → ML Kit OCR → Azure/当前翻译引擎 → 原位置覆盖译文`

说明：

- 这条全屏翻译链路不使用 MediaProjection，不需要开启“录制屏幕”。
- 普通 App、浏览器、聊天界面等优先直接读取无障碍文字，因此没有 OCR 错字。
- 游戏、视频内嵌字幕、Canvas/OpenGL 等页面通常会进入截图 OCR 兜底。
- 部分银行、DRM 或受保护页面可能禁止截图，第三方应用无法绕过系统保护。
- 配置了 Azure 后，全屏文字批量翻译会省略 `from`，由 Azure 自动检测源语言；只需要设置目标语言。

## 构建

### Android Studio

1. 安装最新版 Android Studio。
2. 打开本项目目录，等待 Gradle 同步。
3. 安装 Android SDK 35。
4. 选择 `Build > Build APK(s)`。
5. APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### GitHub Actions

仓库包含两个流程：

- `CI Build`：推送到 `main` 或 `fix/**` 后，运行单元测试、Android Lint 和签名构建。
- `Build Signed APK`：正式代码推送到 `main` 或手动运行时，验证、签名并发布；正式版与测试版渠道分开。

## 手机使用

### 实时语音翻译

1. 安装 APK，并允许需要的录音/通知权限。
2. 按需要授予悬浮窗权限。
3. 选择声音语言和目标语言。
4. 配置在线翻译引擎，或下载本地模型。
5. 视频、直播、游戏声音：声音来源选“系统内部声音”。
6. 电话或语音通话：按设备能力选择麦克风、ROOT 或 Shizuku 实验来源。

### 无障碍全屏翻译

1. 首页进入 `全屏翻译`。
2. 打开系统无障碍设置，启用 `浮译 · 全屏翻译`。
3. 返回需要翻译的 App，屏幕右侧会出现 `译` 悬浮球。
4. 轻点悬浮球：翻译当前屏幕。
5. 长按悬浮球：开启/关闭连续翻译；连续模式显示 `自`。
6. 拖动悬浮球：移动位置。
7. 页面切换时旧译文覆盖层会自动清除。

## 重要限制

- 系统内部音频捕获仍受 Android `AudioPlaybackCapture` 和 MediaProjection 限制；这与无障碍全屏翻译是两套独立链路。
- 对方 App 可以禁止音频捕获。禁止时没有内部音频数据，不代表翻译模型故障。
- 翻译支持的语言数量与手机语音识别服务支持的语言数量不是一回事。
- 菲律宾语在 ML Kit 翻译层使用 Tagalog (`tl`)；Azure 使用 `fil`。
- ML Kit 设备端翻译适合日常和简单内容，部分非英语互译可能经过英语中转。
- 普通第三方应用不能直接读取电话上下行内部音频；ROOT/Shizuku 方案也受设备和 ROM 限制。
- 无障碍服务只用于用户主动启用的全屏翻译；代码会跳过浮译自身界面，避免递归翻译。

## 固定签名和自动发布

仓库已配置固定签名发布流程。正式发布需要在仓库 `Settings → Secrets and variables → Actions` 中保留以下 4 个 Repository secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

以后打开 `Actions → Build Signed APK → Run workflow` 即可构建正式签名 APK。正式升级必须始终使用同一把签名密钥。

## 隐私与费用

离线翻译和离线 ASR 在手机本地运行。使用 Azure、百度、阿里云等云端翻译时，待翻译文本会发送到对应服务商。API 密钥由用户自行配置。无障碍全屏翻译的截图 OCR 兜底在设备端通过 ML Kit OCR 处理，之后只把识别出的文字交给当前翻译引擎。

## 0.7.0 修复与面对面对话

详细变更、离线模型选择及验证范围见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。更新使用相同签名覆盖安装，保留应用数据。API 配置仅保留在当前设备，不随系统备份或设备迁移传送；更换设备需要重新填写。
