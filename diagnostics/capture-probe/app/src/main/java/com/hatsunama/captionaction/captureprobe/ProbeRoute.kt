package com.hatsunama.captionaction.captureprobe

/**
 * Public AudioDeviceInfo type values (API29+): earpiece=1, speaker=2, telephony=18.
 * TELEPHONY is an internal available port, not proof that this track routes there.
 * Keep external and unknown inventory blocked; require a built-in speaker.
 * This inventory check never replaces the owned track's actual-route check.
 */
fun safeAvailableOutputs(types: List<Int>): Boolean =
    2 in types && types.all { it == 1 || it == 2 || it == 18 }

/** A preferred device is advisory. Read the actual device after playback starts. */
fun isExpectedSpeakerRoute(expectedId: Int, actualId: Int?, actualType: Int?): Boolean =
    expectedId > 0 && actualId == expectedId && actualType == 2
