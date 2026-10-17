# 前台启动摄像头识别服务 / Persistent camera foreground service

现在开启手势的顺序是：

1. 打开 App，授予相机和无障碍权限。
2. **保持 App 可见时**点击 Enable Gesture Scrolling。
3. Activity 先释放它用于预览的 CameraX use cases，立即创建 `GestureDetectionService`。
4. Service 立即调用 `startForeground`，保持唯一的前置摄像头 `ImageAnalysis` 与 MediaPipe 识别器。
5. 离开 App 时 **不再停止或重新启动** service；跨 App 的识别由原本的 `ScrollAccessibilityService` 执行。
6. 回到 App 点击 Disable 可关闭摄像头前台服务，并恢复仅用于预览的 CameraX use case。

### 重要限制

- 启用识别后，App 自身的摄像头预览隐藏，以避免 Activity 和 Service 抢占前置摄像头。
- 因 Android 14+ 对 camera 类型 foreground service 的后台/开机启动限制，本版本不再注册开机启动广播；如果系统终止进程，需要重新打开 App 并点击 Enable。
- Android 无障碍服务的权限申请代码和 Manifest 声明来自 Doom-Scrolling，不使用 hand-Gesture-Controller 的无障碍授权实现。
- 部分 ROM、视频通话等占用摄像头的 App 仍可能让识别中断。
- GitHub Actions 能验证编译成功，但**不能**验证实际设备长期后台运行稳定性；必须真机测试。
