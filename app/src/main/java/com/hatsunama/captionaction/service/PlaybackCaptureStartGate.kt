package com.hatsunama.captionaction.service

/**
 * Pure Start→playback-capture init policy (JVM-testable).
 *
 * Silence / zero PCM energy / short first read must **never** fail Start.
 * Only hard AudioRecord / permission / API / security failures abort the session.
 * After a successful init the overlay stays up on launcher Home and waits for
 * device audio (existing Listening copy — no new idle UX).
 */
object PlaybackCaptureStartGate {

    /**
     * Hard init errors only. Callers must not pass silence / RMS into this.
     */
    fun isHardCaptureFailure(
        apiAtLeastQ: Boolean,
        hasRecordAudioPermission: Boolean,
        audioRecordInitialized: Boolean,
        recordingStarted: Boolean,
        securityOrUnsupported: Boolean = false
    ): Boolean {
        if (!apiAtLeastQ) return true
        if (!hasRecordAudioPermission) return true
        if (securityOrUnsupported) return true
        if (!audioRecordInitialized) return true
        if (!recordingStarted) return true
        return false
    }

    /**
     * Explicit policy: idle silence is never a capture failure.
     * Always false — samples/RMS are evidence of idle, not of broken capture.
     */
    @Suppress("UNUSED_PARAMETER")
    fun silenceOrZeroEnergyMeansCaptureFailed(samplesRead: Int, rms: Float): Boolean = false

    /**
     * Whether beginSession should fail Start after AudioRecord setup.
     * [hardInitFailed] covers STATE_UNINITIALIZED / permission / security.
     * [samplesRead]/[rms] are ignored for failure (silence OK).
     */
    fun shouldFailStartAfterInit(
        hardInitFailed: Boolean,
        samplesRead: Int = 0,
        rms: Float = 0f
    ): Boolean {
        if (hardInitFailed) return true
        return silenceOrZeroEnergyMeansCaptureFailed(samplesRead, rms)
    }

    /**
     * Successful playback capture init — including when nothing is playing yet.
     */
    fun captureInitSucceeded(
        audioRecordInitialized: Boolean,
        recordingStarted: Boolean,
        firstReadSamples: Int = 0,
        firstReadRms: Float = 0f
    ): Boolean {
        if (!audioRecordInitialized || !recordingStarted) return false
        // Silence must not flip success → failure.
        if (silenceOrZeroEnergyMeansCaptureFailed(firstReadSamples, firstReadRms)) return false
        return true
    }
}
