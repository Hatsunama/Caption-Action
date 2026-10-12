package com.hatsunama.captionaction.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelGateSessionTest {
    @Test fun dismissDiscardsLateReady() {
        val gate = ModelGateSession()
        gate.close()
        assertFalse(gate.isActive)
        assertFalse(gate.complete { fail("Dismissed dialog started capture") })
    }

    @Test fun successStartsOnlyOnceEvenIfDismissReenters() {
        val gate = ModelGateSession()
        var starts = 0
        assertTrue(gate.complete {
            gate.close() // Dismiss listener can run while success is delivered.
            starts++
        })
        assertFalse(gate.complete { starts++ })
        assertEquals(1, starts)
    }

    @Test fun reopeningDoesNotReviveOldDialog() {
        val old = ModelGateSession()
        old.close()
        val current = ModelGateSession()
        assertFalse(old.complete { fail("Stale gate started capture") })
        old.close() // A delayed old onDismiss must not invalidate the new session.
        assertTrue(current.isActive)
        assertTrue(current.complete {})
    }

    @Test fun dismissalWhileSettingsWriteSuspendsSuppressesReady() = runBlocking {
        val gate = ModelGateSession()
        val settingsFinished = CompletableDeferred<Unit>()
        var started = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            settingsFinished.await()
            gate.complete { started = true }
        }
        gate.close()
        settingsFinished.complete(Unit)
        job.join()
        assertFalse(started)
    }
}
