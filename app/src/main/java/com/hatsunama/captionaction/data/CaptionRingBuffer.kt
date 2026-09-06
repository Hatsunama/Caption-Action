package com.hatsunama.captionaction.data

import com.hatsunama.captionaction.inference.CaptionResult
import java.util.ArrayDeque

/**
 * In-memory rolling caption history. Cleared when the session ends.
 * Nothing is written to disk or cloud.
 */
class CaptionRingBuffer(private val capacity: Int = 64) {
    private val deque = ArrayDeque<CaptionResult>(capacity)

    @Synchronized
    fun add(item: CaptionResult) {
        if (deque.size >= capacity) deque.removeFirst()
        deque.addLast(item)
    }

    @Synchronized
    fun snapshot(): List<CaptionResult> = deque.toList()

    @Synchronized
    fun clear() {
        deque.clear()
    }
}
