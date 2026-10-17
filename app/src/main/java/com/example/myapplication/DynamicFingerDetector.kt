package com.example.myapplication

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * v1.5: 十二个动作分别有明确起手式。
 * 先显示起手式准备提示，随后检测关节屈伸或捏合后的掌心平移。
 * 使用 21 个 MediaPipe 标志点，不依赖静态手势分类的置信度。
 */
private typealias P = HandDetectionAnalyzer.NormalizedPoint

class DynamicFingerDetector {

    private enum class Phase { IDLE, PREPARING, ARMED, FIRED }
    private data class Frame(
        val index: Float, val middle: Float, val ring: Float, val little: Float,
        val pinch: Int, val centerX: Float, val centerY: Float, val width: Float
    )
    private data class Pose(val key: String, val guidance: String, val mode: Int)

    private var phase = Phase.IDLE
    private var selected: Pose? = null
    private var baseline: Frame? = null
    private var firstAt = 0L
    private var armedAt = 0L
    private var lastSeen = 0L
    private var lastFireAt = 0L
    private var releaseAt = 0L
    private var lastHintAt = 0L
    private var lastHint = ""

    private fun d(a: P, b: P): Float = hypot(a.x-b.x, a.y-b.y)

    private fun angle(a: P, b: P, c: P): Float {
        val ax=a.x-b.x;val ay=a.y-b.y
        val cx=c.x-b.x;val cy=c.y-b.y
        val den=(hypot(ax,ay)*hypot(cx,cy)).coerceAtLeast(.0001f)
        val cosine=((ax*cx+ay*cy)/den).coerceIn(-1f,1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /** 弯曲和伸直都考虑关节角与沿掌心方向的伸展，避免单纯位移难以识别上翻。 */
    private fun finger(p: List<P>, mcp:Int, pip:Int, tip:Int, width:Float):Float {
        val ux=p[9].x-p[0].x
        val uy=p[9].y-p[0].y
        val length=hypot(ux,uy).coerceAtLeast(.04f)
        val projected=((p[tip].x-p[mcp].x)*ux+(p[tip].y-p[mcp].y)*uy)/
            (length*width)
        val ext=(projected/1.30f).coerceIn(0f,1f)
        return .48f*(angle(p[mcp],p[pip],p[tip])/180f)+.52f*ext
    }

    private fun frame(p:List<P>):Frame {
        val w=d(p[5],p[17]).coerceAtLeast(.045f)
        val ids=intArrayOf(0,5,9,13,17)
        val cx=ids.sumOf { p[it].x.toDouble() }.toFloat()/5f
        val cy=ids.sumOf { p[it].y.toDouble() }.toFloat()/5f
        val first=d(p[4],p[8])/w
        val second=d(p[4],p[12])/w
        val pinch=when {
            first<.62f && second<.69f -> 2
            first<.62f && second>=.69f -> 1
            else -> 0
        }
        return Frame(finger(p,5,6,8,w),finger(p,9,10,12,w),
            finger(p,13,14,16,w),finger(p,17,18,20,w),pinch,cx,cy,w)
    }

    /** 四个方位对应不同起手区；镜头左右镜像后再判断位置。 */
    private fun direction(f:Frame):String? {
        val screenX=1f-f.centerX
        val y=f.centerY
        val candidates=listOf(
            "Right" to (.40f-screenX),
            "Left" to (screenX-.60f),
            "Down" to (.40f-y),
            "Up" to (y-.60f)
        )
        return candidates.maxByOrNull { it.second }?.takeIf { it.second >= 0f }?.first
    }

    /** 食指独立上翻要求中指收紧；双指上翻要求两根指尖先保持半弯状态。 */
    private fun pose(f:Frame):Pose? {
        if(f.pinch>0) {
            val dir=direction(f) ?: return null
            val label=if(f.pinch==2) "双指捏合" else "单指捏合"
            val guide=when(dir) {
                "Up" -> "从下侧向上移动"
                "Down" -> "从上侧向下移动"
                "Left" -> "从右侧向左移动"
                else -> "从左侧向右移动"
            }
            return Pose("Pinch${f.pinch}_$dir",guide,f.pinch)
        }
        if(f.ring>.76f && f.little>.76f) return null
        if(f.index>.78f && f.middle>.76f)
            return Pose("Two_Down","同时弯下两指",0)
        if(f.index>.78f && f.middle<.64f)
            return Pose("Index_Down","向掌心弯下食指",0)
        if(f.index<.65f && f.middle in .40f.. .71f)
            return Pose("Two_Up","同时伸直两指",0)
        if(f.index<.65f && f.middle<.40f)
            return Pose("Index_Up","伸直食指",0)
        return null
    }

    private fun notifyPose(p:Pose, now:Long, ready:Boolean) {
        val msg=if(ready) "准备：${GestureCatalog.label(p.key)} · ${p.guidance}"
            else "已检测：${GestureCatalog.label(p.key)} · 请保持"
        if(msg!=lastHint || now-lastHintAt>1900L) {
            ScrollAccessibilityService.getInstance()?.showGestureFeedback(msg)
            GestureDiagnostics.state(msg)
            lastHint=msg
            lastHintAt=now
        }
    }

    fun process(p:List<P>?, now:Long=SystemClock.uptimeMillis()):String? {
        if(p==null || p.size!=21) {
            if(now-lastSeen>360L) clear()
            return null
        }
        lastSeen=now
        val f=frame(p)
        if(phase==Phase.FIRED) {
            val b=baseline
            val pos=selected
            val released=when {
                b==null || pos==null -> true
                pos.mode>0 -> f.pinch!=pos.mode
                pos.key.endsWith("Up") -> f.index<.67f &&
                    (pos.key.startsWith("Index") || f.middle<.70f)
                else -> f.index>.77f &&
                    (pos.key.startsWith("Index") || f.middle>.76f)
            }
            if(released) {
                if(releaseAt==0L) releaseAt=now
                if(now-releaseAt>=220L) clear()
            } else releaseAt=0L
            return null
        }
        if(now-lastFireAt<550L) return null

        val candidate=pose(f)
        if(phase==Phase.IDLE) {
            if(candidate==null) {
                if(f.pinch>0 && now-lastHintAt>1800L) {
                    val msg="检测到捏合：从画面上、下、左、右侧起手"
                    ScrollAccessibilityService.getInstance()?.showGestureFeedback(msg)
                    GestureDiagnostics.state(msg)
                    lastHintAt=now
                }
                return null
            }
            selected=candidate;baseline=f;phase=Phase.PREPARING
            firstAt=now
            notifyPose(candidate,now,false)
            return null
        }
        val b=baseline ?: run { clear();return null }
        val chosen=selected ?: run { clear();return null }
        if(phase==Phase.PREPARING) {
            if(candidate?.key!=chosen.key ||
                hypot(f.centerX-b.centerX,f.centerY-b.centerY)>max(.09f,b.width*.55f)) {
                phase=Phase.IDLE
                return null
            }
            if(now-firstAt>=115L) {
                phase=Phase.ARMED
                armedAt=now
                notifyPose(chosen,now,true)
            }
            return null
        }

        if(now-armedAt>2000L) { clear();return null }
        val key=chosen.key
        var trigger=false
        if(chosen.mode>0) {
            if(f.pinch!=chosen.mode) { clear();return null }
            val dx=(b.centerX-f.centerX)/b.width
            val dy=(f.centerY-b.centerY)/b.width
            val threshold=.31f
            trigger=when {
                key.endsWith("Right") -> dx>threshold && abs(dx)>abs(dy)*1.15f
                key.endsWith("Left") -> dx < -threshold && abs(dx)>abs(dy)*1.15f
                key.endsWith("Down") -> dy>threshold && abs(dy)>abs(dx)*1.15f
                else -> dy < -threshold && abs(dy)>abs(dx)*1.15f
            }
        } else {
            trigger=when(key) {
                "Index_Up" -> f.index>.78f && f.index-b.index>.22f && f.middle<.73f
                "Index_Down" -> f.index<.65f && b.index-f.index>.20f && f.middle<.73f
                "Two_Up" -> f.index>.76f && f.middle>.74f &&
                    f.index-b.index>.18f && f.middle-b.middle>.15f
                "Two_Down" -> f.index<.67f && f.middle<.68f &&
                    b.index-f.index>.18f && b.middle-f.middle>.15f
                else -> false
            }
        }
        if(trigger && now-armedAt>=75L) {
            phase=Phase.FIRED
            lastFireAt=now
            releaseAt=0L
            GestureDiagnostics.state("完成动作：${GestureCatalog.label(key)}")
            return key
        }
        return null
    }

    private fun clear() {
        phase=Phase.IDLE
        selected=null
        baseline=null
        firstAt=0L;armedAt=0L;releaseAt=0L
    }
    fun reset() {
        clear()
        GestureDiagnostics.state("等待起手式")
    }
}
