package com.hatsunama.captionaction.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: silence / zero energy at Start must never be treated as capture failure.
 * Only hard AudioRecord / permission / API init errors fail Start.
 */
class PlaybackCaptureStartGateTest {

    @Test
    fun silenceZeroSamples_isNotCaptureFailure() {
        assertFalse(
            "zero samples at start is idle silence — session must stay live",
            PlaybackCaptureStartGate.silenceOrZeroEnergyMeansCaptureFailed(
                samplesRead = 0,
                rms = 0f
            )
        )
        assertFalse(
            PlaybackCaptureStartGate.shouldFailStartAfterInit(
                hardInitFailed = false,
                samplesRead = 0,
                rms = 0f
            )
        )
    }

    @Test
    fun shortFirstRead_zeroRms_isNotCaptureFailure() {
        assertFalse(
            "short first read with zero RMS is still silence, not a hard fail",
            PlaybackCaptureStartGate.silenceOrZeroEnergyMeansCaptureFailed(
                samplesRead = 64,
                rms = 0f
            )
        )
        assertFalse(
            PlaybackCaptureStartGate.shouldFailStartAfterInit(
                hardInitFailed = false,
                samplesRead = 64,
                rms = 0f
            )
        )
    }

    @Test
    fun nearZeroRms_withSomeSamples_isNotCaptureFailure() {
        assertFalse(
            PlaybackCaptureStartGate.shouldFailStartAfterInit(
                hardInitFailed = false,
                samplesRead = 8_000,
                rms = 0.5f
            )
        )
    }

    @Test
    fun uninitializedAudioRecord_isHardFailure() {
        assertTrue(
            PlaybackCaptureStartGate.isHardCaptureFailure(
                apiAtLeastQ = true,
                hasRecordAudioPermission = true,
                audioRecordInitialized = false,
                recordingStarted = false
            )
        )
        assertTrue(
            PlaybackCaptureStartGate.shouldFailStartAfterInit(hardInitFailed = true)
        )
    }

    @Test
    fun missingRecordAudio_isHardFailure() {
        assertTrue(
            PlaybackCaptureStartGate.isHardCaptureFailure(
                apiAtLeastQ = true,
                hasRecordAudioPermission = false,
                audioRecordInitialized = true,
                recordingStarted = true
            )
        )
    }

    @Test
    fun preQ_isHardFailure() {
        assertTrue(
            PlaybackCaptureStartGate.isHardCaptureFailure(
                apiAtLeastQ = false,
                hasRecordAudioPermission = true,
                audioRecordInitialized = true,
                recordingStarted = true
            )
        )
    }

    @Test
    fun initializedRecording_evenSilent_isNotHardFailure() {
        assertFalse(
            PlaybackCaptureStartGate.isHardCaptureFailure(
                apiAtLeastQ = true,
                hasRecordAudioPermission = true,
                audioRecordInitialized = true,
                recordingStarted = true
            )
        )
        assertTrue(
            PlaybackCaptureStartGate.captureInitSucceeded(
                audioRecordInitialized = true,
                recordingStarted = true,
                firstReadSamples = 0,
                firstReadRms = 0f
            )
        )
    }

    @Test
    fun securityException_isHardFailure_notSilence() {
        assertTrue(
            PlaybackCaptureStartGate.isHardCaptureFailure(
                apiAtLeastQ = true,
                hasRecordAudioPermission = true,
                audioRecordInitialized = false,
                recordingStarted = false,
                securityOrUnsupported = true
            )
        )
    }

    @Test
    fun projectionFreshFailKind_playbackFail_stillDistinctFromDecline() {
        // Cascade: silence must not reach PLAYBACK_CAPTURE_FAILED; when it does
        // (hard init only), copy stays error_capture — never decline / allow-sharing.
        val kind = ProjectionFreshStart.failKind(
            wantedProjection = true,
            projectionClaimed = true,
            playbackCaptureStarted = false
        )
        assertTrue(kind == ProjectionFreshStart.FailKind.PLAYBACK_CAPTURE_FAILED)
        assertFalse(kind.useDeclineCopy)
    }
}
