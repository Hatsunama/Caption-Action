package com.hatsunama.captionaction.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionDuplicateGateTest {
    @Test
    fun identicalPhrase_expiresAfterPause() {
        val gate = CaptionDuplicateGate()
        gate.onPublished("turn left at the lights", 1_000)
        assertTrue(gate.shouldSuppress("turn left at the lights", 2_000))
        assertTrue(gate.shouldSuppress("turn left at the lights", 3_500))
        assertFalse(gate.shouldSuppress("turn left at the lights", 3_501))
        assertFalse(gate.shouldSuppress("turn left at the lights", 60_000))
    }

    @Test
    fun suppressedCandidates_doNotKeepExtendingExpiry() {
        val gate = CaptionDuplicateGate()
        gate.onPublished("open the door", 1_000)
        for (time in 1_100L..3_500L step 100) {
            assertTrue(gate.shouldSuppress("open the door", time))
        }
        assertFalse(gate.shouldSuppress("open the door", 3_600))
        gate.onPublished("open the door", 3_600)
        assertTrue(gate.shouldSuppress("open the door", 3_700))
    }

    @Test
    fun newConsumerSession_doesNotInheritSuppression() {
        val priorSession = CaptionDuplicateGate()
        priorSession.onPublished("open the door", 1_000)
        assertTrue(priorSession.shouldSuppress("open the door", 1_001))
        assertFalse(CaptionDuplicateGate().shouldSuppress("open the door", 1_001))
    }

    @Test
    fun nearDuplicate_alsoExpiresWhileExtensionsRemainValid() {
        val gate = CaptionDuplicateGate()
        gate.onPublished("please open the door", 1_000)
        assertTrue(gate.shouldSuppress("please open the door now", 2_000))
        assertFalse(gate.shouldSuppress("please open the door now", 3_501))
        assertFalse(gate.shouldSuppress("please open the door before we arrive", 2_000))
        assertFalse(gate.shouldSuppress("close the window", 2_000))
    }

    @Test
    fun blankOrClockRollback_neverSuppresses() {
        val gate = CaptionDuplicateGate()
        gate.onPublished("open the door", 1_000)
        assertFalse(gate.shouldSuppress("", 1_001))
        assertFalse(gate.shouldSuppress("open the door", 999))
    }
}
