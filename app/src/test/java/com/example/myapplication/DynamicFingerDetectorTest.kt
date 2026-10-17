package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic landmark traces validate the state transitions, not MediaPipe
 * confidence or physical-camera accuracy. Those still require device testing.
 */
class DynamicFingerDetectorTest {


    private fun pose(
        indexExtended: Boolean = false,
        middleExtended: Boolean = false,
        pinch: Int = 0,
        deltaX: Float = 0f,
        deltaY: Float = 0f
    ): List<HandDetectionAnalyzer.NormalizedPoint> {
        val p = MutableList(21) { HandDetectionAnalyzer.NormalizedPoint(.50f,.68f) }
        p[0] = HandDetectionAnalyzer.NormalizedPoint(.50f,.91f)
        p[5] = HandDetectionAnalyzer.NormalizedPoint(.35f,.60f)
        p[6] = if(indexExtended) HandDetectionAnalyzer.NormalizedPoint(.35f,.45f) else HandDetectionAnalyzer.NormalizedPoint(.42f,.65f)
        p[8] = if(indexExtended) HandDetectionAnalyzer.NormalizedPoint(.35f,.20f) else HandDetectionAnalyzer.NormalizedPoint(.50f,.75f)
        p[9] = HandDetectionAnalyzer.NormalizedPoint(.50f,.58f)
        p[10] = if(middleExtended) HandDetectionAnalyzer.NormalizedPoint(.50f,.43f) else HandDetectionAnalyzer.NormalizedPoint(.56f,.65f)
        p[12] = if(middleExtended) HandDetectionAnalyzer.NormalizedPoint(.50f,.20f) else HandDetectionAnalyzer.NormalizedPoint(.61f,.77f)
        p[13] = HandDetectionAnalyzer.NormalizedPoint(.62f,.62f)
        p[14] = HandDetectionAnalyzer.NormalizedPoint(.63f,.67f)
        p[16] = HandDetectionAnalyzer.NormalizedPoint(.67f,.79f)
        p[17] = HandDetectionAnalyzer.NormalizedPoint(.65f,.65f)
        p[18] = HandDetectionAnalyzer.NormalizedPoint(.67f,.71f)
        p[20] = HandDetectionAnalyzer.NormalizedPoint(.72f,.82f)
        p[4] = when(pinch) {
            1 -> HandDetectionAnalyzer.NormalizedPoint(p[8].x,p[8].y)
            2 -> HandDetectionAnalyzer.NormalizedPoint(.425f,.20f)
            else -> HandDetectionAnalyzer.NormalizedPoint(.13f,.89f)
        }
        return p.map { HandDetectionAnalyzer.NormalizedPoint(it.x+deltaX,it.y+deltaY) }
    }

    private fun detector(hints:MutableList<String> = mutableListOf()) =
        DynamicFingerDetector(hint={ hints.add(it) },state={})

    @Test fun fistDoesNotFireUntilOneFingerStraightens() {
        val hints=mutableListOf<String>()
        val d=detector(hints)
        assertNull(d.process(pose(),100))
        assertNull(d.process(pose(),260))
        assertNull(d.process(pose(),350))
        assertEquals(2,hints.size)
        assertNull(d.process(pose(indexExtended=true),390))
        assertEquals("Index_Up",d.process(pose(indexExtended=true),570))
    }

    @Test fun fistThenTwoFingersUpHasPriority() {
        val d=detector()
        d.process(pose(),100)
        d.process(pose(),260)
        assertNull(d.process(pose(indexExtended=true),350))
        assertNull(d.process(pose(indexExtended=true,middleExtended=true),410))
        assertEquals("Two_Up",d.process(pose(indexExtended=true,middleExtended=true),510))
    }

    @Test fun downStartingPosesUnchanged() {
        val single=detector()
        single.process(pose(indexExtended=true),100)
        single.process(pose(indexExtended=true),250)
        assertNull(single.process(pose(),350))
        assertEquals("Index_Down",single.process(pose(),455))

        val double=detector()
        double.process(pose(indexExtended=true,middleExtended=true),100)
        double.process(pose(indexExtended=true,middleExtended=true),250)
        assertNull(double.process(pose(),350))
        assertEquals("Two_Down",double.process(pose(),455))
    }

    @Test fun singlePinchCanMoveFromAnywhereAndDoesNotFireWhenHeldStill() {
        val d=detector()
        val p=pose(indexExtended=true,pinch=1)
        d.process(p,100)
        d.process(p,260)
        assertNull(d.process(p,370))
        assertNull(d.process(pose(indexExtended=true,pinch=1,deltaX=.13f),400))
        assertEquals("Pinch1_Left",d.process(
            pose(indexExtended=true,pinch=1,deltaX=.13f),520))
        assertNull(d.process(pose(indexExtended=true,pinch=1,deltaX=.13f),600))
    }

    @Test fun doublePinchMovesUpWithoutScreenEdgeStart() {
        val d=detector()
        d.process(pose(indexExtended=true,middleExtended=true,pinch=2),100)
        d.process(pose(indexExtended=true,middleExtended=true,pinch=2),260)
        assertNull(d.process(pose(indexExtended=true,middleExtended=true,pinch=2,deltaY=-.13f),370))
        assertEquals("Pinch2_Up",d.process(
            pose(indexExtended=true,middleExtended=true,pinch=2,deltaY=-.13f),475))
    }
}
