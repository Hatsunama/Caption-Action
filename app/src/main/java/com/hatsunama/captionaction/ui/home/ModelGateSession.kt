package com.hatsunama.captionaction.ui.home

/** Main-thread ownership for one dialog. A dismissed gate can never start capture later. */
internal class ModelGateSession {
    var isActive = true
        private set

    fun close() { isActive = false }

    fun complete(onReady: () -> Unit): Boolean {
        if (!isActive) return false
        isActive = false
        onReady()
        return true
    }
}
