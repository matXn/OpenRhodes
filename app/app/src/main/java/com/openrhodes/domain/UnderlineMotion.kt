package com.openrhodes.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** 单一时钟驱动字标和光带，手势只改变目标，不清零可见的动画。 */
internal class UnderlineMotion(entered: Boolean, initialRequest: Int = 0) {
    enum class Mode {
        Entrance,
        Idle,
        Pull,
        Release,
        Restore,
    }

    data class Frame(
        val left: Float = 0f,
        val right: Float = 0f,
        val base: Float = 0f,
        val thickness: Float = 1f,
        val bandAlpha: Float = 1f,
        val glint: Float = 0f,
        val glintAlpha: Float = 0f,
        val headAlpha: Float = 0f,
        val sheenPosition: Float = -.25f,
        val sheenAlpha: Float = .9f,
        val mode: Mode = Mode.Entrance,
    )

    var frame =
        Frame(
            right = if (entered) 1f else 0f,
            base = if (entered) 1f else 0f,
            bandAlpha = if (entered) 0f else 1f,
            mode = if (entered) Mode.Idle else Mode.Entrance,
        )
        private set

    private var pass = if (entered) 3 else 0
    val entered
        get() = pass >= 3

    private var mode = frame.mode
    private var clock = 0f
    private var idleCycle = 0
    private var lastRequest = initialRequest
    private var awaitingRefreshEvent = false
    private var wasRefreshing = false
    private var lastPull = 0f
    private var releaseTime = 0f
    private var releaseLeft = 0f
    private var releaseRight = 0f
    private var thresholdPulse = 1f
    private var armed = false

    private fun release() {
        mode = Mode.Release
        releaseTime = 0f
        releaseLeft = frame.left
        releaseRight = frame.right
    }

    fun advance(
        elapsed: Float,
        pull: Float,
        refreshing: Boolean,
        animations: Boolean = true,
        request: Int = lastRequest,
    ): Frame {
        val dt = elapsed.coerceIn(0f, .032f)
        val amount = pull.coerceIn(0f, 1f)
        if (!animations) {
            pass = 3
            mode = Mode.Idle
            lastRequest = request
            wasRefreshing = refreshing
            lastPull = amount
            frame =
                Frame(
                    right = 1f,
                    base = if (refreshing) .35f else 1f,
                    bandAlpha = 0f,
                    sheenAlpha = 0f,
                    mode = mode,
                )
            return frame
        }
        clock += dt
        val cycle = (clock / 2.6f).toInt()
        val phase = clock % 2.6f
        // pull 只来自已越过触摸阈值的真实手势，可以接住刷新或回弹中的可见帧。
        if (amount > .001f) {
            pass = 3
            mode = Mode.Pull
            awaitingRefreshEvent = false
        } else if (request != lastRequest || (refreshing && !wasRefreshing)) {
            pass = 3
            // 松手与刷新通知可能不在同一帧，保持已经释放的光带。
            if (!awaitingRefreshEvent || mode != Mode.Release) release()
            awaitingRefreshEvent = false
        } else if (mode == Mode.Pull && amount <= .001f) {
            if (refreshing || lastPull >= .999f) {
                release()
                awaitingRefreshEvent = !refreshing
            } else {
                mode = Mode.Restore
            }
        }
        lastRequest = request
        wasRefreshing = refreshing
        lastPull = amount
        if (mode == Mode.Pull && amount >= 1f && !armed) thresholdPulse = 0f
        armed = mode == Mode.Pull && amount >= 1f
        thresholdPulse = (thresholdPulse + dt / .22f).coerceAtMost(1f)

        fun approach(value: Float, target: Float, tau: Float = .09f, speed: Float = 2.5f): Float {
            val step = (target - value) * (1f - exp(-dt / tau))
            return value + step.coerceIn(-speed * dt, speed * dt)
        }
        var left = frame.left
        var right = frame.right
        var base = frame.base
        var band = frame.bandAlpha
        var thickness = frame.thickness
        var head = frame.headAlpha
        when (mode) {
            Mode.Entrance -> {
                val targetLeft = if (pass == 1) 1f else 0f
                left = approach(left, targetLeft, .11f, .65f)
                right = approach(right, 1f, .11f, .65f)
                if (abs(left - targetLeft) < .001f && abs(right - 1f) < .001f) {
                    pass++
                    if (pass == 2) {
                        left = 0f
                        right = 0f
                    }
                    if (pass == 3) {
                        mode = Mode.Idle
                        idleCycle = cycle + 1
                        left = 0f
                        right = 1f
                        base = 1f
                        band = 0f
                    }
                }
            }
            Mode.Idle -> {
                left = approach(left, 0f)
                right = approach(right, 1f)
                base = approach(base, 1f)
                band = approach(band, 0f)
                thickness = approach(thickness, 1f)
                head = approach(head, 0f)
            }
            Mode.Pull -> {
                val width = .12f + .20f * amount
                val targetRight = .12f + .53f * amount
                right = approach(right, targetRight, .09f)
                left = approach(left, targetRight - width, .13f)
                base = approach(base, 1f - .92f * (amount / .15f).coerceAtMost(1f))
                band = approach(band, .65f + .35f * amount)
                thickness = approach(thickness, if (armed) 1.25f else 1f)
                head =
                    approach(head, .25f + .45f * amount + .3f * sin(PI.toFloat() * thresholdPulse))
            }
            Mode.Release -> {
                releaseTime = (releaseTime + dt).coerceAtMost(1.4f)
                val t = releaseTime / 1.4f
                val eased = t * t * (3f - 2f * t)
                right = releaseRight + (1.32f - releaseRight) * eased
                val width = (releaseRight - releaseLeft) * (1f - eased) + .12f * eased
                left = right - width
                base = approach(base, .08f)
                band = approach(band, 1f)
                head = approach(head, .9f)
                thickness = approach(thickness, 1f)
                if (releaseTime >= 1.4f) {
                    // 只有完全退出裁切区以后才换边，短请求也走完这次释放。
                    left = -.32f
                    right = 0f
                    if (refreshing) {
                        releaseLeft = left
                        releaseRight = right
                        releaseTime = 0f
                    } else {
                        left = 0f
                        mode = Mode.Restore
                    }
                }
            }
            Mode.Restore -> {
                left = approach(left, 0f, .06f, 4f)
                right = approach(right, 1f, .06f, 4f)
                head = approach(head, 0f)
                thickness = approach(thickness, 1f)
                if (abs(left) < .001f && abs(right - 1f) < .001f) {
                    mode = Mode.Idle
                    idleCycle = cycle + 1
                    left = 0f
                    right = 1f
                    base = 1f
                    band = 0f
                }
            }
        }
        val idleGlint = mode == Mode.Idle && cycle >= idleCycle && phase >= .12f && phase < 1.92f
        val glintAlpha =
            if (idleGlint) (frame.glintAlpha + .65f * dt / .15f).coerceAtMost(.65f)
            else (frame.glintAlpha - .65f * dt / .15f).coerceAtLeast(0f)
        val glint = if (idleGlint) -.09f + 1.18f * ((phase - .12f) / 1.8f) else frame.glint
        frame =
            Frame(
                left,
                right.coerceAtLeast(left),
                base,
                thickness,
                band,
                glint,
                glintAlpha,
                head,
                -.25f + 1.5f * phase / 2.6f,
                .9f,
                mode,
            )
        return frame
    }
}
