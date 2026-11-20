package com.openrhodes

import com.openrhodes.domain.IdentityFade
import org.junit.Assert.*
import org.junit.Test

class IdentityFadeTest {
    @Test fun switchesPositionOnlyAtZeroOpacityAndImmediatelyFadesIn() {
        assertEquals(1f,IdentityFade.alpha(0f))
        assertFalse(IdentityFade.atTarget(159f))
        assertTrue(IdentityFade.atTarget(160f))
        assertEquals(0f,IdentityFade.alpha(160f))
        assertTrue(IdentityFade.alpha(161f)>0f)
        assertEquals(1f,IdentityFade.alpha(380f))
        for(ms in 0..380) {
            assertFalse(IdentityFade.outgoingAlpha(ms.toFloat())>0f && IdentityFade.incomingAlpha(ms.toFloat())>0f)
        }
    }
}
