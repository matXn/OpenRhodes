package com.openrhodes

import com.openrhodes.domain.*
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class CardPresentationTest {
    @Test
    fun livePercentageMatchesFirmwareWaveRatherThanTheCeiling() {
        val state = LampState(true, 80, true, Breath(6000, 20, 150), sampledAt = 1000)
        assertEquals(80f, BreathVisual.outputPercent(state, 1000), .001f)
        assertEquals(16f, BreathVisual.outputPercent(state, 4000), .001f)
        assertEquals(80f, BreathVisual.outputPercent(state, 7000), .001f)
        for (time in 1000L..25000L step 33) {
            assertEquals(
                80 * state.breath.wave(state.phaseAt(time)),
                BreathVisual.outputPercent(state, time),
                .001f,
            )
        }
        assertEquals(0f, BreathVisual.outputPercent(state.copy(on = false), 4000), 0f)
        assertEquals(80, state.brightness)
    }

    @Test
    fun bothDirectionsStartAtTheCurrentFrameAndSettleToLiveOutput() {
        val renderer = CardPresentation()
        val steady = LampState(true, 56)
        val before = renderer.sample(steady, 1000)
        val breath = steady.copy(breathing = true, phase = PI / 2, sampledAt = 1000)
        val start = renderer.sample(breath, 1000)
        assertEquals(before.output, start.output, 0f)
        assertEquals(before.energy, start.energy, 0f)
        assertTrue(start.transitioning)
        val end = renderer.sample(breath, 1400)
        assertFalse(end.transitioning)
        assertEquals(BreathVisual.outputPercent(breath, 1400), end.output, .001f)
        assertEquals(BreathVisual.cardEnergy(breath, 1400), end.energy, .001f)
        val returnStart = renderer.sample(steady, 1400)
        assertEquals(end.output, returnStart.output, .001f)
        assertEquals(end.energy, returnStart.energy, .001f)
        assertEquals(56f, renderer.sample(steady, 1800).output, .001f)
    }

    @Test
    fun pollsDoNotRestartTheTransitionAndQuickModeChangesRemainContinuous() {
        val renderer = CardPresentation()
        val steady = LampState(true, 70)
        renderer.sample(steady, 1000)
        val breath = steady.copy(breathing = true, phase = PI / 2, sampledAt = 1000)
        renderer.sample(breath, 1000)
        val middle =
            renderer.sample(breath.copy(phase = breath.phaseAt(1200), sampledAt = 1200), 1200)
        val cancelled = renderer.sample(steady, 1200)
        assertEquals(middle.output, cancelled.output, .001f)
        assertEquals(middle.energy, cancelled.energy, .001f)
        val final = renderer.sample(steady.copy(sampledAt = 1550), 1600)
        assertFalse(final.transitioning)
        assertEquals(70f, final.output, .001f)
    }
}
