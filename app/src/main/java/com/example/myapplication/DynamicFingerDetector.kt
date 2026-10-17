package com.example.myapplication

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 只检测相对运动，不使用“静止某手势持续几帧就执行”的旧逻辑。
 * 两类动作：一/双指屈伸上下翻、单/双指捏合后平移四方向。
 */
class DynamicFingerDetector {
    private enum class Stage { WAIT, TRACK, FIRED }
    private var stage = Stage.WAIT
    private var startAt = 0L
    private var lastAt = 0L
    private var lastFireAt = 0L
    private var stableFrames = 0
    private var heldMode = 0 // 0 flex; 1 thumb-index; 2 thumb-index-middle
    private var baselineIndex = 0f
    private var baselineMiddle = 0f
    private var baselinePalmX = 0f
    private var baselinePalmY = 0f
    private var baselineWidth = .1f
    private var lastName = "None"
    private var releaseAt = 0L

    private fun dist(a: HandDetectionAnalyzer.NormalizedPoint, b: HandDetectionAnalyzer.NormalizedPoint) =
        hypot(a.x - b.x, a.y - b.y)

    private fun palm(p: List<HandDetectionAnalyzer.NormalizedPoint>): Pair<Float, Float> {
        val ids = listOf(0, 5, 9, 13, 17)
        return ids.sumOf { p[it].x.toDouble() }.toFloat()/ids.size to
            ids.sumOf { p[it].y.toDouble() }.toFloat()/ids.size
    }
    private fun palmWidth(p: List<HandDetectionAnalyzer.NormalizedPoint>) =
        dist(p[5], p[17]).coerceAtLeast(.045f)

    /** 以手掌纵轴为参考，屏蔽手掌整块的上下平移。 */
    private fun extension(p: List<HandDetectionAnalyzer.NormalizedPoint>, tip: Int, base: Int, w: Float): Float {
        val ux = p[9].x - p[0].x
        val uy = p[9].y - p[0].y
        val length = hypot(ux, uy).coerceAtLeast(.03f)
        return ((p[tip].x - p[base].x)*ux + (p[tip].y - p[base].y)*uy)/(length*w)
    }

    private fun pinchMode(p: List<HandDetectionAnalyzer.NormalizedPoint>, w: Float): Int {
        val first = dist(p[4],p[8])/w
        val second = dist(p[4],p[12])/w
        return when {
            first < .47f && second < .62f -> 2
            first < .47f -> 1
            else -> 0
        }
    }

    fun process(p: List<HandDetectionAnalyzer.NormalizedPoint>?): String? {
        val now = SystemClock.uptimeMillis()
        if (p == null || p.size < 21) {
            if (stage == Stage.FIRED && releaseAt == 0L) releaseAt = now
            if (stage == Stage.WAIT || now - lastAt > 310L ||
                (releaseAt != 0L && now - releaseAt > 300L)) reset()
            return null
        }
        lastAt = now
        val w = palmWidth(p)
        val mode = pinchMode(p,w)
        val center = palm(p)
        val i = extension(p,8,5,w)
        val m = extension(p,12,9,w)
        if (stage == Stage.FIRED) {
            val samePinch = heldMode > 0 && mode == heldMode
            val flexHeld = heldMode == 0
            // 捏合：松开后才能重触发；屈伸：恢复平稳位置并保持一会才重触发。
            val released = if (heldMode > 0) !samePinch
                else abs(i - baselineIndex) < .18f && abs(m - baselineMiddle) < .23f
            if (released) {
                if (releaseAt == 0L) releaseAt = now
                if (now-releaseAt > 300L) reset()
            } else releaseAt = 0L
            return null
        }

        if (stage == Stage.WAIT) {
            if (now-lastFireAt < 450L) return null
            // 不在手刚进入画面时立即触发。
            if (stableFrames == 0) {
                baselineIndex=i;baselineMiddle=m;baselinePalmX=center.first
                baselinePalmY=center.second;baselineWidth=w;heldMode=mode
                startAt=now;stableFrames=1
                return null
            }
            if (mode != heldMode || abs(i-baselineIndex)>.12f ||
                abs(m-baselineMiddle)>.13f ||
                hypot(center.first-baselinePalmX,center.second-baselinePalmY)>.09f) {
                stableFrames=0
                return null
            }
            stableFrames++
            if (stableFrames>=3 && now-startAt>=170L) {
                stage=Stage.TRACK
                startAt=now
                GestureDiagnostics.state(if (mode>0) "等待捏合移动" else "等待手指翻动")
            }
            return null
        }

        // 超时或捏合解除：必须重新建立稳定基线。
        if (now-startAt>1400L || (heldMode>0 && mode!=heldMode)) {
            reset()
            return null
        }
        var key:String?=null
        if (heldMode>0) {
            // 相机预览为镜像：方向按用户在屏幕看到的方向表示。
            val dx=(baselinePalmX-center.first)/baselineWidth
            val dy=(center.second-baselinePalmY)/baselineWidth
            val threshold=.72f
            if (abs(dx)>threshold || abs(dy)>threshold) {
                val horizontal=abs(dx)>abs(dy)*1.45f
                val vertical=abs(dy)>abs(dx)*1.45f
                val d=if(horizontal) if(dx>0) "Right" else "Left"
                    else if(vertical) if(dy>0) "Down" else "Up"
                    else null
                if(d!=null) key="Pinch${heldMode}_$d"
            }
        } else {
            // index and middle joint extension deltas decide single vs double.
            val di=i-baselineIndex
            val dm=m-baselineMiddle
            val ring=extension(p,16,13,w)
            val pinky=extension(p,20,17,w)
            // Do not classify if full palm is open (four-finger motion).
            val otherExtended=ring>.9f && pinky>.9f
            if(!otherExtended && abs(di)>.58f) {
                val matching=abs(dm)>.44f && di*dm>0 &&
                    abs(di-dm)<.48f
                val isolated=abs(dm)<.28f
                if (matching || isolated) {
                    val f=if(matching) "Two" else "Index"
                    key=f+(if(di>0) "_Up" else "_Down")
                }
            }
        }
        if (key!=null && now-startAt>=100L && now-lastFireAt>=450L) {
            lastName=key
            stage=Stage.FIRED
            releaseAt=0L
            lastFireAt=now
            GestureDiagnostics.state("已执行，等待松开")
            return key
        }
        return null
    }
    fun reset() {
        stage=Stage.WAIT
        stableFrames=0
        releaseAt=0L
        heldMode=0
        startAt=0L
        GestureDiagnostics.state("等待动作")
    }
}
