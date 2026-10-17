package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * One transparent fullscreen accessibility overlay: only redraws Canvas.
 * Does NOT move a WindowManager window on every camera frame.
 */
class AccessibilityHud(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var overlay: OverlayView? = null
    private var failed = false
    private var clickBusy = false
    private var dragActive=false
    private var dragDispatching=false
    private var dragEnding=false
    private var dragStroke: GestureDescription.StrokeDescription?=null
    private var dragX=0f
    private var dragY=0f
    private var pendingX=0f
    private var pendingY=0f

    private fun ensure(): OverlayView? {
        overlay?.let { return it }
        if (failed) return null
        return try {
            val v = OverlayView()
            val layout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "隔空手势提示"
            }
            wm.addView(v, layout)
            overlay = v
            GestureDiagnostics.overlay("悬浮提示已连接")
            v
        } catch (error: Exception) {
            failed = true
            Log.e("AirGestureHud", "Cannot display accessibility overlay", error)
            GestureDiagnostics.overlay("悬浮层错误：${error.javaClass.simpleName}")
            null
        }
    }

    fun message(value: String) {
        main.post {
            if (!GestureRuntime.feedback) return@post
            ensure()?.message(value)
        }
    }

    fun move(x: Float, y: Float) {
        main.post { ensure()?.move(x, y) }
    }

    fun hideCursor() {
        main.post { overlay?.hideCursor() }
    }

    fun click(onResult: (Boolean) -> Unit) {
        main.post {
            val v = overlay
            if (v == null || !v.isCursorReady() || clickBusy) {
                Log.w("AirGestureHud", "Click unavailable: cursor missing or busy")
                GestureDiagnostics.action("点击失败：光标未就绪")
                onResult(false)
                return@post
            }
            val (x, y) = v.cursorOnScreen()
            val path = Path().apply { moveTo(x, y) }
            val desc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 75L))
                .build()
            clickBusy = true
            try {
                val sent = service.dispatchGesture(desc,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            clickBusy = false
                            v.flashClick(true)
                            onResult(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            clickBusy = false
                            v.flashClick(false)
                            onResult(false)
                        }
                    }, main)
                if (!sent) {
                    clickBusy = false
                    v.flashClick(false)
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e("AirGestureHud", "Could not dispatch cursor click", e)
                clickBusy = false
                v.flashClick(false)
                onResult(false)
            }
        }
    }

    /** Android 8+ 连续笔画，模拟手指一直按住屏幕拖动。 */
    fun beginDrag() {
        main.post {
            if (android.os.Build.VERSION.SDK_INT < 26) {
                message("当前系统不支持连续拖动")
                GestureDiagnostics.action("系统不支持拖动")
                return@post
            }
            val view=overlay ?: return@post
            if (!view.isCursorReady() || clickBusy || dragActive) return@post
            val point=view.cursorOnScreen()
            dragX=point.first;dragY=point.second
            pendingX=dragX;pendingY=dragY
            dragActive=true;dragDispatching=false;dragEnding=false;dragStroke=null
            dispatchDrag()
        }
    }

    fun updateDrag() {
        main.post {
            if (!dragActive) return@post
            val point=overlay?.cursorOnScreen() ?: return@post
            pendingX=point.first;pendingY=point.second
            dispatchDrag()
        }
    }

    fun endDrag() {
        main.post {
            if (!dragActive) return@post
            dragEnding=true
            dispatchDrag()
        }
    }

    private fun dispatchDrag() {
        if (!dragActive || dragDispatching) return
        val prev=dragStroke
        val ending=dragEnding
        if(prev!=null && !ending &&
            hypot(pendingX-dragX,pendingY-dragY)<2.5f) return
        val x=if(prev==null) dragX else pendingX
        val y=if(prev==null) dragY else pendingY
        val path=Path().apply {
            moveTo(dragX,dragY)
            lineTo(x,y)
        }
        try {
            val next=if(prev==null) GestureDescription.StrokeDescription(path,0L,40L,true)
                     else prev.continueStroke(path,0L,52L,!ending)
            dragDispatching=true
            val sent=service.dispatchGesture(GestureDescription.Builder()
                .addStroke(next).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        dragDispatching=false
                        dragStroke=next
                        dragX=x;dragY=y
                        if(prev!=null && ending) {
                            dragActive=false
                            dragStroke=null
                            GestureDiagnostics.action("拖动结束")
                        } else dispatchDrag()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        failDrag()
                    }
                }, main)
            if(!sent) failDrag()
        } catch(e: Exception) {
            Log.e("AirGestureHud","拖动失败",e)
            failDrag()
        }
    }

    private fun failDrag() {
        dragActive=false;dragDispatching=false;dragEnding=false;dragStroke=null
        GestureDiagnostics.action("拖动失败：系统中断")
        message("拖动失败，请检查无障碍")
    }

    fun clear() {
        main.post {
            dragActive=false;dragDispatching=false;dragStroke=null;dragEnding=false
            val v = overlay
            overlay = null
            if (v != null) {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
        }
    }

    private inner class OverlayView : View(service) {
        private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 210, 240)
            style = Paint.Style.FILL
        }
        private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 12, 23, 42)
            style = Paint.Style.FILL
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 21, 28, 39)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * resources.displayMetrics.scaledDensity
        }
        private val density = resources.displayMetrics.density
        private var rawX = .5f
        private var rawY = .5f
        private var sx = .5f
        private var sy = .5f
        private var visible = false
        private var initialized = false
        private var animating = false
        private var lastTick = 0L
        private var toast = ""
        private var toastUntil = 0L
        private var flashUntil = 0L
        private var flashSuccess = false

        init {
            setWillNotDraw(false)
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun move(x: Float, y: Float) {
            // Front camera mirrored into screen coordinates.
            rawX = (1f - x).coerceIn(.025f, .975f)
            rawY = y.coerceIn(.03f, .97f)
            if (!initialized) {
                initialized = true
                sx = rawX; sy = rawY
            }
            visible = true
            animateNextFrame()
        }

        fun hideCursor() {
            if (visible) {
                visible = false
                invalidate()
            }
        }

        fun isCursorReady(): Boolean = initialized && visible && width > 0 && height > 0

        fun cursorOnScreen(): Pair<Float, Float> {
            val location = IntArray(2)
            getLocationOnScreen(location)
            return (location[0] + sx * width) to (location[1] + sy * height)
        }

        fun message(value: String) {
            toast = value.take(70)
            toastUntil = SystemClock.uptimeMillis() + 1350L
            invalidate()
            postDelayed({ invalidate() }, 1400L)
        }

        fun flashClick(success: Boolean) {
            flashSuccess = success
            flashUntil = SystemClock.uptimeMillis() + 200L
            invalidate()
            postDelayed({ invalidate() }, 240L)
        }

        private fun animateNextFrame() {
            if (animating) return
            animating = true
            postOnAnimation {
                animating = false
                val now = SystemClock.uptimeMillis()
                val dt = if (lastTick == 0L) 16f else (now - lastTick).coerceIn(1L, 60L).toFloat()
                lastTick = now
                // Time-based exponential smoothing is framerate independent.
                val gain = 1f - exp(-dt / 28f)
                sx += (rawX - sx) * gain
                sy += (rawY - sy) * gain
                invalidate()
                if (visible && hypot(rawX - sx, rawY - sy) > .0007f) animateNextFrame()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            if (visible) {
                val x = sx * width
                val y = sy * height
                val r = 9f * density
                canvas.drawCircle(x + density, y + 2f * density, r + 5f * density, shadow)
                canvas.drawCircle(x, y, r + 1.5f * density, cursorRing)
                canvas.drawCircle(x, y, r * .68f, cursorFill)
                if (now < flashUntil) {
                    val paint = Paint(cursorRing).apply {
                        color = if (flashSuccess) Color.GREEN else Color.RED
                        strokeWidth = 4f * density
                    }
                    canvas.drawCircle(x, y, 18f * density, paint)
                }
            }
            if (toast.isNotEmpty() && now < toastUntil) {
                val widthPx = labelPaint.measureText(toast)
                val cx = width / 2f
                val cy = 88f * density
                val pad = 16f * density
                val bounds = RectF(cx - widthPx / 2f - pad, cy - 24f * density,
                    cx + widthPx / 2f + pad, cy + 14f * density)
                canvas.drawRoundRect(bounds, 15f * density, 15f * density, bg)
                canvas.drawText(toast, cx - widthPx / 2f, cy + 1f * density, labelPaint)
            }
        }
    }
}
