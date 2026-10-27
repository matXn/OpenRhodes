package com.openrhodes

import com.openrhodes.domain.*
import org.junit.Assert.*
import org.junit.Test

class BreathVisualTest {
    @Test
    fun longPeriodsChangeTheCurveOnEverySmallAdjustment() {
        for (period in listOf(10001, 11000, 14000, 19000)) {
            val before = (0..100).map { BreathVisual.plotLevel(Breath(period), it / 100.0) }
            val after = (0..100).map { BreathVisual.plotLevel(Breath(period + 25), it / 100.0) }
            assertTrue(
                "Curve must change above 10s: $period",
                before.zip(after).any { (a, b) -> kotlin.math.abs(a - b) > .0001f },
            )
        }
        val expected = Breath(16000).wave(5000.0 / 16000 * 2 * Math.PI)
        assertEquals(expected, BreathVisual.plotLevel(Breath(16000), .25), .000001f)
    }

    @Test
    fun subHalfSecondPeriodValuesAreRetainedAndKeepPhaseContinuous() {
        val state = LampState(true, 30, breathing = true, sampledAt = 1000, phase = .8)
        val edit = BreathEdit(state).change(Breath(12347), 1200)
        assertEquals(12347, edit.preview.breath.periodMs)
        assertEquals(state.phaseAt(1200), edit.preview.phaseAt(1200), 1e-9)
    }

    @Test
    fun cardBreathIsVisibleButCompressedAtBothDimAndBrightSettings() {
        for (brightness in listOf(1, 30, 100)) {
            val state =
                LampState(
                    true,
                    brightness,
                    breathing = true,
                    breath = Breath(6000, 0, 100),
                    sampledAt = 0,
                )
            val peak = BreathVisual.cardEnergy(state, 0)
            val trough = BreathVisual.cardEnergy(state, 3000)
            assertEquals(.26f, peak - trough, .00001f)
            assertTrue(trough >= 0f && peak <= 1f)
            assertEquals(peak, BreathVisual.cardEnergy(state, 6000), .00001f)
            assertEquals(
                BreathVisual.cardEnergy(state.copy(breathing = false), 0),
                BreathVisual.cardEnergy(state.copy(breathing = false), 3000),
                0f,
            )
        }
    }

    @Test
    fun cardAndCurveUseTheSameFloorCurveAndPhase() {
        val parameters = Breath(14000, 60, 250)
        val state = LampState(true, 30, breathing = true, breath = parameters)
        assertEquals(
            parameters.wave(Math.PI),
            BreathVisual.plotLevel(parameters, 7000.0 / BreathVisual.WINDOW_MS),
            .000001f,
        )
        val depth = BreathVisual.cardEnergy(state, 0) - BreathVisual.cardEnergy(state, 7000)
        assertEquals(.26f * .4f, depth, .00001f)
        assertEquals(.03f, BreathVisual.cardEnergy(state.copy(on = false), 0), 0f)
    }

    @Test
    fun timeZeroMarkerStaysInPhaseAcrossFormerPageBoundariesAndManyCycles() {
        val state =
            LampState(true, 70, breathing = true, breath = Breath(13743, 15, 130), phase = .7)
        for (time in listOf(0L, 19999L, 20000L, 39999L, 60000L, 123456L)) {
            val offset = BreathVisual.scrollFraction(state, time)
            assertTrue(offset >= 0.0 && offset < state.breath.periodMs / BreathVisual.WINDOW_MS)
            assertEquals(
                state.breath.wave(state.phaseAt(time)),
                BreathVisual.plotLevel(state, 0.0, time.toDouble()),
                .000001f,
            )
            assertEquals(
                state.breath.wave(state.phaseAt(time)),
                BreathVisual.plotLevel(state.breath, offset),
                .000001f,
            )
        }
    }

    @Test
    fun curveMovesLeftContinuouslyRatherThanPaging() {
        val state = LampState(true, 70, breathing = true, breath = Breath(), phase = .7)
        for (time in listOf(0L, 6799L, 19999L, 20000L, 39999L, 123456L)) {
            for (fraction in listOf(0.0, .1, .5, .9)) {
                assertEquals(
                    BreathVisual.plotLevel(
                        state,
                        fraction + 16.0 / BreathVisual.WINDOW_MS,
                        time.toDouble(),
                    ),
                    BreathVisual.plotLevel(state, fraction, time + 16.0),
                    .000001f,
                )
            }
        }
    }

    @Test
    fun periodEditKeepsTheFixedMarkerInPhaseAfterManyCycles() {
        val state =
            LampState(true, 70, breathing = true, breath = Breath(6500), phase = 240 * Math.PI + .7)
        val time = 12000L
        val edited = BreathEdit(state).change(Breath(18721), time).preview
        assertEquals(
            state.breath.wave(state.phaseAt(time)),
            BreathVisual.plotLevel(edited, 0.0, time.toDouble()),
            .000001f,
        )
    }
}
