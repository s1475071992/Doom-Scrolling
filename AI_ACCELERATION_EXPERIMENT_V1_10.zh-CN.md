# v1.10 实验分支：AI 自动加速 / NPU 研究

> **未发布实验代码**，不属于公开发布的 v1.9.0-rc.1。只有在真机、长时间温升和动作准确率回归通过后才考虑替换稳定版。

## 已实现（本分支）

- 应用首页添加「AI 加速模式」：自动（显示 NPU → GPU → CPU 的目标优先级）、GPU 优先和仅 CPU。设置保存在本地，关闭并重新开启识别后生效。
- 现有 MediaPipe Gesture Recognizer 首次真正提供 **GPU Delegate**：GPU 不可用或初始化失败时回退 CPU；GPU 运行时回调报错后尝试在 CameraX 分析线程重建 CPU 识别器。
- MediaPipe 推理器的 **创建、recognizeAsync() 调用、close()** 在同一单线程摄像头分析执行器上完成，以满足 GPU 上下文线程要求。
- 设置页诊断区域显示当前实际工作的 CPU/GPU 后端；不把设备具有 NPU 等同于模型已经跑在 NPU。
- 不修改六种动态手势、空鼠点击拖动、无障碍服务授权机制、前台摄像头服务的权限声明。

**NPU 状态：没有运行中的 NPU 推理引擎。** 框架候选优先级包含 NPU，但 `ExperimentalNpuBridge.validatedRunnerInstalled == false`，因此 AUTO 当前实际顺序为 **GPU → CPU**。不能将这称作骁龙/天玑 NPU 已支持。

## 为什么不把摄像头和图像转换交给 NPU？

- 采集光子并生成图像帧是 **ISP/摄像头传感器** 工作，不能由 NPU 替代。
- YUV→RGB、旋转、裁剪、缩放等属于像素预处理，通常使用 **ISP、GPU、CPU SIMD 或零拷贝缓冲**；NPU 一般用于支持的网络算子，不是通用图片格式转换器。
- 现有 CameraX 设置 RGBA_8888 会在内部执行 YUV→RGBA 转换，可能在未进行 MediaPipe 推理的帧上也发生转换；虽然 `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` 会丢帧，但**推理节流不等于摄像头传感器停止工作**。
- 直接改成 CameraX YUV 后走当前的 `ImageProxy.toBitmap()` 将使用 JPEG 编码/解码，可能反而更耗 CPU。这不是可接受的节能捷径。
- 后续候选路线是通过 Camera2 控制可支持的帧率范围、用 GPU 图像输入/纹理做转换与旋转、对比 CameraX RGBA 的帧处理耗时；必须做机型兼容性测试。

## 双平台 NPU 引擎下一步的验收门槛

1. 将手掌检测与 21 点关键点模型拆为 **LiteRT 可直接加载的子模型**（原始 `gesture_recognizer.task` 不能直接当成 NPU 委托模型），实现同等的前处理、关键点后处理、追踪和朝向兼容。
2. 分别实现 Qualcomm QNN / MediaTek NeuroPilot 的依赖加载与模型运行适配。**芯片型号不是通过验收的证据**；每台设备需测试运行库与运算符。
3. 运行时实际检查 NPU runner 是否可初始化、模型是否能跑通、21 点输出置信度是否正常；在这些条件**全部通过**后才允许 `validatedRunnerInstalled` 变成可用，而不是仅凭 SoC 名称切换。
4. NPU 初始化或运行失败时 **NPU → GPU → CPU**，并在 UI 中展示真实后端与回退原因。
5. 针对 ColorOS 16、骁龙和天玑机型跑：握拳食指上翻、食指下翻、双指四向移动、手心/手背朝镜头、斜向近镜头、空鼠拖动，逐项录制通过率。
6. 用 Battery Historian / Android Profiler 对比连续 30–60 分钟下的 CPU、GPU、NPU 占用，帧耗时、平均温度和实际耗电。只有在准确率和功耗达到目标时才考虑合入主分支。

## 使用方式

打开实验版，进入主界面的「AI 加速模式」，选择「自动」。如果 GPU 可用且初始化成功，调试区显示 `GPU · MediaPipe`；否则显示 `CPU · MediaPipe（兼容/回退）`。切换模式需要停止并重新开启手势服务。

**没有真机日志或温升数据时，不应以 CI 编译通过宣称 GPU 节能或 NPU 适配已经验证。**

参考官方文档：
- https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/gesture_recognizer/android
- https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer/android
- https://developer.android.com/media/camera/camerax/analyze
- https://ai.google.dev/edge/litert/android/npu

## 2026-10-17：真实 NPU 集成进度（未通过双平台验收）

CI 已下载官方手势模型并递归解包，取得 palm_detection.tflite 与 hand_landmark.tflite。新增基于 LiteRT 2.2 CompiledModel / BuiltinNpuAcceleratorProvider 的 `NpuSubmodelProbe`，支持调用 NPU 模型原生输入、输出并记录失败；同时增加需在真机运行的 `NpuSubmodelDeviceTest`。

官方 v2.2.0 `litert_npu_runtime_libraries_jit.zip` 的 SHA-256 已固定验证。实际检查发现 **MediaTek 的 JIT 插件缺失**，脚本遇到缺失时明确提示 UNAVAILABLE，不会冒充已适配。测试 APK 仅尝试打包 Qualcomm HTP v75 版本的 LiteRT dispatch/compiler 插件；其它骁龙 HTP 类型需独立适配，QNN/HTP 设备运行库可能还须单独提供并检查再分发许可。

**注意**：NPU 子模型运行器尚未取代 MediaPipe 的完整相机帧/掌心检测/跟踪/裁剪/21点后处理流水线。当前应用自动模式真正支持的是 MediaPipe GPU → CPU；**不能把这版 APK 称为天玑+骁龙 NPU 加速版**。即便 `CompiledModel.Options(NPU)` 初始化成功，也可能存在 CPU 运算部分，必须在实际芯片上抓取 NPU 负载/委托日志并执行 parity test。

### 真机验证

1. 用安装包附带的正式模型子文件与 NPU 运行库运行专用 `androidTest`。
2. 测试 Snapdragon v75 和 MediaTek 芯片，确认 `NpuSubmodelDeviceTest` 的真实模型推理输出。
3. 通过系统硬件性能分析工具确认真正 NPU 执行，不把只初始化成功、但回落 CPU 的结果计为通过。
4. 只有在手掌/手指关键点流水线在两个平台全部跑通并通过手势、空鼠、发热对照测试后才合并到稳定版。
