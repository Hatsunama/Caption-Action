package com.hatsunama.captionaction.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Single-consumer Live PCM windowing. Ordinary chunk overshoot belongs to the next
 * window; only a real queued backlog may deliberately drop old audio for video sync.
 * Separate from Mic's completeness-first FIFO policy.
 */
internal class LiveWindowDrainer {
    private val stateLock = Any()
    @Volatile private var generation = 0L
    private var remainder: ShortArray? = null

    fun reset() {
        synchronized(stateLock) {
            generation++
            remainder = null
        }
    }

    suspend fun drain(
        targetSamples: Int,
        takeChunk: suspend () -> ShortArray?,
        queuedChunkCount: () -> Int,
        pollChunk: () -> ShortArray?,
        onDropSamples: (Int) -> Unit = {}
    ): ShortArray? {
        require(targetSamples > 0)
        val context = currentCoroutineContext()
        val (drainGeneration, pending) = synchronized(stateLock) {
            if (!context.isActive) return null
            generation to remainder.also { remainder = null }
        }
        fun isCurrent(): Boolean = context.isActive && generation == drainGeneration
        val chunks = ArrayList<ShortArray>()
        var total = 0
        pending?.let {
            chunks.add(it)
            total += it.size
        }
        while (total < targetSamples) {
            if (!isCurrent()) return null
            val chunk = takeChunk()
            if (!isCurrent()) return null
            if (chunk == null) break
            chunks.add(chunk)
            total += chunk.size
        }
        if (total == 0 || !isCurrent()) return null

        // Capture a bounded snapshot, then drain ALL of that backlog, not only the
        // oldest full window. New producer arrivals are left for the next call so
        // a continuously running pump cannot starve inference.
        var caughtUp = false
        val backlog = queuedChunkCount()
        if (!isCurrent()) return null
        repeat(backlog) {
            if (!isCurrent()) return null
            val chunk = pollChunk()
            if (!isCurrent()) return null
            if (chunk == null) return@repeat
            chunks.add(chunk)
            total += chunk.size
            caughtUp = true
        }
        val pcm = ShortArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(pcm, offset)
            offset += chunk.size
        }
        val output: ShortArray
        val nextRemainder: ShortArray?
        if (total <= targetSamples) {
            output = pcm
            nextRemainder = null
        } else if (caughtUp) {
            onDropSamples(total - targetSamples)
            output = pcm.copyOfRange(total - targetSamples, total)
            nextRemainder = null
        } else {
            // A non-multiple of AudioRecord's read size is not evidence of lag.
            // Keep its suffix instead of silently losing samples on every window.
            nextRemainder = pcm.copyOfRange(targetSamples, total)
            output = pcm.copyOfRange(0, targetSamples)
        }
        // Reset may run while takeChunk blocks or while this window is assembled.
        // Publish state only for the generation that owns these samples. Never hold
        // the state lock across queue callbacks or other potentially blocking work.
        return synchronized(stateLock) {
            if (!isCurrent()) null else {
                remainder = nextRemainder
                output
            }
        }
    }
}
