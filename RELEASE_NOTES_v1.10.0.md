# Doom-Scrolling v1.10.0

这是 GitHub 上公开发布的 v1.10.0，包含 v1.9 后的 CameraX / AI 后端和手势识别优化。

## 主要更新
- 默认自动按 **NPU → GPU → CPU** 尝试推理。厂商运行库不支持、NPU 初始化/执行异常时回退 GPU/CPU；GPU 初始化、回调或同步调用失败时回退 CPU。
- 省电链路使用 CameraX YUV_420_888，只有需要推理的帧才进行 RGB 转换；重复使用图像缓冲区，待机推理约 1 FPS。
- 调整 NPU 手掌初始 ROI、跟踪框和双指移动判定容错，改善 ✌️ 上下左右不触发的情况。
- 保留原来的六种动态手势、空鼠、可见前台摄像头通知、用户授权的无障碍服务。

## 已知限制与安装说明
- **签名特别说明**：目前仓库还未配置永久发布签名。此 APK 是 `release` 构建类型，但暂时由 Android Gradle 插件默认 debug keystore 签名；不同 GitHub Actions 构建可能无法覆盖升级，不能视为应用商店级别的稳定签名发行。不要禁用 Android 安全检查来安装。
- 本次 GitHub Actions 自动检查通过并不等于已在 ColorOS 16 等机型完成长期真机测试。
- LiteRT NPU 选项仅表示请求 NPU 推理；模型可能部分/全部 CPU 回退，逐算子硬件使用率未认证；部分双指手势可能依旧受摄像头视角和追踪影响。
- APK 的 Android package name 为 `com.example.myapplication`，与旧 `-debug` 包的 `com.example.myapplication.debug` 不同；两者不自动迁移数据。
- 此版本并非 Google Play 商店发布。APK 仅从本仓库官方 Release 附件下载，并按 `SHA256SUMS.txt` 验证。

## 校验
发布工作流运行 `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`、检查 APK 签名与 NPU 子模型/厂商库打包，并生成 SHA-256 校验文件。