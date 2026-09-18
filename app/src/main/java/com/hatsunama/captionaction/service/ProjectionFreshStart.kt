package com.hatsunama.captionaction.service

/**
 * Pure Start→MediaProjection policy (JVM-testable).
 *
 * MediaProjection RESULT_OK Intent tokens are single-use and expire when the
 * projection stops. Every Start must launch a fresh [createScreenCaptureIntent]
 * system prompt — never reuse a prior grant Intent, never skip the prompt
 * because a previous Allow was remembered in-process.
 */
object ProjectionFreshStart {

    /**
     * Whether Home Start should launch the system share-one-app / entire-screen UI.
     * Always true on Android 10+; [hasCachedPriorGrant] is intentionally ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldLaunchSystemProjectionPrompt(
        apiAtLeastQ: Boolean,
        hasCachedPriorGrant: Boolean
    ): Boolean {
        // hasCachedPriorGrant is part of the API so tests can prove stale grants
        // still force a prompt — it must never short-circuit to false.
        if (!apiAtLeastQ) return false
        return true
    }

    /** Prior grant Intent is never valid for a later Start. */
    @Suppress("UNUSED_PARAMETER")
    fun mayReusePriorGrantIntent(hasCachedPriorGrant: Boolean): Boolean = false

    /**
     * Live overlay + RECORD_AUDIO must hold BEFORE createScreenCaptureIntent.
     * Otherwise the user can Allow entire-screen and still fail with a false
     * "allow screen sharing" message.
     */
    fun precheckAllowsShareUi(
        overlayGranted: Boolean,
        recordAudioGranted: Boolean
    ): Boolean = overlayGranted && recordAudioGranted

    enum class FailKind(val useDeclineCopy: Boolean) {
        /** User canceled / denied the system prompt. */
        DECLINED(useDeclineCopy = true),
        /** RESULT_OK returned but getMediaProjection failed (expired/consumed token). */
        CLAIM_FAILED_AFTER_ALLOW(useDeclineCopy = false),
        /** Projection claimed but AudioPlaybackCapture could not start. */
        PLAYBACK_CAPTURE_FAILED(useDeclineCopy = false),
        /** Session is capturing — not a failure. */
        OK(useDeclineCopy = false)
    }

    fun failKind(
        wantedProjection: Boolean,
        projectionClaimed: Boolean,
        playbackCaptureStarted: Boolean
    ): FailKind = when {
        playbackCaptureStarted && projectionClaimed -> FailKind.OK
        projectionClaimed && !playbackCaptureStarted -> FailKind.PLAYBACK_CAPTURE_FAILED
        wantedProjection && !projectionClaimed -> FailKind.CLAIM_FAILED_AFTER_ALLOW
        else -> FailKind.DECLINED
    }
}
