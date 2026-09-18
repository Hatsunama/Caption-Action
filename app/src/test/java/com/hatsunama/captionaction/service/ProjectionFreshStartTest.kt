package com.hatsunama.captionaction.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: stale MediaProjection RESULT_OK must never skip the system prompt.
 * Every Start re-prompts; prior grant Intent is not reusable.
 */
class ProjectionFreshStartTest {

    @Test
    fun staleCachedGrant_mustNotSkipSystemPrompt() {
        // Even if a prior Start left RESULT_OK in memory, Start must still launch
        // createScreenCaptureIntent (share-one-app vs entire-screen UI).
        assertTrue(
            ProjectionFreshStart.shouldLaunchSystemProjectionPrompt(
                apiAtLeastQ = true,
                hasCachedPriorGrant = true
            )
        )
        assertFalse(
            "stale RESULT_OK Intent must never be treated as still valid",
            ProjectionFreshStart.mayReusePriorGrantIntent(hasCachedPriorGrant = true)
        )
    }

    @Test
    fun noCachedGrant_stillPromptsOnQ() {
        assertTrue(
            ProjectionFreshStart.shouldLaunchSystemProjectionPrompt(
                apiAtLeastQ = true,
                hasCachedPriorGrant = false
            )
        )
    }

    @Test
    fun preQ_neverLaunchesProjectionPrompt() {
        assertFalse(
            ProjectionFreshStart.shouldLaunchSystemProjectionPrompt(
                apiAtLeastQ = false,
                hasCachedPriorGrant = true
            )
        )
    }

    @Test
    fun prechecks_blockShareUiUntilOverlayAndRecordAudioLive() {
        assertFalse(
            ProjectionFreshStart.precheckAllowsShareUi(
                overlayGranted = false,
                recordAudioGranted = true
            )
        )
        assertFalse(
            ProjectionFreshStart.precheckAllowsShareUi(
                overlayGranted = true,
                recordAudioGranted = false
            )
        )
        assertTrue(
            ProjectionFreshStart.precheckAllowsShareUi(
                overlayGranted = true,
                recordAudioGranted = true
            )
        )
    }

    @Test
    fun failKind_afterAllow_claimNull_isNotDecline() {
        val kind = ProjectionFreshStart.failKind(
            wantedProjection = true,
            projectionClaimed = false,
            playbackCaptureStarted = false
        )
        assertEquals(ProjectionFreshStart.FailKind.CLAIM_FAILED_AFTER_ALLOW, kind)
        assertFalse(kind.useDeclineCopy)
    }

    @Test
    fun failKind_decline_usesDeclineCopy() {
        val kind = ProjectionFreshStart.failKind(
            wantedProjection = false,
            projectionClaimed = false,
            playbackCaptureStarted = false
        )
        assertEquals(ProjectionFreshStart.FailKind.DECLINED, kind)
        assertTrue(kind.useDeclineCopy)
    }

    @Test
    fun failKind_playbackFailAfterAllow_isCaptureFailure_notAllowSharing() {
        val kind = ProjectionFreshStart.failKind(
            wantedProjection = true,
            projectionClaimed = true,
            playbackCaptureStarted = false
        )
        assertEquals(ProjectionFreshStart.FailKind.PLAYBACK_CAPTURE_FAILED, kind)
        assertFalse(kind.useDeclineCopy)
    }

    @Test
    fun secondStart_neverReusesFirstGrantToken() {
        // Simulate two Starts: first grant "consumed"; second must re-prompt.
        val firstGrantCached = true
        assertFalse(ProjectionFreshStart.mayReusePriorGrantIntent(firstGrantCached))
        assertTrue(
            ProjectionFreshStart.shouldLaunchSystemProjectionPrompt(
                apiAtLeastQ = true,
                hasCachedPriorGrant = firstGrantCached
            )
        )
    }
}
