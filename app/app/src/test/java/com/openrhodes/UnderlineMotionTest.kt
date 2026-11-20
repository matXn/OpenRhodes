package com.openrhodes

import com.openrhodes.domain.UnderlineMotion
import com.openrhodes.domain.UnderlineMotion.Mode
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class UnderlineMotionTest {
    private fun step(m: UnderlineMotion, n: Int, pull: Float = 0f, busy: Boolean = false) {
        repeat(n) { m.advance(.016f, pull, busy) }
    }

    @Test
    fun entranceFinishesAndStopsAtFullLine() {
        val m = UnderlineMotion(false)
        step(m, 600)
        assertTrue(m.entered)
        assertEquals(Mode.Idle, m.frame.mode)
        assertEquals(1f, m.frame.right, .001f)
        assertEquals(1f, m.frame.base, .001f)
    }

    @Test
    fun pullChargesShortBandAndArmsWithoutChangingLayout() {
        val m = UnderlineMotion(true)
        step(m, 100, .5f)
        assertEquals(.385f, m.frame.right, .002f)
        assertEquals(.22f, m.frame.right - m.frame.left, .002f)
        step(m, 100, 1f)
        assertEquals(.65f, m.frame.right, .002f)
        assertEquals(.32f, m.frame.right - m.frame.left, .002f)
        assertEquals(1.25f, m.frame.thickness, .002f)
        step(m, 180)
        assertEquals(Mode.Idle, m.frame.mode)
        assertEquals(1f, m.frame.thickness, .002f)
    }

    @Test
    fun releaseStartsAtCurrentLocationAndOnlyTravelsRight() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        var previous = m.frame
        repeat(80) {
            val frame = m.advance(.016f, 0f, it == 0)
            assertTrue(frame.right >= previous.right)
            assertTrue(frame.left >= previous.left)
            assertTrue(frame.right - previous.right < .04f)
            assertTrue(frame.right - frame.left in 0.119f..0.321f)
            previous = frame
        }
        assertEquals(Mode.Release, m.frame.mode)
        step(m, 120)
        assertEquals(Mode.Idle, m.frame.mode)
    }

    @Test
    fun longRefreshOnlyWrapsWhenInvisibleAndNoIdleGlintOverlaps() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        var wraps = 0
        repeat(600) {
            val before = m.frame
            val after = m.advance(.016f, 0f, true)
            if (after.right < before.right) {
                assertTrue(before.left >= 1f)
                assertTrue(after.right <= 0f)
                wraps++
            }
            if (it > 12) assertEquals(0f, after.glintAlpha, .001f)
        }
        assertTrue(wraps >= 5)
    }

    @Test
    fun renewedPullTakesOverWithoutJumping() {
        val m = UnderlineMotion(true)
        step(m, 60, .7f)
        m.advance(.016f, 0f, true)
        step(m, 25)
        val before = m.frame
        val after = m.advance(.016f, .4f, false)
        assertEquals(Mode.Pull, after.mode)
        assertTrue(abs(after.left - before.left) <= .041f)
        assertTrue(abs(after.right - before.right) <= .041f)
    }

    @Test
    fun renewedPullDuringRefreshKeepsVisibleFrameAndResumesReleaseOnLift() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        step(m, 25, busy = true)
        val before = m.frame
        val caught = m.advance(0f, .4f, true)
        assertEquals(Mode.Pull, caught.mode)
        assertEquals(before.left, caught.left, 0f)
        assertEquals(before.right, caught.right, 0f)
        assertEquals(before.bandAlpha, caught.bandAlpha, 0f)
        assertEquals(before.headAlpha, caught.headAlpha, 0f)
        step(m, 100, .4f, busy = true)
        assertEquals(Mode.Pull, m.frame.mode)
        assertEquals(.332f, m.frame.right, .002f)
        val held = m.frame
        val released = m.advance(0f, 0f, true)
        assertEquals(Mode.Release, released.mode)
        assertEquals(held.left, released.left, 0f)
        assertEquals(held.right, released.right, 0f)
    }

    @Test
    fun renewedPullDuringRestoreStartsAtVisibleFrame() {
        val m = UnderlineMotion(true)
        step(m, 100, .4f)
        step(m, 4)
        val before = m.frame
        assertEquals(Mode.Restore, before.mode)
        val caught = m.advance(0f, .2f, false)
        assertEquals(Mode.Pull, caught.mode)
        assertEquals(before.left, caught.left, 0f)
        assertEquals(before.right, caught.right, 0f)
        step(m, 100, .2f)
        assertEquals(.226f, m.frame.right, .002f)
    }

    @Test
    fun realPullWinsOverRefreshNotificationInSameFrame() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        step(m, 15)
        val before = m.frame
        val caught = m.advance(0f, .4f, true, request = 1)
        assertEquals(Mode.Pull, caught.mode)
        assertEquals(before.left, caught.left, 0f)
        assertEquals(before.right, caught.right, 0f)
        step(m, 20, .4f, busy = true)
        assertEquals(Mode.Pull, m.frame.mode)
    }

    @Test
    fun idleGlintIsDelayedAndMovesRightThenWaitsForNextCycle() {
        val m = UnderlineMotion(true)
        step(m, 7)
        assertEquals(0f, m.frame.glintAlpha, .001f)
        step(m, 15)
        val first = m.frame.glint
        step(m, 30)
        assertTrue(m.frame.glint > first)
        m.advance(.016f, .4f, false)
        step(m, 10, .4f)
        assertEquals(0f, m.frame.glintAlpha, .001f)
    }

    @Test
    fun disabledAnimationsUseStaticStateAndNoHighlights() {
        val m = UnderlineMotion(false)
        val f = m.advance(1f, 1f, true, false)
        assertTrue(m.entered)
        assertEquals(1f, f.thickness, 0f)
        assertEquals(0f, f.glintAlpha, 0f)
        assertEquals(0f, f.headAlpha, 0f)
        assertEquals(0f, f.sheenAlpha, 0f)
        assertEquals(.35f, f.base, 0f)
    }

    @Test
    fun fullLineDoesNotDimDuringHandoffToIdle() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        m.advance(.016f, 0f, true)
        repeat(250) {
            val f = m.advance(.016f, 0f, false)
            if (f.mode == Mode.Idle) assertEquals(1f, f.base, .001f)
        }
    }

    @Test
    fun requestThatFinishesWithinOneFrameStillReleases() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        m.advance(.016f, 0f, false, request = 1)
        assertEquals(Mode.Release, m.frame.mode)
        step(m, 150)
        assertEquals(Mode.Idle, m.frame.mode)
    }

    @Test
    fun armedReleaseDoesNotRestoreWhileWaitingForRefreshCallback() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        val start = m.advance(.016f, 0f, false)
        assertEquals(Mode.Release, start.mode)
        step(m, 15)
        val before = m.frame
        val acknowledged = m.advance(.016f, 0f, false, request = 1)
        assertTrue(acknowledged.right >= before.right)
        assertTrue(acknowledged.right - acknowledged.left < .33f)
    }

    @Test
    fun resumeDoesNotCatchUpInOneFrame() {
        val m = UnderlineMotion(true)
        step(m, 100, 1f)
        m.advance(.016f, 0f, true)
        val before = m.frame
        val after = m.advance(10f, 0f, false)
        assertTrue(abs(after.right - before.right) < .04f)
        assertEquals(Mode.Release, after.mode)
    }
}
