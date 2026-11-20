package com.openrhodes

import com.openrhodes.domain.*
import org.junit.Assert.*
import org.junit.Test

class AddPlaybackTest {
    private fun p(ms: Float) = ms / AddSuccessTiming.DURATION
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, .001f)

    @Test fun savedResultPassesSuccessFeedbackThenAutomaticallyFinishesAt6800ms() {
        val clock = AddPlayback()
        assertEquals(AddStage.INTRO, clock.frame.stage)
        near(AddSuccessTiming.SAVED_START_MS, clock.frame.elapsed)
        assertFalse(clock.confirm())
        clock.advance(600f)
        near(2600f, clock.frame.elapsed)
        assertEquals(AddStage.INTRO, clock.frame.stage)
        clock.advance(-50f)
        near(2600f, clock.frame.elapsed)
        clock.advance(1980f)
        assertEquals(AddStage.SUCCESS, clock.frame.stage)
        clock.advance(1000f)
        assertEquals(AddStage.TRANSFER, clock.frame.stage)
        clock.advance(1219f)
        assertEquals(AddStage.TRANSFER, clock.frame.stage)
        clock.advance(1f)
        assertEquals(AddStage.DETAIL, clock.frame.stage)
        near(6800f, clock.frame.elapsed)
        val final = clock.frame
        clock.advance(60000f)
        assertEquals(final, clock.frame)
        assertFalse(clock.confirm())
    }

    @Test fun checkAppearsOnlyAfterTheExpandedPanelAndSuccessCaption() {
        near(0f,AddSuccessTiming.successCaption(p(4000f)))
        near(1f,AddSuccessTiming.successCaption(p(4400f)))
        near(0f,AddSuccessTiming.successCheckAlpha(p(4300f)))
        near(1f,AddSuccessTiming.successCheckAlpha(p(4400f)))
        near(0f,AddSuccessTiming.checkReplay(p(4400f)))
        near(300f,AddSuccessTiming.checkReplay(p(4700f)))
        near(600f,AddSuccessTiming.checkReplay(p(5000f)))
        near(1f,AddSuccessTiming.successCheckAlpha(p(5200f)))
        near(0f,AddSuccessTiming.successCheckAlpha(p(5360f)))
        near(1f, AddSuccessTiming.nameRecenter(p(2300f)))
        near(0f, AddSuccessTiming.down(p(2400f)))
        near(1f, AddSuccessTiming.down(p(3100f)))
        near(1f, ConfirmMotion.tick(280f))
        assertTrue(AddSuccessTiming.down(p(2700f)) in .01f.. .99f)
    }

    @Test fun nativeSquareSettlesBeforeWidthExpands() {
        near(0f, AddSuccessTiming.arrival(p(2700f)))
        near(1f, AddSuccessTiming.arrival(p(3600f)))
        assertTrue(AddSuccessTiming.z(p(3200f)) < 0f)
        near(0f, AddSuccessTiming.z(p(3250f)))
        assertTrue(AddSuccessTiming.x(p(3250f)) > 0f)
        near(0f, AddSuccessTiming.x(p(3325f)))
        assertTrue(AddSuccessTiming.y(p(3325f)) < 0f)
        near(0f, AddSuccessTiming.y(p(3400f)))
        near(0f, AddSuccessTiming.expansion(p(3800f)))
        near(1f, AddSuccessTiming.expansion(p(4200f)))
        near(0f, AddSuccessTiming.panelTravel(p(4200f)))
        near(0f, AddSuccessTiming.panelTravel(p(5200f)))
        assertTrue(AddSuccessTiming.panelTravel(p(5360f)) in .01f.. .99f)
        near(1f, AddSuccessTiming.panelTravel(p(5580f)))
        near(0f, AddSuccessTiming.caption(p(4200f)))
        near(0f, AddSuccessTiming.transfer(p(5580f)))
        near(1f, AddSuccessTiming.transfer(p(6380f)))
        near(0f, AddSuccessTiming.leaf(p(6400f), 1))
        near(1f, AddSuccessTiming.leaf(p(6750f), 2))
        near(0f, AddSuccessTiming.chrome(p(3900f)))
        near(1f, AddSuccessTiming.chrome(p(5780f)))
    }

    @Test fun backgroundAndReducedMotionFinishWithoutConfirmation() {
        for (elapsed in listOf(0f, 1500f, 3000f, 5000f)) {
            val clock = AddPlayback()
            clock.advance(elapsed)
            clock.background()
            assertEquals(AddStage.DETAIL, clock.frame.stage)
            near(AddSuccessTiming.DURATION, clock.frame.elapsed)
            clock.advance(100f)
            near(AddSuccessTiming.DURATION, clock.frame.elapsed)
        }
        assertEquals(AddStage.DETAIL, AddPlayback(false).frame.stage)
    }
}
