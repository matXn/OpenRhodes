package com.openrhodes.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.roundToInt

/** 帧内显示值，编辑中的亮度上限仍保存在 LampState。 */
data class CardFrame(
    val output: Float,
    val energy: Float,
    val transitioning: Boolean,
    val phase: Double = 0.0,
    val breath: Breath = Breath(),
)

class CardPresentation {
    private var mode: Pair<Boolean, Boolean>? = null
    private var previous: CardFrame? = null
    private var started = 0L
    private var outputOffset = 0f
    private var energyOffset = 0f
    private var ceiling: Float? = null
    private var sampledAt: Long? = null
    private var source: LampState? = null
    private var phaseSampledAt: Long? = null
    private var parametersSampledAt: Long? = null
    private var shownPeriod = 0.0
    private var shownFloor = 0.0
    private var shownCurve = 0.0

    fun sample(state: LampState, now: Long, phaseObservation: LampState? = state): CardFrame {
        val nextMode = state.on to state.breathing
        val target = state.brightness.toFloat()
        val dt = sampledAt?.let { (now - it).coerceIn(0, 40).toFloat() } ?: 0f
        // 持续拖动和相同目标的轮询仍需逐帧平滑，不能重置动画。
        ceiling =
            if (mode == nextMode && state.on) {
                val before = ceiling ?: target
                val step = (target - before) * (1f - exp(-dt / 60f))
                val limit = 100f * dt / TRANSITION_MS
                val value = before + step.coerceIn(-limit, limit)
                if (abs(target - value) < .05f) target else value
            } else target
        sampledAt = now

        val parameters = sampleParameters(state.breath, now)
        val phase = samplePhase(state, parameters, now, phaseObservation)
        val visual = state.copy(breath = parameters, phase = phase, sampledAt = now)
        val output = BreathVisual.outputPercent(visual, now, requireNotNull(ceiling))
        val energy = BreathVisual.cardEnergy(visual, now, requireNotNull(ceiling))
        if (mode != nextMode) {
            previous?.let {
                outputOffset = it.output - output
                energyOffset = it.energy - energy
                started = now
            }
            mode = nextMode
        }
        val modeRemaining = remaining(now, started)
        return CardFrame(
            (output + outputOffset * modeRemaining).coerceIn(0f, 100f),
            (energy + energyOffset * modeRemaining).coerceIn(0f, 1f),
            (modeRemaining > 0f && (outputOffset != 0f || energyOffset != 0f)) ||
                (state.on && ceiling != target) ||
                parameters != state.breath,
            phase,
            parameters,
        ).also { previous = it }
    }

    private fun sampleParameters(target: Breath, now: Long): Breath {
        val last = parametersSampledAt
        parametersSampledAt = now
        if (last == null) {
            shownPeriod = target.periodMs.toDouble()
            shownFloor = target.floor.toDouble()
            shownCurve = target.curve.toDouble()
        } else {
            // 固定响应时间，不因新目标重新计时；浮点累积让高刷新率下的小调整也能跟上。
            val elapsed = (now - last).coerceAtLeast(0).toDouble()
            val blend = 1.0 - exp(-elapsed / PARAMETER_RESPONSE_MS)
            shownPeriod += (target.periodMs - shownPeriod) * blend
            shownFloor += (target.floor - shownFloor) * blend
            shownCurve += (target.curve - shownCurve) * blend
        }
        return Breath(shownPeriod.roundToInt(), shownFloor.roundToInt(), shownCurve.roundToInt())
    }

    private fun samplePhase(
        state: LampState,
        parameters: Breath,
        now: Long,
        observation: LampState?,
    ): Double {
        val elapsed = phaseSampledAt?.let { (now - it).coerceAtLeast(0).toDouble() } ?: 0.0
        phaseSampledAt = now
        val old = source
        source = state
        val frame = previous
        if (!state.on || !state.breathing) return frame?.phase ?: state.phase
        if (old == null || !old.on || !old.breathing || frame == null) return state.phaseAt(now)

        // 显示时钟自行推进。调整周期只改变后续速度，设备回报不能重置进度。
        val advance = elapsed * PI * (1.0 / frame.breath.periodMs + 1.0 / parameters.periodMs)
        val predicted = frame.phase + advance
        if (observation == null || !observation.on || !observation.breathing) return predicted
        val difference = observation.phaseAt(now) - predicted
        val error = difference - round(difference / (2 * PI)) * 2 * PI
        // 允许缓慢同步设备时钟，校准速度最多改变正常流速的 15%，不会倒转或赶相位。
        val correction = (error * (1.0 - exp(-elapsed / parameters.periodMs)))
            .coerceIn(-advance * .15, advance * .15)
        return predicted + correction
    }

    private fun remaining(now: Long, start: Long): Float {
        val progress = ((now - start).coerceAtLeast(0) / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - progress * progress * (3f - 2f * progress)
    }

    companion object {
        const val TRANSITION_MS = 400L
        private const val PARAMETER_RESPONSE_MS = 16.0
    }
}
