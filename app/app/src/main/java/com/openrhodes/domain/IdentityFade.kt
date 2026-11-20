package com.openrhodes.domain

/** 同一元素先淡出，在不可见时切换位置，随后立即淡入。 */
object IdentityFade {
    const val OUT_MS = 160
    const val IN_MS = 220
    const val DURATION_MS = OUT_MS + IN_MS

    fun atTarget(elapsed: Float) = elapsed >= OUT_MS
    fun outgoingAlpha(elapsed: Float) = 1f - AddSuccessTiming.ease(elapsed / OUT_MS)
    fun incomingAlpha(elapsed: Float) = AddSuccessTiming.ease((elapsed - OUT_MS) / IN_MS)
    fun alpha(elapsed: Float) =
        if (atTarget(elapsed)) incomingAlpha(elapsed) else outgoingAlpha(elapsed)
}
