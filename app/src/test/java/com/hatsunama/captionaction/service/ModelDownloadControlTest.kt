package com.hatsunama.captionaction.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ModelDownloadControlTest {
    @Test fun cancelledBeforeIoStartsCannotAttachOrReset() {
        val control = ModelDownloadControl()
        control.cancel()
        var disconnected = false
        assertFalse(control.attach { disconnected = true })
        assertTrue(disconnected)
        assertTrue(control.isCancelled)
    }

    @Test fun cancelDisconnectsActiveRequestOnce() {
        val control = ModelDownloadControl()
        var disconnects = 0
        assertTrue(control.attach { disconnects++ })
        control.cancel()
        control.cancel()
        assertEquals(1, disconnects)
    }

    @Test fun finishedRequestIsDetached() {
        val control = ModelDownloadControl()
        control.attach { fail("A detached request was disconnected") }
        control.detach()
        control.cancel()
        assertTrue(control.isCancelled)
    }

    @Test fun reopenedDownloadCannotWriteUntilPreviousWriterCloses() = runBlocking {
        val root = Files.createTempDirectory("model-writers").toFile()
        try {
            val file = root.resolve("tiny.part")
            val release = CompletableDeferred<Unit>()
            var secondEntered = false
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                ModelDownloadLocks.withFile(file) {
                    file.writeText("first")
                    release.await() // Represents the first stream still closing after cancellation.
                    file.appendText("-closed")
                }
            }
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                ModelDownloadLocks.withFile(root.resolve("./tiny.part")) {
                    secondEntered = true
                    assertEquals("first-closed", file.readText())
                    file.appendText("-resumed")
                }
            }
            assertFalse(secondEntered)
            release.complete(Unit)
            first.join()
            second.join()
            assertEquals("first-closed-resumed", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellingQueuedAttemptDoesNotStealOrLeakFileOwnership() = runBlocking {
        val root = Files.createTempDirectory("model-queued").toFile()
        try {
            val file = root.resolve("tiny.part")
            val release = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                ModelDownloadLocks.withFile(file) { release.await() }
            }
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                ModelDownloadLocks.withFile(file) { fail("Cancelled waiter acquired file") }
            }
            queued.cancelAndJoin()
            release.complete(Unit)
            first.join()
            var acquired = false
            ModelDownloadLocks.withFile(file) { acquired = true }
            assertTrue(acquired)
        } finally { root.deleteRecursively() }
    }

    @Test fun failureReleasesWriterForResume() = runBlocking {
        val root = Files.createTempDirectory("model-failure").toFile()
        try {
            val file = root.resolve("tiny.part")
            try {
                ModelDownloadLocks.withFile(file) {
                    file.writeText("partial")
                    throw IllegalStateException("network stopped")
                }
            } catch (_: IllegalStateException) { }
            ModelDownloadLocks.withFile(file) { assertEquals("partial", file.readText()) }
        } finally { root.deleteRecursively() }
    }
}
