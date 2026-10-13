package com.hatsunama.captionaction.captureprobe

/** Native stops may block: caller must keep its independent watchdog and guards armed. */
fun releaseProbeAudio(
    pauseSource: () -> Unit,
    releaseSource: () -> Unit,
    stopRecord: () -> Unit,
    releaseRecord: () -> Unit,
    onSourcePaused: () -> Unit,
    onSourceReleased: () -> Unit
): Boolean {
    var sourceReleased = false
    var recordReleased = false
    try { pauseSource(); onSourcePaused() } catch (_: Exception) {}
    try { releaseSource(); sourceReleased = true; onSourceReleased() } catch (_: Exception) {}
    try { stopRecord() } catch (_: Exception) {}
    try { releaseRecord(); recordReleased = true } catch (_: Exception) {}
    return sourceReleased && recordReleased
}
