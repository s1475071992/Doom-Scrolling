package com.example.myapplication

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * 六种动态动作、三种互斥起手式：
 * 1. 握拳 -> 只伸直食指：食指上翻。
 * 2. 食指伸直、其他三指收拢 -> 弯下食指：食指下翻。
 * 3. 食指＋中指伸直、另外两指收拢 -> 整只手移动：上下左右四方向。
 *
 * 先稳定保持起手式，再移动。静态姿势绝不直接执行。
 * 空鼠有自己独立的捏合检测，不经过本类。
 */
private typealias P = HandDetectionAnalyzer.NormalizedPoint

class DynamicFingerDetector(
    private val hint: (String) -> Unit = {
        ScrollAccessibilityService.getInstance()?.showGestureFeedback(it)
    },
    private val state: (String) -> Unit = { GestureDiagnostics.state(it) }
) {
    private enum class Phase { IDLE, CONFIRMING, ARMED, FIRED }
    private enum class Start { FIST, INDEX_DOWN, TWO_MOVE }
    private data class Frame(
        val index: Float, val middle: Float, val ring: Float, val little: Float,
        val x: Float, val y: Float, val palmWidth: Float
    )

    private var phase = Phase.IDLE
    private var start: Start? = null
    private var baseline: Frame? = null
    private var startedAt = 0L
    private var armedAt = 0L
    private var lastSeenAt = 0L
    private var lastFireAt = -10_000L
    private var releasedAt = 0L
    private var stableFrames = 0
    private var candidateKey: String? = null
    private var candidateAt = 0L
    private var candidateFrames = 0

    private val settleMs = 440L
    private val stableFrameCount = 5
    private val moveThreshold = .60f // 掌宽归一化，拒绝轻微晃动

    private fun distance(a: P, b: P) = hypot(a.x-b.x, a.y-b.y)

    private fun jointAngle(a: P,b: P,c: P): Float {
        val ax=a.x-b.x; val ay=a.y-b.y
        val cx=c.x-b.x; val cy=c.y-b.y
        val den=(hypot(ax,ay)*hypot(cx,cy)).coerceAtLeast(.0001f)
        return Math.toDegrees(
            acos(((ax*cx+ay*cy)/den).coerceIn(-1f,1f)).toDouble()
        ).toFloat()
    }

    private fun extension(p:List<P>,mcp:Int,pip:Int,tip:Int,w:Float):Float {
        val ux=p[9].x-p[0].x
        val uy=p[9].y-p[0].y
        val length=hypot(ux,uy).coerceAtLeast(.04f)
        val projected=((p[tip].x-p[mcp].x)*ux+
            (p[tip].y-p[mcp].y)*uy)/(length*w)
        return .48f*(jointAngle(p[mcp],p[pip],p[tip])/180f) +
            .52f*(projected/1.30f).coerceIn(0f,1f)
    }

    private fun frame(p:List<P>):Frame {
        val width=distance(p[5],p[17]).coerceAtLeast(.045f)
        val palmIndices=intArrayOf(0,5,9,13,17)
        val centerX=palmIndices.sumOf { p[it].x.toDouble() }.toFloat()/5f
        val centerY=palmIndices.sumOf { p[it].y.toDouble() }.toFloat()/5f
        return Frame(
            extension(p,5,6,8,width),
            extension(p,9,10,12,width),
            extension(p,13,14,16,width),
            extension(p,17,18,20,width),
            centerX,centerY,width
        )
    }

    private fun pose(f:Frame): Start? {
        // 先区分握拳、独立食指、双指伸直，不再检测任何捏合。
        val othersClosed=f.ring<.69f && f.little<.69f
        if(!othersClosed) return null
        return when {
            f.index<.58f && f.middle<.58f -> Start.FIST
            f.index>.78f && f.middle<.64f -> Start.INDEX_DOWN
            f.index>.78f && f.middle>.76f -> Start.TWO_MOVE
            else -> null
        }
    }

    private fun description(s:Start):String = when(s) {
        Start.FIST -> "握拳：准备伸直食指（食指上翻）"
        Start.INDEX_DOWN -> "食指伸直：准备弯下食指（食指下翻）"
        Start.TWO_MOVE -> "食指、中指伸直：准备向上下左右移动手掌"
    }

    fun reset() {
        phase=Phase.IDLE
        start=null
        baseline=null
        startedAt=0L
        armedAt=0L
        releasedAt=0L
        stableFrames=0
        candidateKey=null
        candidateFrames=0
        candidateAt=0L
    }

    fun clear() {
        reset()
        state("等待起手式")
    }

    private fun candidate(event:String?,now:Long):String? {
        if(event==null) {
            candidateKey=null
            candidateFrames=0
            return null
        }
        if(candidateKey!=event) {
            candidateKey=event
            candidateAt=now
            candidateFrames=1
            return null
        }
        candidateFrames++
        if(candidateFrames<3 || now-candidateAt<120L) return null
        phase=Phase.FIRED
        lastFireAt=now
        releasedAt=0L
        state("动作完成："+GestureCatalog.label(event))
        return event
    }

    /** 时间可注入，便于自动测试逐帧轨迹。 */
    fun process(points:List<P>?,now:Long=SystemClock.uptimeMillis()):String? {
        if(points==null || points.size!=21) {
            if(now-lastSeenAt>330L) clear()
            return null
        }
        lastSeenAt=now
        val current=frame(points)
        val actualPose=pose(current)
        if(phase==Phase.FIRED) {
            // 同一次保持姿势/移动只能触发一次；需离开原起手状态才可重新准备。
            val off=when(start) {
                Start.FIST -> actualPose==Start.FIST
                Start.INDEX_DOWN -> actualPose==Start.INDEX_DOWN
                Start.TWO_MOVE -> actualPose!=Start.TWO_MOVE
                null -> true
            }
            if(off) {
                if(releasedAt==0L) releasedAt=now
                if(now-releasedAt>=370L) clear()
            } else releasedAt=0L
            return null
        }
        if(now-lastFireAt<550L) return null

        if(phase==Phase.IDLE) {
            if(actualPose!=null) {
                start=actualPose
                baseline=current
                startedAt=now
                stableFrames=1
                phase=Phase.CONFIRMING
                // 减少误触：候选起手式不再立即弹窗。
            }
            return null
        }
        val s=start ?: run { clear();return null }
        val b=baseline ?: run { clear();return null }
        if(phase==Phase.CONFIRMING) {
            val dx=current.x-b.x
            val dy=current.y-b.y
            // 候选期必须手形持续一致、整只手大体保持稳定。
            if(actualPose!=s || hypot(dx,dy)>b.palmWidth*.32f) {
                reset()
                return null
            }
            stableFrames++
            if(now-startedAt>=settleMs && stableFrames>=stableFrameCount) {
                phase=Phase.ARMED
                armedAt=now
                baseline=current
                val msg="起手式已确认："+description(s)
                hint(msg)
                state(msg)
            }
            return null
        }
        if(now-armedAt>2600L) {
            // 超时后需要重新确认，不允许持续静止无限等待触发。
            reset()
            return null
        }
        val origin=baseline ?: return null
        val event=when(s) {
            Start.FIST ->
                if(current.index>.77f && current.index-origin.index>.21f &&
                    current.middle<.66f && current.ring<.72f && current.little<.72f)
                    "Index_Up" else null
            Start.INDEX_DOWN ->
                if(current.index<.65f && origin.index-current.index>.21f &&
                    current.middle<.72f && current.ring<.72f && current.little<.72f)
                    "Index_Down" else null
            Start.TWO_MOVE -> {
                // 非双指伸直状态时禁止判断方向，避免弯手指误认为整掌移动。
                if(actualPose!=Start.TWO_MOVE) null else {
                    val dx=(origin.x-current.x)/origin.palmWidth // 镜像前置摄像头
                    val dy=(current.y-origin.y)/origin.palmWidth
                    when {
                        abs(dx)>=moveThreshold && abs(dx)>abs(dy)*1.30f ->
                            if(dx>0) "Two_Move_Right" else "Two_Move_Left"
                        abs(dy)>=moveThreshold && abs(dy)>abs(dx)*1.30f ->
                            if(dy>0) "Two_Move_Down" else "Two_Move_Up"
                        else -> null
                    }
                }
            }
        }
        return candidate(event,now)
    }
}
