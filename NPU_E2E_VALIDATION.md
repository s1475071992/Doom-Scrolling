# 双 NPU 端到端验证说明（实验版）

实验代码现包括 CameraX RGBA → 192×192 palm SSD → 2016 anchors → 旋转手部 ROI → 224×224 hand landmark → 21 点原图坐标 → 现有六手势／空鼠状态机，两个网络均通过 LiteRT 2.3 CompiledModel(NPU) 请求推理。只有在设置中**手动开启「实验性双 NPU 流水线」**且模式是「自动」时才会尝试；默认仍为原 MediaPipe GPU→CPU，v1.9 主分支不受影响。

**当前状态：仅实现／编译实验路径，并未用真实设备验证输出准确率、NPU 委托分区或持续发热；不能视为已完成实测。**

## 模型与算法参考
- Google MediaPipe `palm_detection_cpu.pbtxt` 的 192 输入、2016 个锚点、18 回归坐标、score sigmoid
- Google MediaPipe `hand_landmark_cpu.pbtxt` 的 224 输入、四输出顺序、21 点、Z 归一化比例
- 处理过程简化为单手最优检测／ROI 跟踪；还不具备原 MediaPipe Weighted NMS、手掌平滑跟踪的完全等价实现
- 新的 LiteRT 2.3.0 官方 MediaTek / Qualcomm Maven runtime 包解决 SDK 分发入口问题，但不能代替处理器匹配的 HTP/APU 固件、模型委托兼容与真机测量

## 验收步骤（双设备分别执行）
1. 在骁龙与天玑安卓 12+ 真机安装实验 APK；确认 CPU 模式、GPU 模式原有手势/空鼠正常。
2. 开启 NPU 实验开关。录制 NPU 初始化、首帧推理与输出校验；如果变成「GPU · NPU 实验失败后回退」，不能算 NPU 成功。
3. 使用硬件供应商的 QNN/HTP 或 MediaTek APU profiler 验证两个网络确实有 NPU 运算分区；LiteRT 默认可能只将部分算子下发到 NPU，不能只根据 `CompiledModel.Options(NPU)` 判定成功。
4. 每个平台分别用手心/手背和倾斜姿态验证全部六项动态手势，以及空鼠移动、点击、拖动；记录成功、失败、误触率（与 v1.9 对照）。对每个手势建议至少 30 次重复。
5. 同一房间、固定亮度及电池起始电量，分别测 CPU/GPU/NPU 各 30 分钟，记录 `adb_heat_benchmark.sh` 结果、平均识别 FPS、平均推理耗时及实际 NPU 占用，查看是否发热或掉帧。
6. 只有六种动作和空鼠的准确率不下降、两款设备 NPU profiler 记录通过，且 NPU 相对 CPU 的温度和耗电改善可复现，才打开默认 AUTO NPU 并合入正式版。

## 数据收集
`bash tools/adb_heat_benchmark.sh <adb-serial> 1800 <cpu|gpu|npu>`

采样到 `benchmark-results/`（不要上传含设备标识／系统日志的原始记录至公开仓库）；其中电池温度并非 SoC 温度，电池电流也不代表仅当前 App 的用电。`adb shell dumpsys thermalservice` 的可见程度因机型而异。

## 已知待校验项
- 192 检测输出张量顺序、ROI 旋转和投影与 MediaPipe 逐帧一致性。
- 21 点模型的 63 浮点 tensor 与 hand confidence 对不同设备的输出可用性。
- Snapdragon v69/v73/v75/v79/v81 与 MediaTek v8/v9 运行库配套设备覆盖；不能认为厂商名称意味着任意型号支持。
- 不得将 NPU 运行时库的下载成功、编译通过或 model.run 返回成功冒充真机 NPU 占用证明。
