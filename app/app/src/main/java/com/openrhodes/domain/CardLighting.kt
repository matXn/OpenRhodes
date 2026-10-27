package com.openrhodes.domain

import kotlin.math.*

/** Surface colour and specular reflection use the card's actual animated tilt. */
object CardLighting {
    const val MAX_TILT = 12f
    const val CAMERA_DISTANCE_DP = 500f
    const val LIGHT_PERIOD_MS = 48000L

    data class Surface(val hue: Float, val saturation: Float, val body: Float, val core: Float)

    data class Reflection(val shiftX: Float, val shiftY: Float, val angle: Float, val alpha: Float)

    fun surface(energy: Float, on: Boolean): Surface {
        // BreathVisual retains its existing 0.85 steady-state ceiling and breathing wave.
        val light = energy.coerceIn(0f, 1f) / .85f
        val body = (.08f + .67f * light.pow(1.05f)).coerceIn(0f, 1f)
        return Surface(
            28f + 17f * light,
            if (on) (.88f + .10f * light).coerceIn(0f, 1f) else .12f,
            body,
            (body + .08f * light).coerceIn(0f, 1f),
        )
    }

    fun reflection(tiltX: Float, tiltY: Float, lightX: Float, lightY: Float): Reflection {
        val ax = Math.toRadians(tiltX.toDouble())
        val ay = Math.toRadians(tiltY.toDouble())
        val nx = (sin(ay) * cos(ax)).toFloat()
        val ny = -sin(ax).toFloat()
        val nz = (cos(ay) * cos(ax)).toFloat()
        val length = sqrt(1f + lightX * lightX + lightY * lightY)
        val lx = lightX / length
        val ly = lightY / length
        val lz = 1f / length
        val dot = nx * lx + ny * ly + nz * lz
        val reflectedX = 2f * dot * nx - lx
        val reflectedY = 2f * dot * ny - ly
        val reflectedZ = 2f * dot * nz - lz
        val specular = (.12f + reflectedZ.coerceAtLeast(0f).pow(18)) / (.12f + lz.pow(18))
        val strength = (1f + (specular - 1f) * 1.6f).coerceIn(.2f, 1.9f)
        return Reflection(
            -(reflectedX + lx) * 1.1f,
            -(reflectedY + ly) * .8f,
            35f + nx * 40f - ny * 25f,
            .44f * strength,
        )
    }
}
