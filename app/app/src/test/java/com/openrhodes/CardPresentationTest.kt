package com.openrhodes

import com.openrhodes.domain.*
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class CardPresentationTest {
    @Test
    fun brightnessChangesAlsoFadeAndRepeatedPollsDoNotResetThem() {
        val renderer = CardPresentation()
        val dim = LampState(true, 20)
        renderer.sample(dim, 1000)
        val brighter = dim.copy(brightness = 80)
        val start = renderer.sample(brighter, 1000)
        assertEquals(20f, start.output, .001f)
        var previous = start
        for (time in 1010L..1600L step 10) {
            val frame = renderer.sample(brighter.copy(sampledAt = time), time)
            assertTrue(frame.output >= previous.output)
            assertTrue(frame.output - previous.output <= 2.6f)
            previous = frame
        }
        assertEquals(80f, previous.output, .05f)
        assertFalse(previous.transitioning)
        val reversal = renderer.sample(dim, 1600)
        assertEquals(previous.output, reversal.output, .001f)
        assertTrue(renderer.sample(dim, 1610).output < reversal.output)
    }

    @Test
    fun livePercentageMatchesFirmwareWaveRatherThanTheCeiling() {
        val state = LampState(true, 80, true, Breath(6000, 20, 150), sampledAt = 1000)
        assertEquals(80f, BreathVisual.outputPercent(state, 1000), .001f)
        assertEquals(16f, BreathVisual.outputPercent(state, 4000), .001f)
        assertEquals(80f, BreathVisual.outputPercent(state, 7000), .001f)
        for (time in 1000L..25000L step 33) {
            assertEquals(
                80 * state.breath.wave(state.phaseAt(time)),
                BreathVisual.outputPercent(state, time),
                .001f,
            )
        }
        assertEquals(0f, BreathVisual.outputPercent(state.copy(on = false), 4000), 0f)
        assertEquals(80, state.brightness)
    }

    @Test
    fun bothDirectionsStartAtTheCurrentFrameAndSettleToLiveOutput() {
        val renderer = CardPresentation()
        val steady = LampState(true, 56)
        val before = renderer.sample(steady, 1000)
        val breath = steady.copy(breathing = true, phase = PI / 2, sampledAt = 1000)
        val start = renderer.sample(breath, 1000)
        assertEquals(before.output, start.output, 0f)
        assertEquals(before.energy, start.energy, 0f)
        assertTrue(start.transitioning)
        val end = renderer.sample(breath, 1400)
        assertFalse(end.transitioning)
        assertEquals(BreathVisual.outputPercent(breath, 1400), end.output, .001f)
        assertEquals(BreathVisual.cardEnergy(breath, 1400), end.energy, .001f)
        val returnStart = renderer.sample(steady, 1400)
        assertEquals(end.output, returnStart.output, .001f)
        assertEquals(end.energy, returnStart.energy, .001f)
        assertEquals("退出呼吸时保留当前方向，随浮动幅度一起收回", end.phase, returnStart.phase, 1e-9)
        assertEquals(56f, renderer.sample(steady, 1800).output, .001f)
    }

    @Test
    fun pollsDoNotRestartTheTransitionAndQuickModeChangesRemainContinuous() {
        val renderer = CardPresentation()
        val steady = LampState(true, 70)
        renderer.sample(steady, 1000)
        val breath = steady.copy(breathing = true, phase = PI / 2, sampledAt = 1000)
        renderer.sample(breath, 1000)
        val middle =
            renderer.sample(breath.copy(phase = breath.phaseAt(1200), sampledAt = 1200), 1200)
        val cancelled = renderer.sample(steady, 1200)
        assertEquals(middle.output, cancelled.output, .001f)
        assertEquals(middle.energy, cancelled.energy, .001f)
        val final = renderer.sample(steady.copy(sampledAt = 1550), 1600)
        assertFalse(final.transitioning)
        assertEquals(70f, final.output, .001f)
    }

    @Test
    fun eachParameterEditStartsAtTheDisplayedWaveAndSettlesToTheNewCurve() {
        val original = LampState(true, 80, true, phase = 2.3, sampledAt = 1000)
        val changes = listOf(Breath(17000), Breath(floor = 70), Breath(curve = 300))
        for (parameters in changes) {
            val renderer = CardPresentation()
            val before = renderer.sample(original, 1000)
            val edited = BreathEdit(original).change(parameters, 1000).preview
            val start = renderer.sample(edited, 1000)
            assertEquals(before.phase, start.phase, 1e-9)
            assertEquals(before.output, start.output, .001f)
            assertEquals(before.energy, start.energy, .001f)
            assertEquals(before.breath, start.breath)
            assertTrue(start.transitioning)
            val end = renderer.sample(edited, 1400)
            assertEquals(parameters, end.breath)
            val displayed = edited.copy(breath = end.breath, phase = end.phase, sampledAt = 1400)
            assertEquals(BreathVisual.outputPercent(displayed, 1400), end.output, .001f)
            assertEquals(BreathVisual.cardEnergy(displayed, 1400), end.energy, .001f)
            assertFalse(end.transitioning)
        }
    }

    @Test
    fun deviceAcknowledgementRephasingKeepsLightAndTiltContinuousAcrossPolls() {
        val renderer = CardPresentation()
        val preview = LampState(true, 80, true, phase = 5.2, sampledAt = 1000)
        renderer.sample(preview, 1000)
        val before = renderer.sample(preview, 1200)
        val reply = preview.copy(phase = 1.2, sampledAt = 1200)
        val acknowledged = renderer.sample(reply, 1200)
        assertEquals(before.phase, acknowledged.phase, 1e-9)
        assertEquals(before.output, acknowledged.output, .001f)
        assertEquals(before.energy, acknowledged.energy, .001f)
        var previous = acknowledged
        for (time in 1216L..1600L step 16) {
            val poll = reply.copy(phase = reply.phaseAt(time), sampledAt = time)
            val frame = renderer.sample(poll, time)
            val step = frame.phase - previous.phase
            val nominal = 16.0 * 2 * PI / preview.breath.periodMs
            assertTrue("回报后必须保持前进，并维持原有流速", step in nominal * .8..nominal * 1.2)
            assertTrue("显示亮度不能在回报时跳变", kotlin.math.abs(frame.output - previous.output) < 6f)
            assertEquals(frame.output / 80f, frame.breath.wave(frame.phase), .00001f)
            previous = frame
        }
        assertFalse(previous.transitioning)
        for (time in 1616L..62000L step 16) previous = renderer.sample(reply, time)
        val difference = previous.phase - reply.phaseAt(62000)
        assertEquals("持续运行后仍应缓慢同步设备时钟", 0.0,
            difference - kotlin.math.round(difference / (2 * PI)) * 2 * PI, .02)
    }

    @Test
    fun rapidEditsAndARevertedRequestTakeOverTheCurrentDisplayedFrame() {
        val renderer = CardPresentation()
        val original = LampState(true, 70, true, phase = 1.8, sampledAt = 1000)
        renderer.sample(original, 1000)
        val first = original.copy(breath = Breath(12000, 65, 250))
        renderer.sample(first, 1000)
        val before = renderer.sample(first, 1160)
        val next = first.copy(breath = Breath(2000, 0, 50),
            phase = first.phaseAt(1160), sampledAt = 1160)
        val start = renderer.sample(next, 1160)
        assertEquals(before.output, start.output, .001f)
        assertEquals(before.energy, start.energy, .001f)
        assertEquals(before.phase, start.phase, 1e-9)
        assertEquals(before.breath, start.breath)
        val beforeRevert = renderer.sample(next, 1240)
        val reverted = original.copy(phase = .4, sampledAt = 1240)
        val revert = renderer.sample(reverted, 1240)
        assertEquals(beforeRevert.output, revert.output, .001f)
        assertEquals(beforeRevert.energy, revert.energy, .001f)
        assertEquals(beforeRevert.phase, revert.phase, 1e-9)
        val end = renderer.sample(reverted, 1640)
        assertEquals(original.breath, end.breath)
        val displayed = reverted.copy(breath = end.breath, phase = end.phase, sampledAt = 1640)
        assertEquals(BreathVisual.outputPercent(displayed, 1640), end.output, .001f)
        assertFalse(end.transitioning)
    }


    @Test
    fun sliderReleaseAndDelayedRepliesCannotAccelerateOrRestartTheDisplayClock() {
        val renderer = CardPresentation()
        var now = 1000L
        var state = LampState(true, 80, true, phase = 4.8, sampledAt = now)
        var observation: LampState? = state
        var frame = renderer.sample(state, now, observation)
        fun advance(count: Int) {
            repeat(count) {
                now += 16
                val next = renderer.sample(state, now, observation)
                val maximum = 16.0 * 2 * PI / minOf(frame.breath.periodMs, next.breath.periodMs) * 1.2
                assertTrue("拖动、松手和确认均不能让时钟倒转或突然加速",
                    next.phase >= frame.phase && next.phase - frame.phase <= maximum + 1e-9)
                if (next.breath == frame.breath) {
                    assertTrue("参数不变时回报不能引入明暗跳变", kotlin.math.abs(next.energy - frame.energy) < .03f)
                }
                frame = next
            }
        }
        for (parameters in listOf(Breath(12000), Breath(12000, 70), Breath(12000, 70, 300))) {
            var edit = BreathEdit(state).change(parameters, now)
            state = edit.preview
            observation = null
            advance(14)
            edit = edit.release()
            // 模拟固件在松手后的延迟回报中重新计算相位。
            val reply = state.copy(phase = .6, sampledAt = now)
            state = edit.settle(edit.revision, reply).preview
            observation = reply
            val before = frame
            frame = renderer.sample(state, now, observation)
            assertEquals("松手后的确认不能重置相位", before.phase, frame.phase, 1e-9)
            assertEquals(before.output, frame.output, .001f)
            advance(50)
            state = reply.copy(phase = reply.phaseAt(now), sampledAt = now)
            advance(20)
        }
    }


    @Test
    fun movingSliderRespondsEveryFrameWithoutRestartingTheFilter() {
        for (frameMs in listOf(8L, 16L)) {
            val renderer = CardPresentation()
            var now = 1000L
            var state = LampState(true, 80, true, Breath(6800, 0, 50), phase = 1.4, sampledAt = now)
            var frame = renderer.sample(state, now, null)
            for (step in 1..80) {
                now += frameMs
                state = state.copy(breath = Breath(6800 + step * 100, step, 50 + step * 3))
                val next = renderer.sample(state, now, null)
                assertTrue("连续拖动的周期应持续响应", next.breath.periodMs > frame.breath.periodMs)
                assertTrue("最暗值应在两个刻度内跟随手指", state.breath.floor - next.breath.floor <= 2)
                assertTrue("曲线应及时跟随手指", state.breath.curve - next.breath.curve <= 5)
                assertTrue("快速反馈仍保持相位正向连续", next.phase > frame.phase)
                frame = next
            }
            val reversed = state.copy(breath = Breath(2000, 0, 50))
            val afterOneFrame = renderer.sample(reversed, now + frameMs, null)
            assertTrue("反向拖动应在下一帧立即响应", afterOneFrame.breath.periodMs < frame.breath.periodMs)
            val after48ms = renderer.sample(reversed, now + 48, null)
            val remainder = (after48ms.breath.periodMs - 2000).toDouble() / (frame.breath.periodMs - 2000)
            assertTrue("大幅调整在48ms内应完成至少95%的跟随", remainder <= .051)
        }
    }

    @Test
    fun smallEditsAtHighRefreshRateDoNotGetLostToRounding() {
        val renderer = CardPresentation()
        val original = LampState(true, 80, true, Breath(floor = 13), sampledAt = 1000)
        renderer.sample(original, 1000, null)
        val edited = original.copy(breath = original.breath.copy(floor = 14))
        renderer.sample(edited, 1008, null)
        assertEquals("高刷新率下一个刻度的变化也应很快生效", 14,
            renderer.sample(edited, 1016, null).breath.floor)
    }

}
