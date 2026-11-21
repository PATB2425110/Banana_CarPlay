package com.dbtool.carplay.ui

import com.dbtool.carplay.ui.ThreeFingerProjectionGesture.Action
import com.dbtool.carplay.ui.ThreeFingerProjectionGesture.Point
import org.junit.Assert.*
import org.junit.Test

class ThreeFingerProjectionGestureTest {
    private val gesture = ThreeFingerProjectionGesture(80f)
    private fun points(dx: Float = 0f, dy: Float = 0f, count: Int = 3) = (0 until count).map { Point(it, 200f + it * 50 + dx, 200f + dy) }
    private fun begin() = gesture.onEvent(Action.POINTER_DOWN, points(), 0)
    @Test fun leftOpens() { begin(); assertEquals(true, gesture.onEvent(Action.MOVE, points(-100f), 100).showProjection) }
    @Test fun rightCloses() { begin(); assertEquals(false, gesture.onEvent(Action.MOVE, points(100f), 100).showProjection) }
    @Test fun twoFingersStayWithPhone() { assertFalse(gesture.onEvent(Action.MOVE, points(count = 2), 10).consume) }
    @Test fun releasePhoneOnceWhenThirdFingerArrives() {
        assertTrue(begin().releasePhone)
        assertFalse(gesture.onEvent(Action.MOVE, points(-100f), 100).releasePhone)
    }
    @Test fun noRepeatInOneSequence() {
        begin(); gesture.onEvent(Action.MOVE, points(-100f), 100)
        assertNull(gesture.onEvent(Action.MOVE, points(100f), 200).showProjection)
    }
    @Test fun verticalSwipeDoesNotProject() { begin(); assertNull(gesture.onEvent(Action.MOVE, points(-100f, 200f), 100).showProjection) }
    @Test fun shortSwipeDoesNotProject() { begin(); assertNull(gesture.onEvent(Action.MOVE, points(-30f), 100).showProjection) }
    @Test fun fourFingersCancelUntilAllLifted() {
        begin(); gesture.onEvent(Action.POINTER_DOWN, points(count = 4), 10)
        assertNull(gesture.onEvent(Action.MOVE, points(-100f), 100).showProjection)
    }
    @Test fun liftingOneFingerBeforeThresholdCancels() {
        begin(); gesture.onEvent(Action.POINTER_UP, points(), 10)
        assertNull(gesture.onEvent(Action.MOVE, points(-100f), 100).showProjection)
    }
    @Test fun pointerOrderDoesNotMatter() { begin(); assertEquals(true, gesture.onEvent(Action.MOVE, points(-100f).reversed(), 100).showProjection) }
    @Test fun oneStationaryFingerCannotTrigger() {
        begin(); val moved = points(-100f).toMutableList(); moved[1] = points()[1]
        assertNull(gesture.onEvent(Action.MOVE, moved, 100).showProjection)
    }
    @Test fun staleSequenceCannotTrigger() { begin(); assertNull(gesture.onEvent(Action.MOVE, points(-100f), 1501).showProjection) }
    @Test fun cancelAndNewDownResetGesture() {
        begin(); assertTrue(gesture.onEvent(Action.CANCEL, points(), 10).consume)
        assertFalse(gesture.onEvent(Action.DOWN, points(count = 1), 20).consume)
        begin(); assertEquals(false, gesture.onEvent(Action.MOVE, points(100f), 100).showProjection)
    }
    @Test fun remainingFingerCannotLeakBackToPhone() {
        begin(); gesture.onEvent(Action.POINTER_UP, points(), 20)
        assertTrue(gesture.onEvent(Action.MOVE, points(count = 1), 30).consume)
        assertTrue(gesture.onEvent(Action.UP, points(count = 1), 40).consume)
    }
    @Test fun lastMovementOnPointerUpStillOpens() {
        begin(); gesture.onEvent(Action.MOVE, points(-70f), 80)
        assertEquals(true, gesture.onEvent(Action.POINTER_UP, points(-100f), 100).showProjection)
        assertNull(gesture.onEvent(Action.MOVE, points(-150f), 110).showProjection)
    }
    @Test fun lastMovementOnPointerUpStillCloses() {
        begin(); assertEquals(false, gesture.onEvent(Action.POINTER_UP, points(100f), 100).showProjection)
    }
    @Test fun timedOutSequenceReportsWhyWithoutCoordinates() {
        begin(); gesture.onEvent(Action.MOVE, points(-100f), 1501)
        assertEquals("timeout", gesture.onEvent(Action.UP, points(count = 1), 1600).reason)
    }
    @Test fun dispatchCancelIsDistinguishedFromThresholdFailure() {
        begin(); assertEquals("cancelled_by_dispatch", gesture.onEvent(Action.CANCEL, points(), 100).reason)
    }
}
