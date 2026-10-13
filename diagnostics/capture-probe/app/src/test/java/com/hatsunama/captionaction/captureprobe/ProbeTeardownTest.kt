package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProbeTeardownTest {
    @Test fun failingSourceReleaseStillReleasesRecordAndCannotDisarm() {
        val calls = mutableListOf<String>()
        val verified = releaseProbeAudio(
            { calls.add("pause") }, { calls.add("sourceRelease"); error("native") },
            { calls.add("stopRecord") }, { calls.add("recordRelease") },
            { calls.add("paused") }, { calls.add("released") })
        assertFalse(verified)
        assertEquals(listOf("pause", "paused", "sourceRelease", "stopRecord", "recordRelease"), calls)
    }
    @Test fun failedRecordReleaseCannotDisarm() {
        assertFalse(releaseProbeAudio({}, {}, {}, { error("native") }, {}, {}))
    }
    @Test fun failedStopsStillAttemptReleases() {
        var source = false; var record = false
        assertTrue(releaseProbeAudio({ error("pause") }, { source = true }, { error("stop") }, { record = true }, {}, {}))
        assertTrue(source && record)
    }
    @Test fun blockedPauseDoesNotDeclareSourceStoppedOrTeardownVerified() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val paused = AtomicBoolean(false); val verified = AtomicBoolean(false)
        val worker = Thread {
            verified.set(releaseProbeAudio({ entered.countDown(); check(resume.await(2, TimeUnit.SECONDS)) }, {}, {}, {}, { paused.set(true) }, {}))
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(paused.get()); assertFalse(verified.get())
        } finally { resume.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive); assertTrue(paused.get()); assertTrue(verified.get())
    }
}
