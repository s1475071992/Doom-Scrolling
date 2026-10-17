package com.example.myapplication

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Measures capture *results* from Camera2 SENSOR_TIMESTAMP, rather than the
 * throttled MediaPipe inferencing rate or delivered ImageProxy frame rate.
 */
class SensorCaptureFpsMonitor : CameraCaptureSession.CaptureCallback() {
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L
    private var frames = 0

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(this) {
            if (firstTimestampNs == 0L || timestamp <= lastTimestampNs) {
                firstTimestampNs = timestamp
                frames = 0
            } else {
                frames++
            }
            lastTimestampNs = timestamp
            val elapsedNs = timestamp - firstTimestampNs
            if (elapsedNs >= 2_000_000_000L && frames > 0) {
                val fps = frames * 1_000_000_000.0 / elapsedNs
                GestureDiagnostics.cameraCapturedFps(
                    String.format(Locale.US, "%.1f FPS（相机捕获结果）", fps)
                )
                frames = 0
                firstTimestampNs = timestamp
            }
        }
    }
}

/**
 * Camera2 AE target FPS control for a single active CameraX camera.
 * An FPS range is a driver *request*, not a guaranteed exact sensor rate.
 * For example [1,30] is not a 1 FPS mode.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraSensorFpsController(
    camera: Camera,
    private val callbackExecutor: Executor
) {
    private val control = Camera2CameraControl.from(camera.cameraControl)
    private val ranges: List<CameraFpsBand>
    private var selected: CameraFpsBand? = null
    @Volatile private var closed = false

    init {
        val available = try {
            Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w("CameraSensorFps", "Camera2 AE FPS characteristics unavailable", e)
            emptyList<Range<Int>>()
        }
        ranges = available.filter { it.lower > 0 && it.upper >= it.lower }
            .map { CameraFpsBand(it.lower, it.upper) }.distinct()
        if (ranges.isEmpty()) {
            GestureDiagnostics.cameraTarget("驱动未报告可控帧率；使用系统默认值")
        }
        Log.i("CameraSensorFps", "Advertised AE FPS ranges: $ranges")
    }

    fun requestActive(active: Boolean) {
        val target = (if (active) CameraFpsPolicy.active(ranges)
            else CameraFpsPolicy.standby(ranges)) ?: return
        synchronized(this) {
            if (closed || target == selected) return
            selected = target
        }
        val label = if (active) "动作／空鼠" else "等待起手式"
        GestureDiagnostics.cameraTarget("$label：正在请求 $target（非实测）")
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(target.min, target.max)
                ).build()
            val future = control.setCaptureRequestOptions(options)
            future.addListener({
                if (closed) return@addListener
                try {
                    future.get()
                    GestureDiagnostics.cameraTarget("$label：已请求 $target（需看实测）")
                } catch (e: Exception) {
                    Log.w("CameraSensorFps", "Driver rejected FPS request", e)
                    GestureDiagnostics.cameraTarget("$label：驱动拒绝 $target，保持原帧率")
                }
            }, callbackExecutor)
        } catch (e: Exception) {
            Log.w("CameraSensorFps", "Failed to set camera FPS range", e)
            GestureDiagnostics.cameraTarget("$label：无法配置 $target")
        }
    }

    fun close() {
        synchronized(this) { closed = true }
    }
}
