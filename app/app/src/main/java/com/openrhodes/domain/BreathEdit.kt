package com.openrhodes.domain

/** A device poll or an older write must not replace an unfinished local gesture. */
data class BreathEdit(
    val preview: LampState,
    val dirty: Boolean = false,
    val dragging: Boolean = false,
    val revision: Int = 0,
) {
    fun change(parameters: Breath, now: Long) =
        copy(
            preview =
                preview.copy(
                    breath = parameters.normalized(),
                    phase = preview.phaseAt(now),
                    sampledAt = now,
                ),
            dirty = true,
            dragging = true,
            revision = revision + 1,
        )

    fun release() = copy(dragging = false)

    fun observe(state: LampState) = if (dirty || dragging) this else copy(preview = state)

    fun settle(sentRevision: Int, state: LampState) =
        if (sentRevision != revision || dragging) this else copy(preview = state, dirty = false)
}
