package com.openrhodes

import com.openrhodes.domain.*
import org.junit.Assert.*
import org.junit.Test

class BreathEditTest {
    private val original = LampState(true, 30, breathing = true, phase = 1.2, sampledAt = 1000)

    @Test
    fun periodChangeKeepsCursorPhaseAtTheGestureTime() {
        val edit = BreathEdit(original).change(Breath(9500), 1400)
        assertEquals(original.phaseAt(1400), edit.preview.phaseAt(1400), 1e-9)
        assertEquals(9500, edit.preview.breath.periodMs)
    }

    @Test
    fun pollsAndEarlyAcknowledgementCannotMoveTheThumbDuringDrag() {
        val edit = BreathEdit(original).change(Breath(9000), 1400)
        assertEquals(edit, edit.observe(original.copy(breath = Breath(2000))))
        assertEquals(edit, edit.settle(edit.revision, original))
        assertEquals(edit.release(), edit.release().observe(original))
    }

    @Test
    fun oldReplyCannotOverwriteANewerParameterEdit() {
        val first = BreathEdit(original).change(Breath(9000), 1400)
        val second = first.change(Breath(12000, 20, 150), 1450).release()
        assertEquals(second, second.settle(first.revision, first.preview))
        val confirmed = second.settle(second.revision, second.preview)
        assertFalse(confirmed.dirty)
        val remote = second.preview.copy(breath = Breath(6000))
        assertEquals(remote, confirmed.observe(remote).preview)
    }

    @Test
    fun failedCurrentRequestCanRevertAfterReleaseWithoutDiscardingANewGesture() {
        val edit = BreathEdit(original).change(Breath(9000), 1400)
        assertEquals(edit, edit.settle(edit.revision, original))
        assertEquals(original, edit.release().settle(edit.revision, original).preview)
        assertFalse(edit.release().settle(edit.revision, original).dirty)
    }
}
