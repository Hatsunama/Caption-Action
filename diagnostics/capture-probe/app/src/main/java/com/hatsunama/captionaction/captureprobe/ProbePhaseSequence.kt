package com.hatsunama.captionaction.captureprobe

/** A failed/uncertain teardown poisons the entire session; no later phase can reset it. */
class ProbePhaseSequence {
    private var verified = true
    private var inPhase = false
    private var poisoned = false

    @Synchronized fun beginPhase() {
        check(!poisoned) { "earlier_phase_teardown_failed" }
        if (inPhase || !verified) {
            poisoned = true
            error("previous_phase_not_released")
        }
        inPhase = true
        verified = false
    }

    @Synchronized fun finishPhase(released: Boolean) {
        if (poisoned || !inPhase || !released) {
            poisoned = true
            verified = false
            inPhase = false
            error("phase_teardown_unverified")
        }
        inPhase = false
        verified = true
    }

    @Synchronized fun canDisarm(): Boolean = verified && !inPhase && !poisoned
}
