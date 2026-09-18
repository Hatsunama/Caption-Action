package com.hatsunama.captionaction.service

/**
 * Pure policy for Start after MediaProjection has been claimed (JVM-testable).
 *
 * Regression (0.3.7→0.3.8): a VirtualDisplay keep-alive hard-fail aborted Start
 * with error_capture even though 0.3.6 successfully reached Listening on Home
 * after Allow without that gate. Keep-alive is best-effort hygiene for API 34+;
 * only a hard playback-capture init failure may fail Start.
 *
 * Fresh createScreenCaptureIntent (0.3.7 ProjectionFreshStart) stays required —
 * this gate does not restore stale-grant reuse.
 */
object ProjectionCaptureStartGate {

    /**
     * Whether beginSession should abort with error_capture after a successful claim.
     *
     * [keepAliveDisplayFailed] is intentionally ignored — a failed 2×2 VirtualDisplay
     * must not paper over or replace the real AudioRecord init result.
     * [playbackCaptureStarted] false → hard fail (same as 0.3.6).
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldFailStartAfterClaim(
        keepAliveDisplayFailed: Boolean,
        playbackCaptureStarted: Boolean
    ): Boolean = !playbackCaptureStarted

    /** Keep-alive must never be the sole reason Start fails. */
    fun keepAliveFailureAloneFailsStart(): Boolean = false
}
