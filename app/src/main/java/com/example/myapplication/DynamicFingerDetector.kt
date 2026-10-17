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
    private val state: (String) -> Unit = { GestureDiagnostics.state(it) },
    private val trace: (String) -> Unit = { GestureDiagnostics.gestureTrace(it) }
) {
    private enum class Phase { IDLE, CONFIRMING, ARMED, FIRED }
    private enum class Start { FIST, INDEX_DOWN, TWO_MOVE }
    private data class Frame(
        val index: Float, val middle: Float, val ring: Float, val little: Float,
        val x: Float, val y: Float, val palmWidth: Float,
        val indexForward: Boolean, val middleForward: Boolean,
        val ringForward: Boolean, val littleForward: Boolean
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
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var motionPath = 0f
    private var lostTwoFrames = 0
    private var confirmingMisses = 0
    private var candidateMisses = 0

    private val settleMs = 440L
    private val stableFrameCount = 5
    // NPU landmarks jitter and palm-size variation made .55 too strict.
    // Require directional persistence (3 frames + 120 ms) to avoid false scroll.
    private val moveThreshold = .33f

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

    /**
     * MediaPipe z 是相对手腕的归一化深度。双指朝向镜头时二维投影变短，
     * 用 3D PIP 关节直线程度 + 指尖深度变化补救，而非要求指尖必须指向画面上方。
     */
    private fun spatialDistance(a:P,b:P):Float {
        val dx=a.x-b.x; val dy=a.y-b.y; val dz=a.z-b.z
        return kotlin.math.sqrt(dx*dx+dy*dy+dz*dz)
    }
    private fun forwardExtended(p:List<P>,mcp:Int,pip:Int,tip:Int,width:Float):Boolean {
        val depth=kotlin.math.abs(p[tip].z-p[mcp].z)/width
        val a=spatialDistance(p[mcp],p[pip])
        val b=spatialDistance(p[pip],p[tip])
        val straightness=spatialDistance(p[mcp],p[tip])/(a+b).coerceAtLeast(.0001f)
        // 不限制 z 的正负：手心和手背朝镜头时都可能出现相反的深度方向。
        return depth>.40f && straightness>.88f
    }

    private fun frame(p:List<P>):Frame {
        val span=distance(p[5],p[17])
        val wristToKnuckle=distance(p[0],p[9])
        // 手掌转向镜头时横向掌宽会缩短；增加腕->掌骨长度下限。
        val width=kotlin.math.max(span,wristToKnuckle*.72f).coerceAtLeast(.07f)
        val palmIndices=intArrayOf(0,5,9,13,17)
        val centerX=palmIndices.sumOf { p[it].x.toDouble() }.toFloat()/5f
        val centerY=palmIndices.sumOf { p[it].y.toDouble() }.toFloat()/5f
        return Frame(
            extension(p,5,6,8,width),
            extension(p,9,10,12,width),
            extension(p,13,14,16,width),
            extension(p,17,18,20,width),
            centerX,centerY,width,
            forwardExtended(p,5,6,8,width),
            forwardExtended(p,9,10,12,width),
            forwardExtended(p,13,14,16,width),
            forwardExtended(p,17,18,20,width)
        )
    }

    private fun pose(f:Frame): Start? {
        // 先区分握拳、独立食指、双指伸直，不再检测任何捏合。
        val indexOpen=f.index>.76f || f.indexForward
        val middleOpen=f.middle>.74f || f.middleForward
        // NPU points fluctuate around these boundaries. For V-pose only,
        // accept a slightly softer extension threshold; ring/pinky must
        // still be bent, so four open fingers never become a V gesture.
        val twoOpen=(f.index>.66f || f.indexForward) &&
            (f.middle>.65f || f.middleForward)
        val twoOthersClosed=f.ring<.76f && f.little<.76f &&
            !f.ringForward && !f.littleForward
        if(twoOpen && twoOthersClosed) return Start.TWO_MOVE
        val othersClosed=f.ring<.69f && f.little<.69f &&
            !f.ringForward && !f.littleForward
        if(!othersClosed) return null
        return when {
            !indexOpen && f.index<.58f && f.middle<.58f && !f.middleForward -> Start.FIST
            indexOpen && !middleOpen && f.middle<.64f && !f.middleForward -> Start.INDEX_DOWN
            else -> null
        }
    }

    private fun description(s:Start):String = when(s) {
        Start.FIST -> "握拳：准备伸直食指（食指上翻）"
        Start.INDEX_DOWN -> "食指伸直：准备弯下食指（食指下翻）"
        Start.TWO_MOVE -> "食指、中指伸直：准备向上下左右移动手掌"
    }

    fun reset() {
        GestureRuntime.setPoseInProgress(false)
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
        motionPath=0f
        lostTwoFrames=0
        confirmingMisses=0
        candidateMisses=0
    }

    fun clear() {
        reset()
        state("等待起手式")
    }

    private fun candidate(event:String?,now:Long):String? {
        if(event==null) {
            // A tiny NPU landmark wobble may momentarily drop below the
            // displacement/straightness threshold. Keep the same direction
            // for up to two bad samples; never count them as valid frames.
            if(candidateKey!=null && ++candidateMisses<=2) return null
            candidateKey=null
            candidateFrames=0
            candidateMisses=0
            return null
        }
        if(candidateKey!=event) {
            candidateKey=event
            candidateAt=now
            candidateFrames=1
            candidateMisses=0
            return null
        }
        candidateMisses=0
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
            trace("无有效 21 点关键点 · 阶段:${phase.name}")
            if(now-lastSeenAt>330L) clear()
            return null
        }
        lastSeenAt=now
        val current=frame(points)
        val actualPose=pose(current)
        trace("判定:${actualPose?.name ?: "未满足"} · 阶段:${phase.name} · " +
            "食指 ${"%.2f".format(java.util.Locale.US, current.index)} / " +
            "中指 ${"%.2f".format(java.util.Locale.US, current.middle)} · " +
            "无名指 ${"%.2f".format(java.util.Locale.US, current.ring)} / " +
            "小指 ${"%.2f".format(java.util.Locale.US, current.little)}")
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
                // The analyzer now samples at full gesture cadence until
                // we either confirm this pose or reset to waiting mode.
                GestureRuntime.setPoseInProgress(true)
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
            val movingTooFast = hypot(dx,dy)>b.palmWidth *
                (if(s==Start.TWO_MOVE) .52f else .32f)
            if(actualPose!=s || movingTooFast) {
                // A brief NPU landmark misclassification should not force a
                // new 1 FPS standby wakeup for an otherwise stable V gesture.
                if(s==Start.TWO_MOVE && actualPose!=s && !movingTooFast &&
                    ++confirmingMisses<=2) {
                    return null
                }
                reset()
                return null
            }
            confirmingMisses=0
            stableFrames++
            val requiredMs=if(s==Start.TWO_MOVE) 300L else settleMs
            val requiredFrames=if(s==Start.TWO_MOVE) 3 else stableFrameCount
            if(now-startedAt>=requiredMs && stableFrames>=requiredFrames) {
                phase=Phase.ARMED
                armedAt=now
                baseline=current
                smoothedX=current.x
                smoothedY=current.y
                motionPath=0f
                lostTwoFrames=0
                // 悬浮提示只显示手形；动作名称等到真正执行才显示。
                hint(when(s) {
                    Start.FIST -> "✊"
                    Start.INDEX_DOWN -> "☝️"
                    Start.TWO_MOVE -> "✌️"
                })
                state(when(s) {
                    Start.FIST -> "握拳起手式已确认"
                    Start.INDEX_DOWN -> "食指起手式已确认"
                    Start.TWO_MOVE -> "双指起手式已确认"
                })
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
                if((current.index>.77f || current.indexForward) &&
                    (current.index-origin.index>.21f || current.indexForward) &&
                    current.middle<.66f && !current.middleForward &&
                    current.ring<.72f && current.little<.72f)
                    "Index_Up" else null
            Start.INDEX_DOWN ->
                if(current.index<.65f && !current.indexForward &&
                    origin.index-current.index>.21f &&
                    current.middle<.72f && !current.middleForward &&
                    current.ring<.72f && current.little<.72f)
                    "Index_Down" else null
            Start.TWO_MOVE -> {
                // 短暂的手指遮挡不立即丢失准备状态；连续丢失则重新确认。
                if(actualPose!=Start.TWO_MOVE) {
                    lostTwoFrames++
                    if(lostTwoFrames>=5) reset()
                    // Preserve the directional candidate across a few bad
                    // NPU landmark frames rather than clearing it below.
                    return null
                } else {
                    lostTwoFrames=0
                    // 低通跟踪掌心：不用食指/中指的指尖位置判断移动。
                    val nx=smoothedX+(current.x-smoothedX)*.78f
                    val ny=smoothedY+(current.y-smoothedY)*.78f
                    val step=hypot(nx-smoothedX,ny-smoothedY)
                    // Ignore sub-palm-width landmark noise in the traveled
                    // path; otherwise jitter alone drives straightness down.
                    motionPath+=(step-current.palmWidth*.045f).coerceAtLeast(0f)
                    smoothedX=nx;smoothedY=ny
                    val dx=(origin.x-smoothedX)/origin.palmWidth
                    val dy=(smoothedY-origin.y)/origin.palmWidth
                    val net=hypot(dx,dy)
                    val travel=motionPath/origin.palmWidth
                    // Direction must remain predominantly cardinal; static
                    // V or diagonal movement must not trigger scrolling.
                    val straight=net/travel.coerceAtLeast(.0001f)
                    trace("双指已确认 · 位移 x=${"%.2f".format(java.util.Locale.US, dx)} " +
                        "y=${"%.2f".format(java.util.Locale.US, dy)} " +
                        "净位移=${"%.2f".format(java.util.Locale.US, net)} / $moveThreshold " +
                        "直线度=${"%.2f".format(java.util.Locale.US, straight)}")
                    when {
                        net<moveThreshold || straight<.72f -> null
                        abs(dx)>abs(dy)*1.25f ->
                            if(dx>0) "Two_Move_Right" else "Two_Move_Left"
                        abs(dy)>abs(dx)*1.25f ->
                            if(dy>0) "Two_Move_Down" else "Two_Move_Up"
                        else -> null
                    }
                }
            }
        }
        return candidate(event,now)
    }
}
