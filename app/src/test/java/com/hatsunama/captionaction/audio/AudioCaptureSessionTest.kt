package com.hatsunama.captionaction.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.projection.MediaProjection
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Latches order real AudioCapture calls; no microphone, timing sleeps, or model capture implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [AudioCaptureSessionTest.ControlledRecord::class])
class AudioCaptureSessionTest {
    class Read(val marker: Short, val size: Int = 8, val result: Int = size) {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fun allow() { resume.countDown() }
    }
    class Driver {
        val reads = LinkedBlockingQueue<Read>()
        val allocations = AtomicInteger()
        val releases = AtomicInteger()
        val stops = AtomicInteger()
        val stopped = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val released = CountDownLatch(1)
        var failStart = false
        var stopGate: CountDownLatch? = null
        var onAllocate: (() -> Unit)? = null
        fun read(marker: Int, size: Int = 8, result: Int = size) = Read(marker.toShort(), size, result).also { reads.add(it) }
    }
    @Implements(AudioRecord::class)
    class ControlledRecord {
        private var driver: Driver? = null
        @Implementation fun __constructor__(attributes: AudioAttributes, format: AudioFormat, bufferBytes: Int, sessionId: Int) {
            allocate()
        }
        @Implementation(minSdk = 31) fun __constructor__(attributes: AudioAttributes, format: AudioFormat,
            bufferBytes: Int, sessionId: Int, context: Context?, maxHistory: Int, halFlags: Int) {
            allocate()
        }
        private fun allocate() {
            if (driver != null) return
            driver = planned.remove()
            driver!!.allocations.incrementAndGet()
            driver!!.onAllocate?.invoke()
        }
        @Implementation fun getState(): Int = AudioRecord.STATE_INITIALIZED
        @Implementation fun getRecordingState(): Int = AudioRecord.RECORDSTATE_RECORDING
        @Implementation fun startRecording() {
            if (driver!!.failStart) throw IllegalStateException("controlled start failure")
        }
        @Implementation fun read(data: ShortArray, offset: Int, length: Int): Int {
            val operation = driver!!.reads.poll(5, TimeUnit.SECONDS) ?: return AudioRecord.ERROR_INVALID_OPERATION
            operation.entered.countDown()
            check(operation.resume.await(5, TimeUnit.SECONDS)) { "Read was never released" }
            if (operation.result > 0) data.fill(operation.marker, offset, offset + operation.size.coerceAtMost(length))
            return operation.result
        }
        @Implementation fun stop() {
            driver?.stops?.incrementAndGet()
            driver?.stopped?.countDown()
            driver?.stopGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            driver?.stopReturned?.countDown()
        }
        @Implementation fun release() { driver?.releases?.incrementAndGet(); driver?.released?.countDown() }
        companion object {
            val planned = LinkedBlockingQueue<Driver>()
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, encoding: Int): Int = 1024
        }
    }
    @Implements(AudioRecord.Builder::class)
    class ControlledBuilder {
        // Substitute only hardware allocation; production playback configuration/setters still execute.
        @Implementation fun build(): AudioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 96000
        )
    }

    private lateinit var capture: AudioCapture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reads = mutableListOf<Read>()
    @Before fun setup() {
        ControlledRecord.planned.clear()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        capture = AudioCapture(RuntimeEnvironment.getApplication())
    }
    @After fun cleanup() {
        capture.stop()
        reads.forEach { it.allow() }
        scope.cancel()
    }
    private fun await(latch: CountDownLatch) = assertTrue("deterministic boundary was not reached", latch.await(5, TimeUnit.SECONDS))
    private fun start(driver: Driver) {
        ControlledRecord.planned.add(driver)
        assertTrue("permission must be granted", capture.hasRecordAudioPermission())
        val started = capture.startMicrophoneCapture()
        assertTrue(org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable?.stackTraceToString()}" }, started)
        capture.startPump(scope)
    }
    private fun Driver.operation(marker: Int, size: Int = 8, result: Int = size): Read = read(marker, size, result).also { this@AudioCaptureSessionTest.reads.add(it) }
    private fun pumpJob(): Job {
        val owner = stateOwner()
        return owner.javaClass.getDeclaredField("pumpJob").apply { isAccessible = true }.get(owner) as Job
    }
    // Inject only a queue observation boundary, never substitute capture/session behavior.
    private fun stateOwner(): Any = try {
        capture.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(capture)!!
    } catch (_: NoSuchFieldException) { capture }
    private fun replaceQueue(queue: ArrayBlockingQueue<ShortArray>) {
        val owner = stateOwner()
        owner.javaClass.getDeclaredField("pcmQueue").apply { isAccessible = true }.set(owner, queue)
    }
    private class HeldPoll(private val takeBeforeHold: Boolean = false) : ArrayBlockingQueue<ShortArray>(24) {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val taken = ConcurrentLinkedQueue<ShortArray>()
        override fun poll(timeout: Long, unit: TimeUnit): ShortArray? {
            val before = if (takeBeforeHold) super.poll() else null
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            return (if (takeBeforeHold) before else super.poll(timeout, unit)).also {
                if (it != null) taken.add(it)
            }
        }
    }

    @Test fun lateOldReadCannotPublishSamplesOrMetricsIntoRestart() = runBlocking {
        val old = Driver()
        val a = old.operation(111)
        start(old)
        val oldJob = pumpJob()
        await(a.entered)
        capture.stop()
        val fresh = Driver()
        val b = fresh.operation(222)
        val end = fresh.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
        start(fresh)
        val newJob = pumpJob()
        b.allow(); await(end.entered) // next read proves B has been published
        a.allow(); oldJob.join()
        assertEquals("retired A must not count as current PCM", 1L, capture.chunkCount())
        assertEquals(222f, capture.lastRms())
        assertEquals(0L, capture.overrunCount())
        end.allow(); newJob.join()
        assertArrayEquals(ShortArray(8) { 222 }, capture.drainFifoWindow(8))
        assertNull(capture.takeChunk(1))
    }

    private fun stalePollCannotStealNewAudio(live: Boolean) = runBlocking {
        val old = Driver()
        val a = old.operation(111)
        start(old); await(a.entered)
        val oldJob = pumpJob()
        val held = HeldPoll()
        replaceQueue(held)
        val stale = async(Dispatchers.IO) {
            if (live) capture.drainToNewestWindow(3) else capture.drainFifoWindow(3)
        }
        await(held.entered)
        capture.stop()
        val fresh = Driver()
        val b = fresh.operation(222)
        val end = fresh.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
        start(fresh)
        val newJob = pumpJob()
        b.allow(); await(end.entered)
        held.resume.countDown()
        assertNull("retired consumer must not publish a window/remainder", stale.await())
        assertTrue("retired poll must never steal fresh PCM", held.taken.isEmpty())
        end.allow(); newJob.join()
        assertArrayEquals(ShortArray(3) { 222 }, capture.drainFifoWindow(3))
        assertArrayEquals(ShortArray(5) { 222 }, capture.drainFifoWindow(5))
        a.allow(); oldJob.join()
    }
    @Test fun staleFifoPollCannotStealNewChunkOrCommitRemainder() = stalePollCannotStealNewAudio(false)
    @Test fun staleLivePollCannotStealNewChunk() = stalePollCannotStealNewAudio(true)

    @Test fun failedStartReleasesAllocatedRecordOnce() {
        val failed = Driver().apply { failStart = true }
        ControlledRecord.planned.add(failed)
        assertFalse(capture.startMicrophoneCapture())
        capture.stop(); capture.stop()
        assertEquals(1, failed.releases.get())
    }
    @Test fun stopDefersReleaseUntilOwnedBlockingReadReturns() = runBlocking {
        val old = Driver(); val a = old.operation(111)
        start(old); val job = pumpJob(); await(a.entered)
        capture.stop(); capture.stop()
        assertEquals("native record is still being read", 0, old.releases.get())
        a.allow(); job.join(); await(old.released)
        assertEquals(1, old.releases.get())
        assertEquals(1, old.stops.get())
    }
    @Test fun oldFifoRemainderCannotOverwriteFreshSession() = runBlocking {
        val old = Driver(); val a = old.operation(111)
        start(old); await(a.entered); val oldJob = pumpJob()
        val held = HeldPoll(takeBeforeHold = true)
        held.offer(ShortArray(8) { 111 })
        replaceQueue(held)
        val stale = async(Dispatchers.IO) { capture.drainFifoWindow(3) }
        await(held.entered) // Old A removed; pause before the consumer can assemble its remainder.
        capture.stop()
        val fresh = Driver(); val b = fresh.operation(222)
        val end = fresh.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
        start(fresh); val newJob = pumpJob()
        b.allow(); await(end.entered)
        held.resume.countDown(); val staleResult = stale.await()
        end.allow(); newJob.join()
        assertArrayEquals("Old A remainder must not replace fresh B", ShortArray(3) { 222 }, capture.drainFifoWindow(3))
        assertArrayEquals(ShortArray(5) { 222 }, capture.drainFifoWindow(5))
        assertNull(staleResult)
        a.allow(); oldJob.join()
    }

    @Test fun platformStopFinishesBeforeReplacementRecordAllocation() = runBlocking {
        val old = Driver().apply { stopGate = CountDownLatch(1) }
        val a = old.operation(111)
        start(old); await(a.entered); val oldJob = pumpJob()
        val fresh = Driver().apply {
            onAllocate = { assertEquals("old hardware stop must return before next allocation", 0L, old.stopReturned.count) }
        }
        ControlledRecord.planned.add(fresh)
        val restart = async(Dispatchers.IO) { capture.startMicrophoneCapture() }
        await(old.stopped)
        assertEquals(0, fresh.allocations.get())
        old.stopGate!!.countDown()
        assertTrue(restart.await())
        a.allow(); oldJob.join(); await(old.released)
        capture.stop(); await(fresh.released)
        assertEquals(1, fresh.releases.get())
    }

    @Test fun cancelledScopeAndRepeatedPumpStartReleaseOnceWithoutReading() = runBlocking {
        val driver = Driver()
        ControlledRecord.planned.add(driver)
        assertTrue(capture.startMicrophoneCapture())
        val cancelled = CoroutineScope(Job().apply { cancel() } + Dispatchers.IO)
        capture.startPump(cancelled)
        val job = pumpJob()
        capture.startPump(scope)
        assertSame("session must not spawn a second reader", job, pumpJob())
        job.join()
        capture.stop(); capture.stop()
        await(driver.released)
        assertEquals(1, driver.stops.get())
        assertEquals(1, driver.releases.get())
    }

    @Test fun deniedRestartRetiresOnlyItsOwnedRecord() = runBlocking {
        val old = Driver(); val a = old.operation(111)
        start(old); await(a.entered); val job = pumpJob()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(capture.startMicrophoneCapture())
        assertFalse(capture.usingPlaybackCapture)
        assertEquals(0, capture.queueDepth())
        a.allow(); job.join(); await(old.released)
        capture.stop()
        assertEquals(1, old.releases.get())
    }

    @Test fun fifoPreservesOvershootAndLiveSelectsNewestContiguousWindow() = runBlocking {
        suspend fun queued(): Driver {
            val driver = Driver()
            val a = driver.operation(111); val b = driver.operation(222)
            val end = driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
            start(driver); val job = pumpJob()
            a.allow(); b.allow(); end.allow(); job.join()
            return driver
        }
        queued()
        assertArrayEquals(ShortArray(5) { 111 }, capture.drainFifoWindow(5))
        assertArrayEquals(shortArrayOf(111, 111, 111, 222, 222), capture.drainFifoWindow(5))
        capture.stop()
        queued()
        assertArrayEquals(ShortArray(5) { 222 }, capture.drainToNewestWindow(5))
        assertNull(capture.takeChunk(1))
    }

    @Test fun repeatedMarkerSessionsDoNotAccumulateSamplesOrMetrics() = runBlocking {
        val random = java.util.Random(48219)
        repeat(16) {
            val marker = 1 + random.nextInt(20000)
            val driver = Driver(); val read = driver.operation(marker)
            val end = driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
            start(driver); val job = pumpJob()
            read.allow(); end.allow(); job.join()
            assertArrayEquals(ShortArray(3) { marker.toShort() }, capture.drainFifoWindow(3))
            assertEquals(1L, capture.chunkCount())
            assertEquals(0L, capture.overrunCount())
            capture.stop(); capture.stop(); await(driver.released)
            assertEquals(1, driver.releases.get())
        }
    }

    @Test
    @Config(sdk = [35], shadows = [ControlledRecord::class, ControlledBuilder::class])
    fun playbackAndMicrophoneAlternateWithoutSharingPcmOrRecordOwnership() = runBlocking {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val binderType = Class.forName("android.media.projection.IMediaProjection")
        val binder = java.lang.reflect.Proxy.newProxyInstance(binderType.classLoader, arrayOf(binderType)) { _, method, _ ->
            when (method.name) {
                "canProjectAudio" -> true
                "asBinder" -> android.os.Binder()
                else -> null
            }
        }
        ReflectionHelpers.setField(projection, "mImpl", binder)
        repeat(6) { index ->
            val playback = index % 2 == 0
            val marker = (index + 1) * 101
            val driver = Driver(); val read = driver.operation(marker)
            val end = driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
            ControlledRecord.planned.add(driver)
            assertTrue(if (playback) capture.startPlaybackCapture(projection) else capture.startMicrophoneCapture())
            assertEquals(playback, capture.usingPlaybackCapture)
            capture.startPump(scope); val job = pumpJob()
            read.allow(); end.allow(); job.join()
            assertArrayEquals(ShortArray(3) { marker.toShort() }, capture.drainFifoWindow(3))
            assertEquals(1L, capture.chunkCount())
            capture.stop(); await(driver.released)
            assertEquals(1, driver.stops.get())
            assertEquals(1, driver.releases.get())
        }
    }

    @Test fun cancellationDuringReadDiscardsLatePcmAndStillReleasesOnce() = runBlocking {
        val driver = Driver(); val read = driver.operation(333)
        start(driver); val job = pumpJob(); await(read.entered)
        job.cancel()
        read.allow(); job.join()
        assertEquals(0L, capture.chunkCount())
        assertEquals(0, capture.queueDepth())
        assertNull(capture.drainFifoWindow(3))
        capture.stop(); capture.stop(); await(driver.released)
        assertEquals(1, driver.releases.get())
    }

    private fun cancelledFifoDrain(target: Int): ShortArray? {
        val completion = CompletableFuture<ShortArray?>()
        // Enter the actual suspend method with an already-cancelled context. A launch/async
        // would skip its body and could never exercise the stale caller's entry boundary.
        val operation: suspend () -> ShortArray? = { capture.drainFifoWindow(target) }
        operation.startCoroutine(object : Continuation<ShortArray?> {
            override val context: CoroutineContext = Job().apply { cancel() }
            override fun resumeWith(result: Result<ShortArray?>) {
                result.fold({ completion.complete(it) }, { completion.completeExceptionally(it) })
            }
        })
        return completion.get(5, TimeUnit.SECONDS)
    }

    @Test fun alreadyCancelledConsumerCannotClearFreshFifoRemainder() = runBlocking {
        val driver = Driver(); val b = driver.operation(222)
        val end = driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
        start(driver); val job = pumpJob()
        b.allow(); end.allow(); job.join()
        assertArrayEquals(ShortArray(3) { 222 }, capture.drainFifoWindow(3))
        val cancelledResult = cancelledFifoDrain(3)
        assertArrayEquals("cancelled entry must leave all five fresh remainder samples", ShortArray(5) { 222 }, capture.drainFifoWindow(5))
        assertNull(cancelledResult)
    }

    @Test fun alreadyCancelledConsumerCannotTrimFreshBacklog() = runBlocking {
        val driver = Driver()
        repeat(25) { driver.operation(222).allow() }
        driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION).allow()
        start(driver); pumpJob().join()
        assertEquals(24, capture.queueDepth())
        assertEquals(1L, capture.overrunCount())
        assertNull(cancelledFifoDrain(3))
        assertEquals("cancelled entry must not trim four fresh backlog chunks", 24, capture.queueDepth())
    }

    @Test fun cancellationAfterFifoSnapshotDoesNotDiscardUncommittedRemainder() = runBlocking {
        val driver = Driver(); val b = driver.operation(222)
        val end = driver.operation(0, result = AudioRecord.ERROR_INVALID_OPERATION)
        start(driver); val job = pumpJob()
        b.allow(); end.allow(); job.join()
        assertArrayEquals(ShortArray(3) { 222 }, capture.drainFifoWindow(3))
        val held = HeldPoll(takeBeforeHold = true)
        held.offer(ShortArray(8) { 333 })
        replaceQueue(held)
        val cancelled = async(Dispatchers.IO) { capture.drainFifoWindow(8) }
        await(held.entered) // Five pending B samples have been snapshotted before waiting for C.
        cancelled.cancel()
        held.resume.countDown(); cancelled.join()
        assertArrayEquals("cancelled assembly must not discard uncommitted B remainder", ShortArray(5) { 222 }, capture.drainFifoWindow(5))
    }

}
