# 浮译 FloatingTranslator

一个面向 Android 13+ 的系统内部声音实时翻译工具。播放视频、直播、游戏或语音房时，应用捕获允许共享的媒体音频，使用设备端语音识别，再通过 ML Kit 本地模型翻译并显示悬浮字幕。

## 当前支持

- 中文（普通话）
- 英语
- 日语
- 越南语
- 菲律宾语（Tagalog/Filipino）
- 马来语
- 任意上述语言互译，默认翻译成中文
- 系统内部音频捕获
- 原文与译文悬浮字幕
- 字号调整、后台前台服务
- 模型下载后可离线翻译

## 构建

### Android Studio

1. 安装最新版 Android Studio。
2. 打开本项目目录，等待 Gradle 同步。
3. 安装 Android SDK 35。
4. 选择 `Build > Build APK(s)`。
5. APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### GitHub Actions（不需要本地电脑配置 Android 环境）

1. 把整个项目上传到 GitHub 仓库。
2. 打开仓库的 `Actions` 页面。
3. 运行 `Build APK`，或向 `main/master` 分支推送一次。
4. 在完成的任务底部下载 `FloatingTranslator-debug-apk`。

## 手机使用

1. 安装 APK，并允许“录音”和“通知”。
2. 点击“授予悬浮窗权限”。
3. 选择声音语言和目标语言。
4. 点击“下载当前语言模型”；首次下载需要联网。
5. 点击“开始实时翻译”，在系统弹窗中允许共享声音。
6. 切换到视频或直播 App，悬浮字幕会保持显示。

## 重要限制

- Android 会在每次开始捕获时要求用户确认，普通应用不能绕过。
- 对方 App 可以禁止 `AudioPlaybackCapture`。禁止捕获时不会有音频数据，这不是翻译模型故障。
- 设备端语音识别器需要支持 Android 13 的 `EXTRA_AUDIO_SOURCE`。一加 Ace 6 / Android 16 是本项目首要测试设备。
- 菲律宾语在翻译层使用 ML Kit 的 Tagalog (`tl`) 模型，语音识别区域使用 `fil-PH`。
- “下载语言模型”下载的是文本翻译模型；语音识别离线包由手机系统的语音识别服务管理。若提示语音包不可用，请到系统或 Google 语音服务中下载对应语言。
- 本版本不捕获普通电话的上下行音频。电话实时双向翻译需要 Root/系统级实现。

## 技术流程

`其他 App 音频 → AudioPlaybackCapture → Android 设备端语音识别 → ML Kit 本地翻译 → 悬浮字幕`

## 隐私与费用

应用不包含账号、广告或付费 API。翻译模型下载完成后，文本翻译在本机执行。语音是否完全离线取决于手机安装并启用的设备端语音识别服务。
