package com.openrhodes

import com.openrhodes.domain.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CardLightingTest {
    @Test
    fun fullBrightnessRestoresPaleGoldWithoutLosingDimOrBreathingDetail() {
        val full = CardLighting.surface(BreathVisual.cardEnergy(LampState(true, 100), 0), true)
        assertEquals(.75f, full.body, .00001f)
        assertEquals(.83f, full.core, .00001f)
        assertEquals(45f, full.hue, .00001f)
        val dim = CardLighting.surface(BreathVisual.cardEnergy(LampState(true, 1), 0), true)
        assertTrue(dim.core < .09f)
        for (level in listOf(1, 30, 100)) {
            val state = LampState(true, level, breathing = true, sampledAt = 0)
            val bodies =
                (0L..state.breath.periodMs / 2L step 10).map {
                    val surface = CardLighting.surface(BreathVisual.cardEnergy(state, it), true)
                    assertTrue(surface.body in 0f..1f && surface.core in 0f..1f)
                    surface.body
                }
            assertTrue(bodies.zipWithNext().all { (a, b) -> a >= b - .000001f })
            assertTrue(bodies.first() - bodies.last() > .15f)
        }
    }

    @Test
    fun tiltingChangesReflectionInBothAxesAndRestoresBrightSpecularPeaks() {
        val samples = buildList {
            for (i in 0..24) {
                val phase = i / 24.0 * 2 * PI
                for (x in -12..12 step 3) for (y in -12..12 step 3) {
                    add(
                        CardLighting.reflection(
                            x.toFloat(),
                            y.toFloat(),
                            .5f * sin(phase).toFloat(),
                            -.18f + .36f * cos(phase).toFloat(),
                        )
                    )
                }
            }
        }
        assertTrue(samples.maxOf { it.alpha } > .80f)
        assertTrue(samples.minOf { it.alpha } < .12f)
        assertTrue(samples.all { it.alpha in 0.08f..0.86f })
        assertTrue(samples.maxOf { it.shiftX } - samples.minOf { it.shiftX } > .5f)
        assertTrue(samples.maxOf { it.shiftY } - samples.minOf { it.shiftY } > .5f)
        assertTrue(samples.maxOf { it.angle } - samples.minOf { it.angle } > 20f)
    }

    @Test
    fun releaseReturnsToOneContinuousReflectionPlane() {
        for (i in 0..24) {
            val phase = i / 24.0 * 2 * PI
            val lx = .5f * sin(phase).toFloat()
            val ly = -.18f + .36f * cos(phase).toFloat()
            val flat = CardLighting.reflection(0f, 0f, lx, ly)
            val approaching = CardLighting.reflection(.0001f, -.0001f, lx, ly)
            assertEquals(0f, flat.shiftX, .00001f)
            assertEquals(0f, flat.shiftY, .00001f)
            assertEquals(.44f, flat.alpha, .00001f)
            assertEquals(flat.shiftX, approaching.shiftX, .0001f)
            assertEquals(flat.shiftY, approaching.shiftY, .0001f)
            assertEquals(flat.alpha, approaching.alpha, .0001f)
        }
    }
}
