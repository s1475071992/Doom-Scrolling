# Doom-Scrolling — 手势扩展分支

本分支基于 Pixel04M/Doom-Scrolling 的 **CameraX + MediaPipe + ScrollAccessibilityService**，仅借鉴 KarthikSai06/hand-Gesture-Controller 的手势类别、后备几何判断、可配置动作思路。

**没有移植 GestureControl 的无障碍授权、AccessibilityService、设置跳转流程。** 使用原仓库的无障碍服务，首次需要在系统设置里手动开启。

## 功能

- MediaPipe 七种原生静态手势：Thumb_Up、Thumb_Down、Pointing_Up、Closed_Fist、Open_Palm、Victory、ILoveYou
- 几何规则后备识别：Peace、Fist、Open_Palm、OK、Shaka、L_Shape（有重叠手形，部分只在模型输出 None 时触发）
- 可选自训练模型：把 gesture_custom.tflite 与 gesture_labels.json 放在 app/src/main/assets。标签必须包含无手势类，模型至少两个分类。
- 独立的手势映射页，可选：点击、双击、四方向滑动、上下滚动、音量、返回、主页、最近任务、无动作。
- 连续 4 帧识别一致才触发，1 秒全局冷却，同一个姿势持续保持不会连续触发。

## CI 构建

GitHub Actions 中打开 **Android debug APK**，成功后在本次运行的 Artifacts 下载 Doom-Scrolling-gesture-debug。工作流会自动下载 Google 官方 gesture_recognizer.task 模型（不会提交到 Git 历史）。如果 Fork 的 Actions 受限，需要在 GitHub 的 Actions 页启用工作流。

本地构建先把官方模型下载到 app/src/main/assets/gesture_recognizer.task：

https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task

然后执行 ./gradlew :app:assembleDebug。

**注意：** 原仓库的后台摄像头切换方案仍可能受 Android 14+ 前台服务权限限制。本分支暂不承诺已经在 Root 设备真机验证。两个源仓库均未检测到清晰的根目录授权文件，公开重新发布之前应核实许可。
