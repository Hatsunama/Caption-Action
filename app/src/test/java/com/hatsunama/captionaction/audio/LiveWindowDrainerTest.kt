package com.hatsunama.captionaction.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveWindowDrainerTest {
    private fun pcm(start: Int, size: Int) = ShortArray(size) { (start + it).toShort() }

    @Test
    fun backlog_returnsNewestWindowRatherThanOldestBatch() = runBlocking {
        val queue = ArrayDeque((0 until 6).map { pcm(it * 8, 8) })
        val result = LiveWindowDrainer().drain(18, { queue.poll() }, { queue.size }, { queue.poll() })
        assertArrayEquals(pcm(30, 18), result)
        assertEquals(0, queue.size)
    }

    @Test
    fun ordinaryNonAlignedWindows_preserveEverySampleExactlyOnce() = runBlocking {
        val input = ArrayDeque((0 until 10).map { pcm(it * 8, 8) })
        val drainer = LiveWindowDrainer()
        val output = ArrayList<Short>()
        while (true) {
            // Simulate chunks arriving only when the consumer waits for them.
            val window = drainer.drain(18, { input.poll() }, { 0 }, { null }) ?: break
            output.addAll(window.toList())
        }
        assertArrayEquals(pcm(0, 80), output.toShortArray())
    }

    @Test
    fun alignedWindows_doNotKeepOrRepeatSamples() = runBlocking {
        val input = ArrayDeque((0 until 4).map { pcm(it * 8, 8) })
        val drainer = LiveWindowDrainer()
        assertArrayEquals(pcm(0, 16), drainer.drain(16, { input.poll() }, { 0 }, { null }))
        assertArrayEquals(pcm(16, 16), drainer.drain(16, { input.poll() }, { 0 }, { null }))
        assertNull(drainer.drain(16, { input.poll() }, { 0 }, { null }))
    }

    @Test
    fun captureEnd_flushesPartialAndOvershootRemainder() = runBlocking {
        val input = ArrayDeque(listOf(pcm(0, 8), pcm(8, 8), pcm(16, 8)))
        val drainer = LiveWindowDrainer()
        assertArrayEquals(pcm(0, 18), drainer.drain(18, { input.poll() }, { 0 }, { null }))
        assertArrayEquals(pcm(18, 6), drainer.drain(18, { input.poll() }, { 0 }, { null }))
        assertNull(drainer.drain(18, { input.poll() }, { 0 }, { null }))
    }

    @Test
    fun captureEnd_flushesShortWindowWithoutPadding() = runBlocking {
        val input = ArrayDeque(listOf(pcm(0, 7)))
        assertArrayEquals(pcm(0, 7), LiveWindowDrainer().drain(18, { input.poll() }, { 0 }, { null }))
    }

    @Test
    fun catchupSnapshot_isBoundedEvenWhenPumpKeepsAddingChunks() = runBlocking {
        val queue = ArrayDeque(listOf(pcm(0, 8), pcm(8, 8), pcm(16, 8)))
        var next = 24
        var polls = 0
        val result = LiveWindowDrainer().drain(8, { queue.poll() }, { queue.size }, {
            polls++
            val chunk = queue.poll()
            queue.add(pcm(next, 8))
            next += 8
            chunk
        })
        assertArrayEquals(pcm(16, 8), result)
        assertEquals(2, polls)
        assertEquals(2, queue.size)
    }

    @Test
    fun newBacklog_replacesPreviouslySavedRemainderWithNewestContiguousWindow() = runBlocking {
        val drainer = LiveWindowDrainer()
        var input = ArrayDeque((0 until 3).map { pcm(it * 8, 8) })
        assertArrayEquals(pcm(0, 18), drainer.drain(18, { input.poll() }, { 0 }, { null }))
        input = ArrayDeque((3 until 9).map { pcm(it * 8, 8) })
        assertArrayEquals(pcm(54, 18), drainer.drain(18, { input.poll() }, { input.size }, { input.poll() }))
        assertNull(drainer.drain(18, { null }, { 0 }, { null }))
    }

    @Test
    fun smallerTarget_consumesRemainderWithoutWaitingOrDropping() = runBlocking {
        val drainer = LiveWindowDrainer()
        val input = ArrayDeque(listOf(pcm(0, 8)))
        assertArrayEquals(pcm(0, 3), drainer.drain(3, { input.poll() }, { 0 }, { null }))
        assertArrayEquals(pcm(3, 2), drainer.drain(2, { error("must use remainder") }, { 0 }, { null }))
        assertArrayEquals(pcm(5, 3), drainer.drain(10, { null }, { 0 }, { null }))
    }

    @Test
    fun reset_discardsPreviousSessionRemainder() = runBlocking {
        val drainer = LiveWindowDrainer()
        drainer.drain(3, { pcm(0, 8) }, { 0 }, { null })
        drainer.reset()
        assertNull(drainer.drain(3, { null }, { 0 }, { null }))
    }

    @Test
    fun resetDuringDrain_discardsOldResultAndRemainder() = runBlocking {
        val drainer = LiveWindowDrainer()
        val paused = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        var oldResult: ShortArray? = pcm(-1, 1)
        val oldDrain = launch(Dispatchers.Default) {
            oldResult = drainer.drain(3, { pcm(0, 8) }, {
                paused.complete(Unit)
                check(resume.await(5, TimeUnit.SECONDS))
                0
            }, { null })
        }
        try {
            withTimeout(5_000) { paused.await() }
            drainer.reset()
            resume.countDown()
            oldDrain.join()
            assertNull(oldResult)
            assertNull(drainer.drain(3, { null }, { 0 }, { null }))
        } finally {
            resume.countDown()
            oldDrain.cancelAndJoin()
        }
    }

    @Test
    fun resetAndRestart_oldDrainCannotOverwriteNewSessionRemainder() = runBlocking {
        val drainer = LiveWindowDrainer()
        val paused = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        var oldResult: ShortArray? = pcm(-1, 1)
        val oldDrain = launch(Dispatchers.Default) {
            oldResult = drainer.drain(3, { pcm(0, 8) }, {
                paused.complete(Unit)
                check(resume.await(5, TimeUnit.SECONDS))
                0
            }, { null })
        }
        try {
            withTimeout(5_000) { paused.await() }
            drainer.reset()
            assertArrayEquals(pcm(100, 3), drainer.drain(3, { pcm(100, 8) }, { 0 }, { null }))
            resume.countDown()
            oldDrain.join()
            assertArrayEquals(pcm(103, 5), drainer.drain(5, { null }, { 0 }, { null }))
            assertNull(oldResult)
            assertNull(drainer.drain(3, { null }, { 0 }, { null }))
        } finally {
            resume.countDown()
            oldDrain.cancelAndJoin()
        }
    }

    @Test
    fun cancelledDrain_discardsResultAndDoesNotSaveRemainder() = runBlocking {
        val drainer = LiveWindowDrainer()
        val paused = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        var oldResult: ShortArray? = pcm(-1, 1)
        val oldDrain = launch(Dispatchers.Default) {
            oldResult = drainer.drain(3, { pcm(0, 8) }, {
                paused.complete(Unit)
                check(resume.await(5, TimeUnit.SECONDS))
                0
            }, { null })
        }
        try {
            withTimeout(5_000) { paused.await() }
            oldDrain.cancel()
            resume.countDown()
            oldDrain.join()
            assertNull(oldResult)
            assertNull(drainer.drain(3, { null }, { 0 }, { null }))
        } finally {
            resume.countDown()
            oldDrain.cancelAndJoin()
        }
    }

    @Test
    fun resetDuringBlockedTake_doesNotDrainRestartedQueue() = runBlocking {
        val drainer = LiveWindowDrainer()
        val paused = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        var oldResult: ShortArray? = pcm(-1, 1)
        var queuedChecks = 0
        val oldDrain = launch(Dispatchers.Default) {
            oldResult = drainer.drain(3, {
                paused.complete(Unit)
                check(resume.await(5, TimeUnit.SECONDS))
                pcm(0, 8)
            }, { queuedChecks++; 1 }, { pcm(100, 8) })
        }
        try {
            withTimeout(5_000) { paused.await() }
            drainer.reset()
            resume.countDown()
            oldDrain.join()
            assertEquals(0, queuedChecks)
            assertNull(oldResult)
            assertNull(drainer.drain(3, { null }, { 0 }, { null }))
        } finally {
            resume.countDown()
            oldDrain.cancelAndJoin()
        }
    }

    @Test
    fun resetDuringCatchup_discardsResultBeforePublication() = runBlocking {
        val drainer = LiveWindowDrainer()
        val queue = ArrayDeque(listOf(pcm(0, 8), pcm(8, 8)))
        val result = drainer.drain(3, { queue.poll() }, { queue.size }, { queue.poll() }, {
            drainer.reset()
        })
        assertNull(result)
        assertNull(drainer.drain(3, { null }, { 0 }, { null }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroTarget_isRejected() = runBlocking {
        LiveWindowDrainer().drain(0, { null }, { 0 }, { null })
        Unit
    }
}
