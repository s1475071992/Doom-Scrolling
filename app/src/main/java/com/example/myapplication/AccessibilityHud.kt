package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/** System-wide, non-touchable, non-focusable accessibility overlays.
 * No SYSTEM_ALERT_WINDOW grant is needed for the service's TYPE_ACCESSIBILITY_OVERLAY.
 */
class AccessibilityHud(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var toast: TextView? = null
    private var cursor: TextView? = null
    private var px = .5f
    private var py = .5f
    private var smoothReady = false
    private var clickBusy = false
    private val hideToast = Runnable { toast?.let { safeRemove(it) }; toast = null }

    private fun params(width: Int, height: Int, gravity: Int) =
        WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT).apply { this.gravity = gravity }

    private fun safeRemove(view: TextView) {
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    fun message(message: String) {
        handler.post {
            if (!GestureRuntime.feedback) return@post
            handler.removeCallbacks(hideToast)
            val v = toast ?: TextView(service).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(24, 13, 24, 13)
                background = GradientDrawable().apply {
                    setColor(Color.argb(210, 24, 26, 33))
                    cornerRadius = 24f
                }
                try {
                    wm.addView(this, params(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { y = 110 })
                    toast = this
                } catch (_: Exception) { return@post }
            }
            v.text = message
            handler.postDelayed(hideToast, 950L)
        }
    }

    fun move(rawX: Float, rawY: Float) {
        handler.post {
            // Mirror selfie camera horizontally. Orientation / calibration is
            // device-specific; this is intentionally an experimental mouse.
            val targetX = (1f - rawX).coerceIn(.03f, .97f)
            val targetY = rawY.coerceIn(.04f, .96f)
            if (!smoothReady) {
                px = targetX; py = targetY; smoothReady = true
            } else {
                px += (targetX - px) * .36f
                py += (targetY - py) * .36f
            }
            val v = cursor ?: TextView(service).apply {
                text = "●"
                textSize = 30f
                setTextColor(Color.rgb(0, 215, 244))
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                try {
                    wm.addView(this, params(64, 64, Gravity.TOP or Gravity.LEFT))
                    cursor = this
                } catch (_: Exception) { return@post }
            }
            val m = service.resources.displayMetrics
            val layout = v.layoutParams as WindowManager.LayoutParams
            layout.x = (px * m.widthPixels).roundToInt() - 32
            layout.y = (py * m.heightPixels).roundToInt() - 32
            try { wm.updateViewLayout(v, layout) } catch (_: Exception) {}
        }
    }

    fun click() {
        handler.post {
            if (!smoothReady || clickBusy) return@post
            val m = service.resources.displayMetrics
            val p = Path().apply {
                moveTo(px * m.widthPixels, py * m.heightPixels)
            }
            val desc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, 55L)).build()
            clickBusy = true
            val ok = service.dispatchGesture(desc,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { clickBusy = false }
                    override fun onCancelled(g: GestureDescription?) { clickBusy = false }
                }, handler)
            if (!ok) clickBusy = false
        }
    }

    fun hideCursor() {
        handler.post {
            cursor?.let { safeRemove(it) }
            cursor = null
            smoothReady = false
        }
    }

    fun clear() {
        handler.removeCallbacks(hideToast)
        toast?.let { safeRemove(it) }
        cursor?.let { safeRemove(it) }
        toast = null; cursor = null; smoothReady = false
    }
}