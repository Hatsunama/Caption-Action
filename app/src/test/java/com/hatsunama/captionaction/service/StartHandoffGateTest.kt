package com.hatsunama.captionaction.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fails on the legacy 8s / handoff-only gate; passes after Seeker-safe handoff fix.
 */
class StartHandoffGateTest {

    private var now = 0L
    private lateinit var gate: StartHandoffGate

    @Before
    fun setUp() {
        now = 1_000L
        gate = StartHandoffGate(clock = { now })
    }

    @Test
    fun defaultWindow_coversSeekerLauncherHandoff_atLeast30s() {
        assertTrue(
            "handoff window must be >= 30s for Seeker projection+launcher handoff, was ${StartHandoffGate.DEFAULT_WINDOW_MS}",
            StartHandoffGate.DEFAULT_WINDOW_MS >= 30_000L
        )
        assertTrue(
            "prefer 45–60s suppress covering slow OEMs, was ${StartHandoffGate.DEFAULT_WINDOW_MS}",
            StartHandoffGate.DEFAULT_WINDOW_MS in 30_000L..60_000L
        )
    }

    @Test
    fun begin_beforeProjectionDialog_stillSuppressesAfterUserTakes20s() {
        // Start taps Start → begin handoff → system projection dialog (slow Allow).
        gate.begin(now)
        now += 20_000L
        assertTrue(
            "20s on projection dialog must still be inside handoff window",
            gate.shouldSuppressHomeAutoStop(now)
        )
    }

    @Test
    fun extendOnProjectionGranted_keepsSuppressThroughGoToLauncherHome() {
        gate.begin(now)
        now += 25_000L // dialog dwell expired old 8s window
        // Grant path must extend.
        gate.extend(now)
        gate.markServiceStarted(now)
        now += 15_000L // Seeker: Home may resume well after minimize
        assertTrue(gate.shouldSuppressHomeAutoStop(now))
        assertFalse(gate.shouldAutoStopOnHomeResume(serviceRunning = true, now = now))
    }

    @Test
    fun onResume_neverStopsIfServiceStartedWithinWindow_evenIfHandoffEnded() {
        gate.begin(now)
        gate.markServiceStarted(now)
        gate.end() // handoff cleared, but service just started
        now += 10_000L
        assertTrue(
            "recent service start must suppress Home auto-stop",
            gate.shouldSuppressHomeAutoStop(now)
        )
        assertFalse(gate.shouldAutoStopOnHomeResume(serviceRunning = true, now = now))
    }

    @Test
    fun onResume_stopsWhenNoHandoffAndServiceOld() {
        gate.begin(now)
        gate.markServiceStarted(now)
        now += StartHandoffGate.DEFAULT_WINDOW_MS + 1_000L
        gate.end()
        assertTrue(gate.shouldAutoStopOnHomeResume(serviceRunning = true, now = now))
        assertFalse(gate.shouldSuppressHomeAutoStop(now))
    }

    @Test
    fun onResume_noOpWhenServiceNotRunning() {
        gate.begin(now)
        assertFalse(gate.shouldAutoStopOnHomeResume(serviceRunning = false, now = now))
    }

    @Test
    fun projectionResult_RESULT_OK_minusOne_isGranted() {
        // Activity.RESULT_OK == -1; require(resultCode != 0) must accept it.
        assertTrue(StartHandoffGate.isProjectionResultGranted(resultCode = -1, hasData = true))
        assertFalse(StartHandoffGate.isProjectionResultGranted(resultCode = 0, hasData = true))
        assertFalse(StartHandoffGate.isProjectionResultGranted(resultCode = -1, hasData = false))
    }

    @Test
    fun end_clearsHandoff_forDeclinePath() {
        gate.begin(now)
        gate.end()
        assertFalse(gate.isHandoffActive(now))
        assertFalse(gate.shouldSuppressHomeAutoStop(now))
    }
}
