package com.hatsunama.captionaction.audio

/**
 * Single-consumer Live PCM windowing. Ordinary chunk overshoot belongs to the next
 * window; only a real queued backlog may deliberately drop old audio for video sync.
 * Separate from Mic's completeness-first FIFO policy.
 */
internal class LiveWindowDrainer {
    private var remainder: ShortArray? = null

    fun reset() {
        remainder = null
    }

    suspend fun drain(
        targetSamples: Int,
        takeChunk: suspend () -> ShortArray?,
        queuedChunkCount: () -> Int,
        pollChunk: () -> ShortArray?,
        onDropSamples: (Int) -> Unit = {}
    ): ShortArray? {
        require(targetSamples > 0)
        val chunks = ArrayList<ShortArray>()
        var total = 0
        remainder?.let {
            chunks.add(it)
            total += it.size
        }
        remainder = null
        while (total < targetSamples) {
            val chunk = takeChunk() ?: break
            chunks.add(chunk)
            total += chunk.size
        }
        if (total == 0) return null

        // Capture a bounded snapshot, then drain ALL of that backlog, not only the
        // oldest full window. New producer arrivals are left for the next call so
        // a continuously running pump cannot starve inference.
        var caughtUp = false
        repeat(queuedChunkCount()) {
            val chunk = pollChunk() ?: return@repeat
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
        if (total <= targetSamples) return pcm
        if (caughtUp) {
            onDropSamples(total - targetSamples)
            return pcm.copyOfRange(total - targetSamples, total)
        }

        // A non-multiple of AudioRecord's read size is not evidence of lag.
        // Keep its suffix instead of silently losing samples on every window.
        remainder = pcm.copyOfRange(targetSamples, total)
        return pcm.copyOfRange(0, targetSamples)
    }
}
