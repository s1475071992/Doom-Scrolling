<div align="center">

# 隔空手势 · Doom-Scrolling

**不用触碰屏幕，用手势控制 Android 上的其他应用。**

基于前置摄像头、MediaPipe 手部关键点与 Android 无障碍服务的免费、无广告手势控制工具。

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![构建状态](https://github.com/s1475071992/Doom-Scrolling/actions/workflows/android-debug.yml/badge.svg)](https://github.com/s1475071992/Doom-Scrolling/actions/workflows/android-debug.yml)
[![公开下载](https://img.shields.io/badge/下载-v1.10.0-0969da?logo=github)](https://github.com/s1475071992/Doom-Scrolling/releases/tag/v1.10.0)

### [⬇️ 获取 Android APK](https://github.com/s1475071992/Doom-Scrolling/releases/tag/v1.10.0) · [📖 使用指南](#使用方法) · [🐛 反馈问题](https://github.com/s1475071992/Doom-Scrolling/issues)

</div>

> **v1.10.0 发布说明：** 此版本以公开正式 GitHub Release 形式发布，但 APK 仍使用 GitHub Actions 临时调试密钥签名，**不是永久发布密钥签名的商店版**，后续自动覆盖升级不保证成功。NPU 路径属于按需尝试的可降级加速功能，硬件算子归属与各型号手势精度未通过完整真机认证。

## 功能

- **系统级手势控制**：在支持无障碍模拟滑动的应用中翻页、滑动、返回或调节音量。
- **六种动态手势**：必须先稳定保持起手式，再完成实际手指或整掌运动，不会仅凭静止手形立刻执行动作。
- **空中鼠标（实验性）**：食指控制光标，短捏合松开点击，捏合移动并松开拖动。
- **跨应用轻量提示**：起手式仅显示 ✊、☝️ 或 ✌️，触发时显示简短中文动作名称。
- **可自定义映射**：六种动态动作可分别关联系统操作。
- **后台持续识别**：使用可见的前台摄像头服务通知，带「停止手势」按钮；不会尝试隐藏摄像头使用状态。
- **智能省电（默认开启）**：等待起手式时 AI 推理约 1 FPS，并请求摄像头驱动允许的最低采集档；确认起手式和空鼠使用更高推理速度。YUV 图像仅对被选中推理的帧做 RGB 转换；相机传感器在服务运行时仍可能持续采集。
- **AI 加速模式**：自动按 NPU → GPU → CPU 尝试，失败自动降级，也支持 GPU 优先或 CPU 兼容模式；NPU 仅代表 LiteRT 请求，不证明所有算子都在 NPU 运行。
- **本机处理**：图像仅用于本地实时识别，应用本身不请求网络权限；没有广告或云端识别功能。

## 手势速查

| 起手式 | 稳定后执行的动作 | 默认用途 |
| --- | --- | --- |
| ✊ 握拳 | 只伸直食指 → **食指上翻** | 向上翻页 |
| ☝️ 伸直食指，其他手指收起 | 弯下食指 → **食指下翻** | 向下翻页 |
| ✌️ 伸直食指、中指，其他手指收起 | 整只手向上移动 → **双指向上移动** | 向上翻页 |
| ✌️ 同上 | 整只手向下移动 → **双指向下移动** | 向下翻页 |
| ✌️ 同上 | 整只手向左移动 → **双指向左移动** | 向左滑动 |
| ✌️ 同上 | 整只手向右移动 → **双指向右移动** | 向右滑动 |

起手式需要保持约半秒才会确认。双指可以斜向镜头，手心或手背朝向均尝试识别；强烈的遮挡、逆光或某些手机的前置摄像头镜像仍会影响实际精度。

## 使用方法

1. 在 [Releases 页面](https://github.com/s1475071992/Doom-Scrolling/releases/tag/v1.10.0) 下载 `Doom-Scrolling-v1.10.0-release.apk`。可使用同页 `SHA256SUMS.txt` 校验文件完整性。此 APK 使用临时调试密钥签名。
2. 安装后开启**相机权限**与**通知权限**（Android 13 及以上）。
3. 按应用提示打开 Android 系统**无障碍设置**，启用「隔空手势」服务。
4. 返回应用，点击**开启手势识别**，确认通知栏出现运行中的常驻通知。
5. 在「动作手势映射」设置需要的动作，看到起手式符号后再进行相应动作。
6. 若要使用空鼠，打开「实验性空中鼠标」开关；它与普通手势模式互斥。需要省电可以保留默认的「智能省电」。结束时请点击**关闭手势识别**或通知中的**停止手势**。

**兼容性：** Android 7.0（API 24）及以上；空鼠的连续按住拖动需要 Android 8.0（API 26）及以上。需要前置摄像头与系统无障碍服务。不同 ROM、应用和受保护页面可能限制触摸注入。

## 下载、升级与校验

| 项目 | 说明 |
| --- | --- |
| 公开发行页 | [v1.10.0](https://github.com/s1475071992/Doom-Scrolling/releases/tag/v1.10.0) |
| APK | `Doom-Scrolling-v1.10.0-release.apk`（临时调试密钥签名） |
| SHA-256 | 从 Release 附件 `SHA256SUMS.txt` 查看 |
| 代码与自动测试 | [GitHub Actions](https://github.com/s1475071992/Doom-Scrolling/actions) |
| 稳定签名 | **尚未配置**；后续若要提供免卸载升级，需由仓库所有者安全配置固定的签名密钥 |

v1.9 的 debug 包使用 `.debug` 包名，与 v1.10 的 release 构建包名不同，可共存但不继承应用内数据。如果安装时出现「签名不一致」，可能是现有包使用了不同签名。请先自行备份设置，再按需卸载旧包并安装新版本；**请勿为了绕过签名错误关闭 Android 系统安全保护**。

仓库内历史遗留的 `app-release.apk` **不是最新发布包**，请始终从上面的 Release 页面下载；不要依赖其他项目的下载链接。

## 隐私、安全与稳定性

- 摄像头画面仅在本机用于识别；应用无 `INTERNET` 权限，不提供摄像视频上传。
- 应用仅在用户主动启用时启动摄像头前台服务，并通过系统通知提供停止入口。
- Android 无障碍服务用于跨应用触摸注入和可见的提示悬浮层。请理解该权限能力，仅从可信的发布页面获取 APK。
- 不提供绕过系统隐私限制的后台静默摄像头启动，不保证任何受保护界面都允许模拟触摸。
- 自动测试覆盖动作状态机、方向轨迹等逻辑；并不等于已经通过所有手机型号的真实使用测试。

## 开发与构建

项目采用 **Kotlin / Jetpack Compose / CameraX / MediaPipe Tasks Vision**；本仓库延续 Doom-Scrolling 原有的 `ScrollAccessibilityService` 权限与触摸实现。

```bash
git clone https://github.com/s1475071992/Doom-Scrolling.git
cd Doom-Scrolling
# 准备 JDK 21、Android SDK 36，并在 app/src/main/assets 放入
# 官方 MediaPipe gesture_recognizer.task 模型
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions 会从官方的版本化 MediaPipe 模型地址获取 `.task`，执行测试、静态检查和编译。详见 [构建工作流](.github/workflows/android-debug.yml)。

## 项目来源、问题反馈

本仓库基于 [Pixel04M/Doom-Scrolling](https://github.com/Pixel04M/Doom-Scrolling) 持续改造，保留原有无障碍服务路线；手势交互曾参考 [KarthikSai06/hand-Gesture-Controller](https://github.com/KarthikSai06/hand-Gesture-Controller) 的设计思路。感谢原作者。

有问题请使用 [Issues](https://github.com/s1475071992/Doom-Scrolling/issues) 提交：Android 版本、机型、动作、是否手心/手背朝镜头、帧率与错误表现。请避免上传包含私密信息的摄像头画面。

**许可说明：** 仓库当前未附独立 `LICENSE` 文件，公开可查看源码不自动等于允许任意再分发或商用；使用上游代码时仍须核对并遵守其许可条件。
