package com.hatsunama.captionaction.captureprobe

/** Cleanup ownership: only verified native + projection teardown may disarm safety and publish. */
fun finalizeProbeSession(
    nativeTeardownVerified: Boolean,
    stopProjection: () -> Unit,
    disarm: () -> Unit,
    publish: (() -> Unit)?,
    mayPublish: () -> Boolean = { true }
): Boolean {
    try { stopProjection() } catch (_: Exception) { return false }
    if (!nativeTeardownVerified) return false
    disarm()
    if (mayPublish()) publish?.invoke()
    return true
}
