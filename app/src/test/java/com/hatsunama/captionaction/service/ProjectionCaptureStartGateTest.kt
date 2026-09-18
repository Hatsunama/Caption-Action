package com.hatsunama.captionaction.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: after Allow (projection claimed), Start must follow the 0.3.6 success path —
 * keep-alive VirtualDisplay failure alone must NOT show error_capture.
 * Only hard playback-capture init failure fails Start.
 */
class ProjectionCaptureStartGateTest {

    @Test
    fun keepAliveFailed_butCaptureStarted_doesNotFailStart() {
        assertFalse(
            "0.3.8 hard-failed Start on keep-alive alone; 0.3.6 stayed live after Allow",
            ProjectionCaptureStartGate.shouldFailStartAfterClaim(
                keepAliveDisplayFailed = true,
                playbackCaptureStarted = true
            )
        )
    }

    @Test
    fun keepAliveOk_captureStarted_doesNotFailStart() {
        assertFalse(
            ProjectionCaptureStartGate.shouldFailStartAfterClaim(
                keepAliveDisplayFailed = false,
                playbackCaptureStarted = true
            )
        )
    }

    @Test
    fun keepAliveFailed_captureNotStarted_failsStart() {
        assertTrue(
            "real AudioRecord/init failure still fails Start (0.3.6 behavior)",
            ProjectionCaptureStartGate.shouldFailStartAfterClaim(
                keepAliveDisplayFailed = true,
                playbackCaptureStarted = false
            )
        )
    }

    @Test
    fun keepAliveOk_captureNotStarted_failsStart() {
        assertTrue(
            ProjectionCaptureStartGate.shouldFailStartAfterClaim(
                keepAliveDisplayFailed = false,
                playbackCaptureStarted = false
            )
        )
    }

    @Test
    fun keepAliveFailureAlone_neverFailsStart() {
        assertFalse(
            "policy flag: keep-alive must not be a solo fail gate",
            ProjectionCaptureStartGate.keepAliveFailureAloneFailsStart()
        )
    }
}
