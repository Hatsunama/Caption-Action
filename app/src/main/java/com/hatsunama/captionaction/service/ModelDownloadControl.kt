package com.hatsunama.captionaction.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One-way cancellation: starting a queued download must never erase a prior cancel. */
internal class ModelDownloadControl {
    @Volatile var isCancelled = false
        private set
    private var disconnect: (() -> Unit)? = null

    fun cancel() {
        val close = synchronized(this) {
            isCancelled = true
            disconnect.also { disconnect = null }
        }
        close?.invoke()
    }

    fun attach(close: () -> Unit): Boolean {
        val accepted = synchronized(this) {
            if (isCancelled) false else {
                disconnect = close
                true
            }
        }
        if (!accepted) close()
        return accepted
    }

    @Synchronized fun detach() { disconnect = null }
}

/** All manager instances share file ownership until streams are closed and promotion finishes. */
internal object ModelDownloadLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFile(file: File, block: suspend () -> T): T =
        locks.getOrPut(file.canonicalPath) { Mutex() }.withLock { block() }
}
