package com.openrhodes.domain

import kotlin.math.PI
import kotlin.math.pow

/** A fixed time axis makes every period change visible, including periods above 10s. */
object BreathVisual {
    const val WINDOW_MS = 20000.0

    /** Same logical output formula as the firmware, extrapolated from its sampled phase. */
    fun outputPercent(state: LampState, now: Long): Float =
        when {
            !state.on -> 0f
            state.breathing -> state.brightness * state.breath.wave(state.phaseAt(now))
            else -> state.brightness.toFloat()
        }.coerceIn(0f, 100f)

    fun plotLevel(parameters: Breath, fraction: Double, startPhase: Double = 0.0) =
        parameters.wave(startPhase + fraction * WINDOW_MS / parameters.periodMs * 2 * PI)

    fun plotLevel(state: LampState, fraction: Double, startMs: Double) =
        plotLevel(
            state.breath,
            fraction,
            state.phase + (startMs - state.sampledAt) / state.breath.periodMs * 2 * PI,
        )

    /** Translate a cached periodic path left; wrapping by one cycle leaves the image identical. */
    fun scrollFraction(state: LampState, now: Long): Double {
        val phase = ((state.phaseAt(now) % (2 * PI)) + 2 * PI) % (2 * PI)
        return phase / (2 * PI) * state.breath.periodMs / WINDOW_MS
    }

    /** Keep a visible, compressed breath even at a 1% setting, without a full black/white pulse. */
    fun cardEnergy(state: LampState, now: Long): Float {
        if (!state.on) return .03f
        val base = (state.brightness / 100f).pow(1.3f) * .85f
        if (!state.breathing) return base
        val wave = state.breath.wave(state.phaseAt(now))
        return (.14f + base * .80f + (wave - .5f) * .26f).coerceIn(0f, 1f)
    }
}
