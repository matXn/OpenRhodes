package com.openrhodes

import androidx.compose.ui.graphics.Color
import com.openrhodes.domain.*
import com.openrhodes.ui.cardInk
import org.junit.Assert.*
import org.junit.Test

class CardInkTest {
    @Test
    fun warmGradientMixesContinuouslyBelowSixtyPercent() {
        val samples =
            (0..1000).map {
                cardInk(BreathVisual.cardEnergy(LampState(true, it / 10), 0))
            }
        assertEquals(Color(0xFFFFFAED), samples.first()[0])
        assertEquals(Color(0xFF190D04), samples.last()[1])
        for ((a, b) in samples.zipWithNext()) {
            assertTrue(kotlin.math.abs(a[0].red - b[0].red) < .03f)
        }
        val mid = cardInk(BreathVisual.cardEnergy(LampState(true, 50), 0))
        val sixty = cardInk(BreathVisual.cardEnergy(LampState(true, 60), 0))
        assertTrue(mid[0].red < 1f && mid[0].red > .3f)
        assertTrue(sixty[0].red < mid[0].red)
        assertNotEquals(mid[0], mid[1])
    }

    @Test
    fun breathingMixesInkWithTheDisplayedSurface() {
        val state = LampState(true, 56, breathing = true)
        val peak = cardInk(BreathVisual.cardEnergy(state, 0))
        val trough = cardInk(BreathVisual.cardEnergy(state, 3000))
        assertTrue(peak[0].red < trough[0].red)
        assertEquals(
            Color(0xFFFFFAED),
            cardInk(BreathVisual.cardEnergy(state.copy(on = false), 0))[0],
        )
    }
}
