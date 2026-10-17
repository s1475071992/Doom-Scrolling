# v1.10.0-dev.5：自动 NPU → GPU → CPU 与摄像头链路减负

仅供 PR #11 的实验分支。主分支 / v1.9 不变；未做 ColorOS 16 真机功耗或手势准确率验收，不承诺一定省电。

## 自动加速模式（默认）
- AUTO 不再需要额外勾选“NPU 实验开关”，启动时顺序尝试 **LiteRT NPU → MediaPipe GPU → MediaPipe CPU**。
- NPU 运行库/编译器/模型初始化不可用时，尝试 GPU 再 CPU；NPU 首次或后续模型运行抛异常时自动转 GPU/CPU；GPU 异步回调失败后转 CPU。
- 手动模式保留 GPU → CPU、仅 CPU；已有用户显式选择 GPU/CPU 的偏好不会被覆盖。
- 显示的「NPU」是 LiteRT 请求 NPU 而不是硬件负载证据。未做 QNN/HTP profiler，仍可能混用 CPU；准确率仍待真机验收。

## 省电方向
- CameraX 的分析流从 RGBA_8888 改为 **YUV_420_888**，让跳过推理的帧不再提前触发 CameraX RGBA 全帧转换。
- 只有 AI 节流/防重入判定通过的图像帧，才执行 YUV → ARGB 颜色转换。
- 取消分析链路内 YUV → NV21 → JPEG → Bitmap 的临时路径；直接读取 Y/U/V plane，处理 rowStride、pixelStride 和 0/90/180/270 旋转。
- 每个分析器复用 ARGB 像素数组与 Bitmap（MediaPipe async 结果关闭之后才允许复写）；NPU palm/landmark 裁剪的 Canvas 对象也复用。
- 将 ImageAnalysis 目标分辨率从 640×480 降为 **480×360**（实际尺寸由 CameraX 选择）。小手、弱光、较远距离可能降低检出率，需要观察。
- 保留原有摄像头 AE FPS 低档请求，保留待机约 1 FPS / 起手式和空鼠加速，不做反复关闭/重绑摄像头（避免唤醒延迟和稳定性风险）。
- 前台摄像头服务、系统可见通知、无障碍权限与六手势/空鼠状态机不变。

## 已知限制
- CPU Kotlin YUV 转 RGB 本身也有开销，在高帧率连续识别场景可能不如特定机型的原生 libyuv 路径；需要用真机热量与流畅度验证。
- 摄像头传感器/ISP 即使在约 1 FPS 模型节流时仍可按驱动最低约 10 FPS 工作；此改动**不**等同于把传感器降为 1 FPS。
- NPU 模型仍是简化的双网络 ROI 路线；不能保证与原 MediaPipe GPU/CPU 的坐标、跟踪或手势质量完全一致。
- 如 NPU 子模型成功运行但输出语义错误、未抛异常，仍可能导致识别失败；必须查看设备上的手势表现。

## 最低限度的验收
1. 确认自动模式在 NPU 不可用时能显示 GPU / CPU 并正常识别六手势。
2. 在一加 ColorOS 16 测试摄像头 10 FPS 待机、起手后恢复交互帧率，检查旋转方向及镜像。
3. 快速测试双指四方向、空鼠拖动及较远的小手；如 480×360 导致明显精度退化，恢复目标分辨率为 640×480 而保留 YUV 改造。
4. 查看 CI 中 :app:testDebugUnitTest、:app:lintDebug、:app:assembleDebug 结果；真机未验证前不发布稳定版。
