package com.openrhodes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Continuous warm light-to-dark mixing, including below 60%; no polarity switch. */
internal fun cardInk(energy: Float): List<Color> {
    val position = ((energy / .85f - .25f) / .5f).coerceIn(0f, 1f)
    val mix = position * position * (3f - 2f * position)
    return listOf(
        lerp(Color(0xFFFFFAED), Color(0xFF3F230A), mix),
        lerp(Color(0xFFE8D2B0), Color(0xFF190D04), mix),
    )
}
