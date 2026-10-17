package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态机合成标志点测试，不替代真机精度验证。 */
class DynamicFingerDetectorTest {
    private fun pose(indexExtended:Boolean=false,middleExtended:Boolean=false,
        deltaX:Float=0f,deltaY:Float=0f,thumbTouchingIndex:Boolean=false
    ):List<HandDetectionAnalyzer.NormalizedPoint> {
        fun point(x:Float,y:Float)=HandDetectionAnalyzer.NormalizedPoint(x,y)
        val p=MutableList(21) { point(.50f,.68f) }
        p[0]=point(.50f,.91f)
        p[5]=point(.35f,.60f)
        p[6]=if(indexExtended) point(.35f,.45f) else point(.42f,.65f)
        p[8]=if(indexExtended) point(.35f,.20f) else point(.50f,.75f)
        p[9]=point(.50f,.58f)
        p[10]=if(middleExtended) point(.50f,.43f) else point(.56f,.65f)
        p[12]=if(middleExtended) point(.50f,.20f) else point(.61f,.77f)
        p[13]=point(.62f,.62f)
        p[14]=point(.63f,.67f)
        p[16]=point(.67f,.79f)
        p[17]=point(.65f,.65f)
        p[18]=point(.67f,.71f)
        p[20]=point(.72f,.82f)
        p[4]=if(thumbTouchingIndex) point(p[8].x,p[8].y) else point(.13f,.89f)
        return p.map { point(it.x+deltaX,it.y+deltaY) }
    }

    private fun detector(hints:MutableList<String> = mutableListOf()) =
        DynamicFingerDetector(hint={hints.add(it)},state={})

    private fun arm(d:DynamicFingerDetector,p:List<HandDetectionAnalyzer.NormalizedPoint>) {
        listOf(100L,220L,340L,440L,560L).forEach { t ->
            assertNull("起手式不能自动触发",d.process(p,t))
        }
    }

    private fun finish(d:DynamicFingerDetector,p:List<HandDetectionAnalyzer.NormalizedPoint>):String? {
        assertNull(d.process(p,640L))
        assertNull(d.process(p,720L))
        return d.process(p,810L)
    }

    @Test fun poseCandidateWakesInferenceAndResetReturnsToOneFps() {
        val d = detector()
        d.reset()
        assertFalse(GestureRuntime.poseInProgress)
        assertNull(d.process(pose(indexExtended = true), 100L))
        assertTrue("检测到候选起手式后必须提高推理帧率", GestureRuntime.poseInProgress)
        assertEquals(68L, RecognitionCadence.intervalMs(false, true, GestureRuntime.poseInProgress))
        d.reset()
        assertFalse(GestureRuntime.poseInProgress)
        assertEquals(1000L, RecognitionCadence.intervalMs(false, true, GestureRuntime.poseInProgress))
    }

    @Test fun fistShowsHintOnlyAfterStableAndThenUp() {
        val hints=mutableListOf<String>()
        val d=detector(hints)
        val fist=pose()
        assertNull(d.process(fist,100L))
        assertNull(d.process(fist,210L))
        assertNull(d.process(fist,330L))
        assertTrue("不能刚发现姿势就弹窗",hints.isEmpty())
        assertNull(d.process(fist,460L))
        assertNull(d.process(fist,570L))
        assertEquals(1,hints.size)
        assertEquals("✊",hints[0])
        assertNull(d.process(fist,610L))
        assertNull(d.process(pose(indexExtended=true),670L))
        assertNull(d.process(pose(indexExtended=true),760L))
        assertEquals("Index_Up",d.process(pose(indexExtended=true),840L))
        assertNull(d.process(pose(indexExtended=true),920L))
    }

    @Test fun indexDownPreserved() {
        val d=detector()
        arm(d,pose(indexExtended=true))
        assertEquals("Index_Down",finish(d,pose()))
    }

    @Test fun twoFingersAreNotDownFlipUntilMoved() {
        val hints=mutableListOf<String>()
        val d=detector(hints)
        val two=pose(indexExtended=true,middleExtended=true)
        arm(d,two)
        assertEquals("✌️",hints.single())
        listOf(640L,720L,810L,900L).forEach { assertNull(d.process(two,it)) }
    }

