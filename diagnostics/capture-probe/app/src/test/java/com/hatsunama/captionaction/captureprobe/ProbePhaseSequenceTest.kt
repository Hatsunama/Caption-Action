package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Test

class ProbePhaseSequenceTest {
    @Test fun firstPhaseReleaseFailureAbortsBeforeNextAllocationReportOrDisarm() {
        val state = ProbePhaseSequence()
        val allocations = mutableListOf<ProbePhase>(); val reports = mutableListOf<ProbePhase>()
        var watchdogDisarmed = false; var published = false
        try {
            for (phase in ProbePhase.values()) {
                state.beginPhase(); allocations.add(phase)
                val released = releaseProbeAudio({}, { if (phase == ProbePhase.SILENT) error("source release") }, {}, {}, {}, {})
                state.finishPhase(released)
                reports.add(phase)
            }
        } catch (_: IllegalStateException) { }
        assertEquals("failed first teardown must prevent all later source/record allocations", listOf(ProbePhase.SILENT), allocations)
        assertTrue("failed teardown must not report even a per-phase PASS", reports.isEmpty())
        assertFalse(finalizeProbeSession(state.canDisarm(), {}, { watchdogDisarmed = true }, { published = true }))
        assertFalse(watchdogDisarmed); assertFalse(published)
    }
    @Test fun swallowedFailureCannotBeResetByLaterBeginOrSuccessfulFinish() {
        val state = ProbePhaseSequence(); state.beginPhase()
        try { state.finishPhase(false) } catch (_: IllegalStateException) {}
        var nextBegan = false
        try { state.beginPhase(); nextBegan = true } catch (_: IllegalStateException) {}
        assertFalse(nextBegan)
        try { state.finishPhase(true) } catch (_: IllegalStateException) {}
        assertFalse(state.canDisarm())
    }
    @Test fun successfulPhasesAllowDisarmOnlyAfterEachTeardownReturns() {
        val state = ProbePhaseSequence()
        for (phase in ProbePhase.values()) {
            state.beginPhase(); assertFalse(state.canDisarm())
            state.finishPhase(true); assertTrue(state.canDisarm())
        }
    }
}
