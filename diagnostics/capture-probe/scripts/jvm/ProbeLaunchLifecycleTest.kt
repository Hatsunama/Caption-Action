package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProbeLaunchLifecycleTest {
    @Before fun reset() { ProbeState.running = false; ProbeState.status = ""; Log.published.clear() }
    private fun armed(s: LaunchFixture) {
        assertTrue(ProbeState.running); assertTrue(s.safetyArmed)
        assertTrue(s.handler.armed); assertTrue(s.audio.armed); assertTrue(s.deadline.armed)
    }
    private fun disarmed(s: LaunchFixture) {
        assertFalse("running must clear through finally", ProbeState.running)
        assertFalse(s.active); assertFalse(s.safetyArmed)
        assertFalse(s.handler.armed); assertFalse(s.audio.armed); assertFalse(s.deadline.armed)
        assertEquals(1, s.stopCalls)
    }
    @Test fun cancelBeforeFirstDispatchRunsEmptyVerifiedCleanup() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q)
        s.start(); armed(s); assertEquals(1, q.size())
        s.onDestroy(); armed(s)
        q.drain()
        assertEquals(0, s.manager.acquired); assertEquals(0, s.audioSetups)
        assertEquals(0, s.manager.projection.stopped); assertTrue(s.phaseSequence.canDisarm())
        assertTrue(Log.published.isEmpty()); disarmed(s)
    }
    @Test fun ordinaryCancellationReleasesBeforeDisarmWithoutPublish() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q)
        s.start(); q.drain(); assertEquals(1, s.audioSetups); armed(s)
        s.onDestroy(); q.drain()
        assertEquals(1, s.manager.projection.stopped); assertTrue(s.phaseSequence.canDisarm())
        assertTrue(Log.published.isEmpty()); disarmed(s)
    }
    @Test fun projectionStopFailureKeepsGuardsArmed() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q)
        s.manager.projection.failStop = true
        s.start(); q.drain(); s.onDestroy(); q.drain()
        assertEquals(1, s.manager.projection.stopped); armed(s)
        assertTrue(ProbeState.status.startsWith("TEARDOWN UNVERIFIED")); assertTrue(Log.published.isEmpty())
    }
    @Test fun audioReleaseFailureKeepsGuardsArmed() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q)
        s.failAudioRelease = true
        s.start(); q.drain(); s.onDestroy(); q.drain()
        assertFalse(s.phaseSequence.canDisarm()); armed(s)
        assertEquals(1, s.manager.projection.stopped); assertTrue(Log.published.isEmpty())
    }
    @Test fun queuedStopThenFreshRestartDoesNotInheritStuckRunning() {
        val q = QueuedDispatcher(); val old = LaunchFixture(q)
        old.start(); old.onDestroy()
        assertTrue("Activity must block Begin until old cleanup finishes", ProbeState.running)
        q.drain(); disarmed(old)
        val fresh = LaunchFixture(q); fresh.start(); armed(fresh)
        fresh.onDestroy(); q.drain(); disarmed(fresh)
        assertEquals(0, old.manager.acquired); assertEquals(0, fresh.manager.acquired)
        assertTrue(Log.published.isEmpty())
    }
    @Test fun alreadyCancelledScopeStillRunsEmptyCleanup() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q)
        s.onDestroy(); s.start(); q.drain()
        assertEquals(0, s.manager.acquired); assertTrue(Log.published.isEmpty()); disarmed(s)
    }
    @Test fun uninterruptedRunStillPublishesOnlyAfterVerifiedTeardown() {
        val q = QueuedDispatcher(); val s = LaunchFixture(q); s.complete = true
        s.start(); q.drain(); disarmed(s)
        assertEquals(3, s.audioSetups); assertEquals(1, s.manager.projection.stopped)
        assertEquals(1, Log.published.size); assertTrue(s.finished)
    }
}