    @Test fun fourTwoFingerDirectionsNeedStableMovement() {
        for((dx,dy,key) in listOf(
            Triple(-.22f,0f,"Two_Move_Right"),
            Triple(.22f,0f,"Two_Move_Left"),
            Triple(0f,-.22f,"Two_Move_Up"),
            Triple(0f,.22f,"Two_Move_Down")
        )) {
            val d=detector()
            arm(d,pose(indexExtended=true,middleExtended=true))
            assertEquals(key,finish(d,pose(
                indexExtended=true,middleExtended=true,deltaX=dx,deltaY=dy
            )))
        }
    }

    @Test fun TinyMovementOrWrongHandCannotTrigger() {
        val d=detector()
        arm(d,pose(indexExtended=true,middleExtended=true))
        listOf(640L,720L,810L).forEach {
            assertNull(d.process(pose(
                indexExtended=true,middleExtended=true,deltaX=.05f),it))
        }
        assertNull(d.process(pose(indexExtended=true,deltaX=.24f),910L))
    }

    @Test fun briefPoseNotArmed() {
        val hints=mutableListOf<String>()
        val d=detector(hints)
        assertNull(d.process(pose(),100L))
        assertNull(d.process(pose(indexExtended=true),200L))
        assertNull(d.process(pose(indexExtended=true),310L))
        assertTrue(hints.isEmpty())
    }

    @Test fun noObsoleteGestureNames() {
        assertEquals(6,GestureCatalog.known.size)
        assertFalse(GestureCatalog.known.any {
            it.startsWith("Pinch") || it=="Two_Up" || it=="Two_Down"
        })
    }

    @Test fun thumbNearIndexCannotCausePinchDirection() {
        val d=detector()
        val p=pose(indexExtended=true,thumbTouchingIndex=true)
        arm(d,p)
        listOf(640L,730L,840L).forEach { assertNull(d.process(p,it)) }
    }

    /**
     * 手心/手背及斜向镜头的共同难点：x-y 中指尖和掌骨投影几乎重合。
     * 只变 z 号模拟朝/背摄像头的两种方向，均要保持双指识别。
     */
    private fun forwardTwoFingers(depth:Float, deltaX:Float=0f,
                                  deltaY:Float=0f, mirror:Boolean=false
    ):List<HandDetectionAnalyzer.NormalizedPoint> {
        val p=pose(deltaX=deltaX,deltaY=deltaY).toMutableList()
        fun pt(x:Float,y:Float,z:Float=0f)=HandDetectionAnalyzer.NormalizedPoint(x,y,z)
        p[6]=pt(p[5].x,p[5].y,depth*.45f)
        p[8]=pt(p[5].x,p[5].y,depth)
        p[10]=pt(p[9].x,p[9].y,depth*.45f)
        p[12]=pt(p[9].x,p[9].y,depth)
        return if(mirror) p.map { pt(1f-it.x,it.y,it.z) } else p
    }

    @Test fun palmFacingAndBackFacingCameraBothRecognizeTwoFingerMovement() {
        for(depth in listOf(-.38f,.38f)) {
            val hints=mutableListOf<String>()
            val d=detector(hints)
            arm(d,forwardTwoFingers(depth))
            assertEquals(listOf("✌️"),hints)
            assertEquals("Two_Move_Right",finish(d,forwardTwoFingers(depth,deltaX=-.22f)))
        }
    }

    @Test fun mirroredHandAlsoTracksDirectionFromPalmCenter() {
        val d=detector()
        arm(d,forwardTwoFingers(depth=.38f,mirror=true))
        assertEquals("Two_Move_Right",finish(d,
            forwardTwoFingers(depth=.38f,deltaX=.22f,mirror=true)))
    }

    @Test fun diagonalMotionIsNotMistakenForCardinalDirection() {
        val d=detector()
        arm(d,pose(indexExtended=true,middleExtended=true))
        listOf(640L,720L,810L,900L).forEach {
            assertNull(d.process(pose(
                indexExtended=true,middleExtended=true,deltaX=.20f,deltaY=.20f),it))
        }
    }

    @Test fun forwardForeshortenedFingersAreNotAutomaticallyClicked() {
        val d=detector()
        arm(d,forwardTwoFingers(depth=-.38f))
        listOf(640L,720L,810L,900L).forEach {
            assertNull(d.process(forwardTwoFingers(depth=-.38f),it))
        }
    }
}
