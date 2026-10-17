package com.example.myapplication

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * v1.6 动态检测：
 *  - 握拳 -> 伸一指/伸两指：上翻（同一起手式，完成动作时才区分）
 *  - 先伸一指/两指 -> 弯曲：下翻（保留 v1.5 起手式）
 *  - 任意画面位置捏合 -> 掌心上下左右移动（不再要求屏幕边缘）
 * 静止姿势只出现准备提示，绝不执行操作。
 */
private typealias P = HandDetectionAnalyzer.NormalizedPoint

class DynamicFingerDetector(
    private val hint: (String) -> Unit = {
        ScrollAccessibilityService.getInstance()?.showGestureFeedback(it)
    },
    private val state: (String) -> Unit = { GestureDiagnostics.state(it) }
) {
    private enum class Phase { IDLE, PREPARING, ARMED, FIRED }
    private enum class Start { FIST, INDEX_DOWN, TWO_DOWN, PINCH_ONE, PINCH_TWO }
    private data class Frame(
        val index: Float, val middle: Float, val ring: Float, val little: Float,
        val pinch: Int, val x: Float, val y: Float, val palmWidth: Float
    )

    private var phase = Phase.IDLE
    private var start: Start? = null
    private var baseline: Frame? = null
    private var startedAt = 0L
    private var armedAt = 0L
    private var lastSeenAt = 0L
    private var firedAt = -10_000L
    private var releaseAt = 0L
    private var candidateAction: String? = null
    private var candidateAt = 0L
    private var candidateFrames = 0
    private var lastMessage = ""

    private fun distance(a: P,b: P) = hypot(a.x-b.x,a.y-b.y)

    private fun angle(a: P,b: P,c: P):Float {
        val abx=a.x-b.x;val aby=a.y-b.y
        val cbx=c.x-b.x;val cby=c.y-b.y
        val den=(hypot(abx,aby)*hypot(cbx,cby)).coerceAtLeast(.0001f)
        return Math.toDegrees(acos(((abx*cbx+aby*cby)/den).coerceIn(-1f,1f)).toDouble()).toFloat()
    }

    private fun extension(p:List<P>,mcp:Int,pip:Int,tip:Int,w:Float):Float {
        val ax=p[9].x-p[0].x; val ay=p[9].y-p[0].y
        val len=hypot(ax,ay).coerceAtLeast(.04f)
        val projection=((p[tip].x-p[mcp].x)*ax+
            (p[tip].y-p[mcp].y)*ay)/(len*w)
        val normalized=(projection/1.30f).coerceIn(0f,1f)
        return .48f*(angle(p[mcp],p[pip],p[tip])/180f)+.52f*normalized
    }

    private fun read(p:List<P>):Frame {
        val w=distance(p[5],p[17]).coerceAtLeast(.045f)
        val ids=intArrayOf(0,5,9,13,17)
        val x=ids.sumOf { p[it].x.toDouble() }.toFloat()/5f
        val y=ids.sumOf { p[it].y.toDouble() }.toFloat()/5f
        val index=extension(p,5,6,8,w)
        val middle=extension(p,9,10,12,w)
        val ring=extension(p,13,14,16,w)
        val little=extension(p,17,18,20,w)

        val thumbIndex=distance(p[4],p[8])/w
        val thumbMiddle=distance(p[4],p[12])/w
        val fist=index<.58f && middle<.58f && ring<.68f && little<.68f
        // 拳头可能使拇指贴近食指：明确优先归类握拳。
        val pinch=when {
            fist -> 0
            thumbIndex<.72f && thumbMiddle<.79f -> 2
            thumbIndex<.72f -> 1
            else -> 0
        }
        return Frame(index,middle,ring,little,pinch,x,y,w)
    }

    /** 分类的是五种起手式，而不是十二个尚未发生的方向动作。 */
    private fun startingPose(f:Frame):Start? {
        val fist=f.index<.58f && f.middle<.58f &&
            f.ring<.68f && f.little<.68f
        if(fist) return Start.FIST
        if(f.pinch==2) return Start.PINCH_TWO
        if(f.pinch==1) return Start.PINCH_ONE
        if(f.ring>.77f && f.little>.77f) return null
        if(f.index>.78f && f.middle>.76f) return Start.TWO_DOWN
        if(f.index>.78f && f.middle<.64f) return Start.INDEX_DOWN
        return null
    }

    private fun name(s:Start):String = when(s) {
        Start.FIST -> "握拳起手：伸直食指为单指上翻；同时伸直食指和中指为双指上翻"
        Start.INDEX_DOWN -> "食指下翻起手：伸直食指后向掌心弯下"
        Start.TWO_DOWN -> "双指下翻起手：伸直食指和中指后同时弯下"
        Start.PINCH_ONE -> "单指捏合起手：捏住拇指与食指，再向上下左右移动"
        Start.PINCH_TWO -> "双指捏合起手：三指指尖汇聚，再向上下左右移动"
    }

    private fun show(text:String) {
        if(lastMessage==text) return
        lastMessage=text
        hint(text)
        state(text)
    }

    /**
     * 传入时间便于单元测试；普通运行使用 monotonic clock，丢帧不触发动作。
     */
    fun process(p:List<P>?,now:Long=SystemClock.uptimeMillis()):String? {
        if(p==null || p.size!=21) {
            if(now-lastSeenAt>320L) clear()
            return null
        }
        lastSeenAt=now
        val f=read(p)
        val pose=startingPose(f)
        if(phase==Phase.FIRED) {
            val released=when(start) {
                Start.PINCH_ONE,Start.PINCH_TWO -> f.pinch==0
                Start.FIST -> pose==Start.FIST
                Start.INDEX_DOWN -> pose==Start.INDEX_DOWN
                Start.TWO_DOWN -> pose==Start.TWO_DOWN
                null -> true
            }
            if(released) {
                if(releaseAt==0L) releaseAt=now
                if(now-releaseAt>=220L) clear()
            } else releaseAt=0L
            return null
        }
        if(now-firedAt<470L) return null

        if(phase==Phase.IDLE) {
            if(pose!=null) {
                start=pose
                baseline=f
                startedAt=now
                phase=Phase.PREPARING
                show("识别到起手式："+name(pose))
            }
            return null
        }

        val current=start ?: run { clear();return null }
        val base=baseline ?: run { clear();return null }
        if(phase==Phase.PREPARING) {
            if(pose!=current) { clear(); return null }
            // 只在最初几帧检查稳定，不要求捏合时手掌钉在原地。
            if(now-startedAt>=125L) {
                armedAt=now
                phase=Phase.ARMED
                baseline=f // 以完成准备时的掌心为移动起点
                show("已准备："+name(current))
            }
            return null
        }
        if(now-armedAt>1800L) { clear(); return null }

        val b=baseline ?: return null
        var next:String?=null
        when(current) {
            Start.FIST -> {
                // 同一握拳起手；双指优先，短暂等待中指伸直防止抢识别。
                val indexUp=f.index>.73f && f.index-b.index>.19f
                val middleUp=f.middle>.71f && f.middle-b.middle>.17f
                if(indexUp && middleUp) next="Two_Up"
                else if(indexUp && f.middle<.71f) next="Index_Up"
            }
            Start.INDEX_DOWN -> {
                if(f.index<.65f && b.index-f.index>.20f && f.middle<.73f)
                    next="Index_Down"
            }
            Start.TWO_DOWN -> {
                if(f.index<.67f && f.middle<.68f &&
                    b.index-f.index>.18f && b.middle-f.middle>.15f)
                    next="Two_Down"
            }
            Start.PINCH_ONE,Start.PINCH_TWO -> {
                val desired=if(current==Start.PINCH_TWO) 2 else 1
                if(f.pinch==0) { clear(); return null }
                // 双指捏合允许一帧误判为单指，单指期间升级成双指则重新起手。
                if(desired==1 && f.pinch==2) { clear(); return null }
                val dx=(b.x-f.x)/b.palmWidth
                val dy=(f.y-b.y)/b.palmWidth
                val threshold=.33f
                val direction=when {
                    abs(dx)>threshold && abs(dx)>abs(dy)*1.18f ->
                        if(dx>0) "Right" else "Left"
                    abs(dy)>threshold && abs(dy)>abs(dx)*1.18f ->
                        if(dy>0) "Down" else "Up"
                    else -> null
                }
                if(direction!=null) next="Pinch${desired}_$direction"
            }
        }

        if(next==null) {
            candidateAction=null
            candidateFrames=0
            return null
        }
        if(candidateAction!=next) {
            candidateAction=next
            candidateAt=now
            candidateFrames=1
            return null
        }
        candidateFrames++
        val needed=if(current==Start.FIST && next=="Index_Up") 165L else 75L
        if(candidateFrames>=2 && now-candidateAt>=needed && now-armedAt>=85L) {
            phase=Phase.FIRED
            firedAt=now
            releaseAt=0L
            show("动作完成："+GestureCatalog.label(next))
            return next
        }
        return null
    }

    private fun clear() {
        phase=Phase.IDLE
        start=null
        baseline=null
        startedAt=0L
        armedAt=0L
        releaseAt=0L
        candidateAction=null
        candidateFrames=0
        lastMessage=""
    }
    fun reset() {
        clear()
        state("等待起手式")
    }
}
