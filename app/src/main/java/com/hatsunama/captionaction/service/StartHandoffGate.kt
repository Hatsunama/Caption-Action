package com.hatsunama.captionaction.service

/**
 * Pure start→projection→launcher handoff timing (JVM-testable).
 * HomeActivity.onResume must not kill a session while this gate says suppress.
 *
 * Window is long enough for Seeker: projection dialog dwell + grant + goToLauncherHome
 * + brief Home resume before the launcher settles.
 */
class StartHandoffGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { 0L }
) {
    @Volatile
    private var handoffUntil: Long = 0L

    @Volatile
    private var serviceStartedAt: Long = NOT_STARTED

    fun begin(now: Long = clock()) {
        handoffUntil = now + windowMs
    }

    /** Extend/restart the suppress window (e.g. when projection is granted). */
    fun extend(now: Long = clock()) {
        begin(now)
    }

    fun end() {
        handoffUntil = 0L
    }

    fun markServiceStarted(now: Long = clock()) {
        serviceStartedAt = now
        // Keep suppress aligned with service start (grant path).
        begin(now)
    }

    fun clearServiceStarted() {
        serviceStartedAt = NOT_STARTED
    }

    fun isHandoffActive(now: Long = clock()): Boolean =
        now < handoffUntil

    /**
     * True when HomeActivity.onResume must not auto-stop the overlay service:
     * handoff active OR overlay service started within [windowMs].
     */
    fun shouldSuppressHomeAutoStop(now: Long = clock()): Boolean {
        if (now < handoffUntil) return true
        if (serviceStartedAt != NOT_STARTED && now - serviceStartedAt < windowMs) return true
        return false
    }

    fun shouldAutoStopOnHomeResume(
        serviceRunning: Boolean,
        now: Long = clock()
    ): Boolean = serviceRunning && !shouldSuppressHomeAutoStop(now)

    companion object {
        /** Covers Seeker projection dialog + launcher handoff (30–60s). */
        const val DEFAULT_WINDOW_MS = 45_000L
        const val NOT_STARTED = Long.MIN_VALUE

        /** RESULT_OK is -1; canceled is 0. Any non-zero + data means granted. */
        fun isProjectionResultGranted(resultCode: Int, hasData: Boolean): Boolean =
            resultCode != 0 && hasData
    }
}
