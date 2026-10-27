package com.openrhodes.domain

/** Frame-local presentation; the editable brightness ceiling remains in LampState. */
data class CardFrame(val output: Float, val energy: Float, val transitioning: Boolean)

class CardPresentation {
    private var mode: Pair<Boolean, Boolean>? = null
    private var previous: CardFrame? = null
    private var started = 0L
    private var outputOffset = 0f
    private var energyOffset = 0f

    fun sample(state: LampState, now: Long): CardFrame {
        val output = BreathVisual.outputPercent(state, now)
        val energy = BreathVisual.cardEnergy(state, now)
        val nextMode = state.on to state.breathing
        if (mode != nextMode) {
            previous?.let {
                outputOffset = it.output - output
                energyOffset = it.energy - energy
                started = now
            }
            mode = nextMode
        }
        val elapsed = (now - started).coerceAtLeast(0)
        val progress = (elapsed / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val remaining = 1f - progress * progress * (3f - 2f * progress)
        return CardFrame(
                (output + outputOffset * remaining).coerceIn(0f, 100f),
                (energy + energyOffset * remaining).coerceIn(0f, 1f),
                elapsed < TRANSITION_MS && (outputOffset != 0f || energyOffset != 0f),
            )
            .also { previous = it }
    }

    companion object {
        const val TRANSITION_MS = 400L
    }
}
