package com.example.myapplication

import android.os.SystemClock
import kotlin.math.hypot

/**
 * 动态手势与空鼠互斥。静止手形不触发任何操作。
 * 空鼠：捏合锁定当前光标，短捏合松开=单击，捏合移动=连续拖动。
 */
class GestureScrollController {
    enum class GestureAction { SCROLL_DOWN, SCROLL_UP, PAUSE, RESUME, NONE }
    data class GestureResult(val action: GestureAction = GestureAction.NONE, val scrollAmount: Int = 0)
    private val dynamic = DynamicFingerDetector()
    private var wasMouse = false
    private var pointerX = .5f
    private var pointerY = .5f
    private var downAt = 0L
    private var startPalmX = 0f
    private var startPalmY = 0f
    private var startX = .5f
    private var startY = .5f
    private var dragging = false
    private var lastClickAt = 0L
    private var pinchMode = 0
    private var lastDir = ""
    private var lostAt = 0L

    fun processGesture(g: HandDetectionAnalyzer.HandGesture?): GestureResult {
        val isMouse = GestureRuntime.airMouse
        if (wasMouse != isMouse) {
            if (dragging) ScrollAccessibilityService.getInstance()?.endCursorDrag()
            resetMouse()
            dynamic.reset()
            wasMouse = isMouse
            if (!isMouse) ScrollAccessibilityService.getInstance()?.hideCursor()
        }
        if (isMouse) processMouse(g)
        else {
            val event = dynamic.process(g?.landmarks)
            if (event != null) GestureActionRouter.dispatch(event)
        }
        return GestureResult()
    }

    private fun palm(g: HandDetectionAnalyzer.HandGesture): Pair<Float, Float> {
        val p = g.landmarks
        val ids = intArrayOf(0, 5, 9, 13, 17)
        return ids.sumOf { p[it].x.toDouble() }.toFloat()/5f to
            ids.sumOf { p[it].y.toDouble() }.toFloat()/5f
    }
    private fun mode(g: HandDetectionAnalyzer.HandGesture): Int {
        val p = g.landmarks
        if (p.size<21) return 0
        fun d(i:Int,j:Int) = hypot(p[i].x-p[j].x,p[i].y-p[j].y)
        val w=d(5,17).coerceAtLeast(.045f)
        return when {
            d(4,8)/w<.47f && d(4,12)/w<.62f -> 2
            d(4,8)/w<.47f -> 1
            else -> 0
        }
    }
    private fun processMouse(g: HandDetectionAnalyzer.HandGesture?) {
        GestureDiagnostics.state(if (dragging) "正在拖动" else "空鼠已开启")
        val svc = ScrollAccessibilityService.getInstance()
        if (svc == null || !ScrollAccessibilityService.isEnabled()) {
            GestureDiagnostics.action("无障碍未连接")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (g==null || g.landmarks.size<21 || g.indexFingerPosition==null) {
            if (lostAt==0L) lostAt=now
            // 短暂丢帧不立即结束拖动，避免一帧检测失败误点击。
            if (now-lostAt>300L) {
                if (dragging) svc.endCursorDrag()
                resetMouse()
                svc.hideCursor()
            }
            return
        }
        lostAt=0L
        val center=palm(g)
        val m=mode(g)
        val index=g.indexFingerPosition
        if (downAt==0L && m==0) {
            pointerX=index.x
            pointerY=index.y
            svc.moveCursor(pointerX,pointerY)
            return
        }
        if (downAt==0L && m>0) {
            downAt=now
            pinchMode=m
            startPalmX=center.first;startPalmY=center.second
            // 使用捏合之前的光标位置，避免食指在合拢时突然跳动。
            startX=pointerX;startY=pointerY
            svc.showGestureFeedback(if(m==2) "双指捏合：松开点击，移动拖动"
                                    else "单指捏合：松开点击，移动拖动")
            return
        }
        if (downAt==0L) return
        // 扩大松开阈值，迟滞防止捏合状态来回振荡。
        val release = if(pinchMode==2) m!=2 &&
            g.pinchRatio>.72f else g.pinchRatio>.76f
        if (release) {
            if (dragging) {
                svc.endCursorDrag()
                GestureDiagnostics.action("结束拖动")
                svc.showGestureFeedback("拖动结束")
            } else if(now-downAt>=105L && now-downAt<=1000L && now-lastClickAt>400L) {
                lastClickAt=now
                GestureActionRouter.clickCursor()
            }
            resetMouse()
            return
        }
        if (m==0 && !dragging) return
        // 用掌心整体运动更新光标；捏合期间绝不重新采用弯曲食指指尖。
        val targetX=(startX+(center.first-startPalmX)*1.3f).coerceIn(.025f,.975f)
        val targetY=(startY+(center.second-startPalmY)*1.3f).coerceIn(.03f,.97f)
        val displacement=hypot(targetX-startX,targetY-startY)
        if (!dragging && displacement>.065f && now-downAt>=105L) {
            dragging=true
            svc.beginCursorDrag()
            GestureDiagnostics.action(if(pinchMode==2) "开始双指捏合拖动" else "开始单指捏合拖动")
            svc.showGestureFeedback(if(pinchMode==2) "双指捏合：开始拖动" else "单指捏合：开始拖动")
        }
        if(dragging) {
            svc.moveCursor(targetX,targetY)
            svc.updateCursorDrag()
            val dx=targetX-startX; val dy=targetY-startY
            val dir=if(kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.35f)
                if(dx>0) "向左" else "向右"
                else if(kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.35f)
                    if(dy>0) "向下" else "向上"
                else "自由"
            if(dir!=lastDir) {
                lastDir=dir
                val msg=(if(pinchMode==2) "双指" else "单指")+"捏合"+dir+"拖动"
                svc.showGestureFeedback(msg)
                GestureDiagnostics.action(msg)
            }
        }
    }

    private fun resetMouse() {
        downAt=0L; pinchMode=0; dragging=false;lastDir="";lostAt=0L
    }
    fun reset() {
        dynamic.reset()
        if(dragging) ScrollAccessibilityService.getInstance()?.endCursorDrag()
        resetMouse()
    }
    fun isPaused(): Boolean = false
}
