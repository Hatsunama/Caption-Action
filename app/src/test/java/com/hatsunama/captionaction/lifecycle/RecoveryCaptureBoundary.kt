package com.hatsunama.captionaction.lifecycle

import android.content.Context
import android.media.projection.MediaProjection
import com.hatsunama.captionaction.audio.AudioCapture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CoroutineScope
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Hardware boundary only. Callbacks can deliberately outlive stop to challenge caller ownership. */
@Implements(value = AudioCapture::class, callThroughByDefault = false)
class RecoveryCaptureBoundary {
    @Implementation fun __constructor__(context: Context) {}
    @Implementation fun hasRecordAudioPermission(): Boolean = true
    @Implementation fun startPlaybackCapture(projection: MediaProjection): Boolean { playbackStarts.incrementAndGet(); return true }
    @Implementation fun startMicrophoneCapture(): Boolean { microphoneStarts.incrementAndGet(); return true }
    @Implementation fun getUsingPlaybackCapture(): Boolean = true
    @Implementation fun startPump(scope: CoroutineScope) { failures += {} }
    @Implementation fun startPump(scope: CoroutineScope, onFailure: () -> Unit) { failures += onFailure }
    @Implementation fun stop() { stops.incrementAndGet() }
    @Implementation fun overrunCount(): Long = 0L
    @Implementation fun chunkCount(): Long = 0L
    @Implementation fun queueDepth(): Int = 0
    @Implementation fun lastRms(): Float = 0f
    @Implementation fun drainFifoWindow(targetSamples: Int, continuation: Continuation<ShortArray?>): Any? = drain()
    @Implementation fun drainToNewestWindow(targetSamples: Int, continuation: Continuation<ShortArray?>): Any? = drain()
    private fun drain(): ShortArray? {
        val operation = drains.poll() ?: return null
        operation.entered.countDown()
        check(operation.proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Recovery drain was never released" }
        return operation.pcm
    }
    companion object {
        val drains = LinkedBlockingQueue<CallerPreparationOwnershipTest.ControlledDrain>()
        val failures = CopyOnWriteArrayList<() -> Unit>()
        val stops = AtomicInteger()
        val playbackStarts = AtomicInteger()
        val microphoneStarts = AtomicInteger()
        fun reset() { drains.clear(); failures.clear(); stops.set(0); playbackStarts.set(0); microphoneStarts.set(0) }
    }
}
