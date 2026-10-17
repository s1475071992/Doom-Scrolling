package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Uses Doom-Scrolling's ORIGINAL accessibility service instance.
 * No accessibility registration/permission code is imported from GestureControl.
 */
object GestureActionRouter {
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    fun clickCursor() {
        val service = ScrollAccessibilityService.getInstance() ?: return
        // Async dispatch callback reports success/failure; do not pre-announce success.
        service.clickCursor()
    }

    fun dispatch(name: String) {
        handler.post {
            val service = ScrollAccessibilityService.getInstance() ?: return@post
            if (!ScrollAccessibilityService.isEnabled()) return@post
            val action = GestureActionMap.actionFor(service, name)
            val description = GestureCatalog.labels[name] ?: name
            GestureDiagnostics.action(if (action == GestureActionMap.NONE)
                "$name: unmapped" else "$name → $action")
            // Show feedback for recognised but UNMAPPED gestures too, so the
            // user can determine whether recognition or mapping is at fault.
            service.showGestureFeedback(if (action == GestureActionMap.NONE)
                "$description · 未映射" else "$description → $action")
            when (action) {
                GestureActionMap.NONE -> Unit
                GestureActionMap.SCROLL_DOWN -> ScrollAccessibilityService.performScroll(850)
                GestureActionMap.SCROLL_UP -> ScrollAccessibilityService.performScroll(-850)
                GestureActionMap.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                GestureActionMap.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                GestureActionMap.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                GestureActionMap.VOLUME_UP -> volume(service, AudioManager.ADJUST_RAISE)
                GestureActionMap.VOLUME_DOWN -> volume(service, AudioManager.ADJUST_LOWER)
                GestureActionMap.TAP -> tap(service, false)
                GestureActionMap.DOUBLE_TAP -> tap(service, true)
                GestureActionMap.SWIPE_UP -> swipe(service, .50f, .80f, .50f, .20f)
                GestureActionMap.SWIPE_DOWN -> swipe(service, .50f, .20f, .50f, .80f)
                GestureActionMap.SWIPE_LEFT -> swipe(service, .85f, .50f, .15f, .50f)
                GestureActionMap.SWIPE_RIGHT -> swipe(service, .15f, .50f, .85f, .50f)
                else -> Log.w("GestureActionRouter", "Unknown action: $action")
            }
        }
    }
    private fun volume(service: ScrollAccessibilityService, change: Int) {
        val mgr = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mgr.adjustStreamVolume(AudioManager.STREAM_MUSIC, change, AudioManager.FLAG_SHOW_UI)
    }
    private fun tap(service: ScrollAccessibilityService, double: Boolean) {
        val m = service.resources.displayMetrics
        val p = Path().apply { moveTo(m.widthPixels * .5f, m.heightPixels * .5f) }
        val b = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 55L))
        if (double) b.addStroke(GestureDescription.StrokeDescription(p, 140L, 55L))
        submit(service, b.build())
    }
    private fun swipe(service: ScrollAccessibilityService,
                      sx: Float, sy: Float, ex: Float, ey: Float) {
        val m = service.resources.displayMetrics
        val p = Path().apply {
            moveTo(m.widthPixels * sx, m.heightPixels * sy)
            lineTo(m.widthPixels * ex, m.heightPixels * ey)
        }
        submit(service, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 320L)).build())
    }
    private fun submit(service: ScrollAccessibilityService, gesture: GestureDescription) {
        if (busy) return
        busy = true
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { busy = false }
            override fun onCancelled(gestureDescription: GestureDescription?) { busy = false }
        }, handler)
        if (!ok) busy = false
    }
}