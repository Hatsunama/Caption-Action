package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProbeFinalizationTest {
    @Test fun verifiedTeardownPrecedesDisarmAndPublication() {
        val calls = mutableListOf<String>()
        assertTrue(finalizeProbeSession(true, { calls.add("projectionStop") }, { calls.add("disarm") }, { calls.add("publish") }))
        assertEquals(listOf("projectionStop", "disarm", "publish"), calls)
    }
    @Test fun projectionStopFailureLeavesWatchdogArmedAndCannotPublish() {
        val disarmed = AtomicBoolean(false); val published = AtomicBoolean(false)
        assertFalse(finalizeProbeSession(true, { error("projection stop") }, { disarmed.set(true) }, { published.set(true) }))
        assertFalse(disarmed.get()); assertFalse(published.get())
    }
    @Test fun unverifiedNativeReleaseLeavesWatchdogArmedEvenAfterProjectionStops() {
        var projectionStopped = false; var disarmed = false; var published = false
        assertFalse(finalizeProbeSession(false, { projectionStopped = true }, { disarmed = true }, { published = true }))
        assertTrue(projectionStopped); assertFalse(disarmed); assertFalse(published)
    }
    @Test fun cancellationCleansUpButDoesNotPublishResult() {
        val calls = mutableListOf<String>()
        assertTrue(finalizeProbeSession(true, { calls.add("projectionStop") }, { calls.add("disarm") }, null))
        assertEquals(listOf("projectionStop", "disarm"), calls)
    }
    @Test fun stalledProjectionStopCannotDisarmOrPublish() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val disarmed = AtomicBoolean(false); val published = AtomicBoolean(false)
        val worker = Thread {
            finalizeProbeSession(true, { entered.countDown(); check(resume.await(2, TimeUnit.SECONDS)) }, { disarmed.set(true) }, { published.set(true) })
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(disarmed.get()); assertFalse(published.get())
        } finally { resume.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive); assertTrue(disarmed.get()); assertTrue(published.get())
    }

    @Test fun cancellationDuringProjectionStopPreventsLatePassPublication() {
        var active = true; var disarmed = false; var published = false
        assertTrue(finalizeProbeSession(true, { active = false }, { disarmed = true }, { published = true }, { active }))
        assertTrue(disarmed); assertFalse(published)
    }
}
