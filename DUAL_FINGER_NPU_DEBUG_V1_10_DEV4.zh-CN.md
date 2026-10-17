# v1.10.0-dev.4 — 双指识别与 NPU 运行诊断

本次更新仅在 `experiment/ai-auto-acceleration-v110` 开发，不合并 v1.9 稳定版。

## 双指识别修复（单指和空鼠路径不变）
- 双指起手式的食指、中指展开判断略微放宽，但仍要求无名指、小指收拢，四指展开不会被当成双指。
- 双指确认从原来的至少 **440ms / 5 有效帧** 调整为至少 **300ms / 3 有效帧**。
- 确认阶段允许最多 2 帧短暂错判，不立即回到 1 FPS 待机；移动阶段允许最多 4 帧双指姿态丢失，保留方向候选，防止 NPU 21 点抖动导致指令丢失。
- 位移阈值从掌宽 **0.55** 调整为 **0.33**，主方向判断从 1.40 调为 1.25，仍保留直线度至少 0.72、同方向稳定至少 3 帧和 120ms 的防误触条件。
- 没有改变原先四种方向与滑动事件的映射，也没有改变空鼠点击和拖拽算法。
- 增加测试：四方向较小位移、双指确认阶段短暂错帧、移动阶段短暂错帧、四指不误识别，以及现有单指／斜向／静止误触测试。

## 运行硬件诊断：哪些内容可以被证实

现在「实时识别调试」包含：

- **芯片信息**：Android 系统报告的 SOC_MANUFACTURER / SOC_MODEL / HARDWARE，仅说明设备芯片，不等于当前算子分配。
- **AI 推理后端**：应用请求的 GPU、CPU 或 NPU 模式，以及运行时发生的引擎回退。
- **模型执行统计**：手掌检测、关键点两套 LiteRT 模型实际调用次数；各自平均和最近一次 `model.run()` 墙钟耗时。
- **硬件算子分配**：当前 Kotlin LiteRT 2.3 API 没有公开的逐算子 NPU/CPU 分区报告，因此一律明确写「未验证，可能 CPU 部分/全部回退」，绝不能展示虚构的「NPU 占比 100%」。
- **最近回退原因**：NPU 模型初始化、执行异常或 GPU Delegate 报错后的回退原因。
- **手势判断详情**：双指候选阶段、双指位移、阈值、直线度，帮助确认是否由起手式或动作判定阻碍了触发。
- 摄像头实际捕获 FPS、摄像头请求 FPS、模型推理 FPS、设备电池温度仍然保留。

## 真机确认 NPU 的方法

Android Studio / 未 Root 的 ColorOS 普通调试页面不能可靠展示天玑 APU 或高通 HTP 逐算子执行比例。连接 ADB 可查看 LiteRT 原生插件日志：

```bash
adb logcat -c
adb logcat -v time | grep -Ei 'NpuSubmodel|LiteRT|DispatchDelegate|TfLiteXNNPackDelegate|CompilerPlugin|QNN|MediaTek'
```

识别到 `DispatchDelegate` 的 delegate 替换日志说明某些模型子图经 LiteRT 分配给 Dispatch Delegate；观察到 `TfLiteXNNPackDelegate` 说明有 CPU XNNPACK 执行的可能。**无日志不证明 NPU 未运行，看到 DispatchDelegate 日志也不证明全部算子都由 NPU 执行。** 对全分区比例和真实 NPU 算力占用，必须提供 Qualcomm QNN/HTP 或 MediaTek APU profiler 追踪。

## 继续验收
`dev.4` 已增加单元测试但真机准确率仍需由用户测试。建议分别测试双指起手式识别、上下左右移动和空鼠点击拖拽。若双指在出现「✌️ 已确认」后仍无动作，提供「手势判断详情」一行及当时模型统计，就能判断是位移阈值、方向条件，还是模型输出抖动。
