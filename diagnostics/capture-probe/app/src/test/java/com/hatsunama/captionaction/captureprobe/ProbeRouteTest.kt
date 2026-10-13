package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Test

class ProbeRouteTest {
    @Test fun passiveTelephonyInventoryIsNotASelectedRoute() {
        assertTrue(safeAvailableOutputs(listOf(2, 18)))
        assertTrue(safeAvailableOutputs(listOf(1, 2, 18)))
        assertFalse(safeAvailableOutputs(listOf(18)))
        assertFalse(safeAvailableOutputs(emptyList()))
    }
    @Test fun externalOrUnknownInventoryRemainsBlocked() {
        for (type in listOf(0, 3, 4, 7, 8, 9, 11, 12, 19, 22, 25, 26, 999)) {
            assertFalse("type=$type", safeAvailableOutputs(listOf(2, 18, type)))
        }
    }
    @Test fun actualRouteMustBeTheExactBuiltInSpeaker() {
        assertTrue(isExpectedSpeakerRoute(4, 4, 2))
        assertFalse(isExpectedSpeakerRoute(4, 4, 18))
        assertFalse(isExpectedSpeakerRoute(4, 4, 8))
        assertFalse(isExpectedSpeakerRoute(4, 5, 2))
        assertFalse(isExpectedSpeakerRoute(4, null, null))
        assertFalse(isExpectedSpeakerRoute(-1, -1, 2))
    }
    @Test fun passiveTelephonyNeverOverridesOtherSafetyChecks() {
        val external = !safeAvailableOutputs(listOf(2, 18))
        assertTrue(safeOutput(0, false, external, true))
        assertFalse(safeOutput(1, false, external, true))
        assertFalse(safeOutput(0, true, external, true))
        assertFalse(safeOutput(0, false, external, false))
    }
}
