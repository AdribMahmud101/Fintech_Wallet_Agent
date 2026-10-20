package app.fusion.wallet.ui.motion

import org.junit.Assert.*
import org.junit.Test

class MotionPolicyTest {
    @Test fun systemRemoveAnimationsDisablesAllMotion() {
        assertFalse(MotionPolicy.enabled(0f))
        assertFalse(MotionPolicy.enabled(-1f))
        assertFalse(MotionPolicy.enabled(Float.NaN))
        assertFalse(MotionPolicy.enabled(Float.POSITIVE_INFINITY))
    }
    @Test fun positiveSystemScaleEnablesMotionWithoutDoubleScaling() {
        assertTrue(MotionPolicy.enabled(.5f))
        assertTrue(MotionPolicy.enabled(1f))
        assertTrue(MotionPolicy.enabled(10f))
        assertEquals(60L, MotionPolicy.revealDelayMillis(1, MotionPolicy.enabled(.5f)))
    }
    @Test fun reducedMotionRevealsEveryRecordedResultImmediately() {
        (0..16).forEach { index -> assertEquals(0L, MotionPolicy.revealDelayMillis(index, MotionPolicy.enabled(0f))) }
        assertEquals(900L, MotionPolicy.revealDelayMillis(15, true))
    }
}
