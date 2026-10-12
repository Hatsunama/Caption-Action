package regression
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
class FakePump(var isActive: Boolean = true)
class ScriptedPcmQueue(ready: List<ShortArray>, arriving: List<ShortArray>, val endCapture: () -> Unit) {
    private val available = ArrayDeque(ready)
    private val future = ArrayDeque(arriving)
    val size: Int get() = available.size
    fun isEmpty() = available.isEmpty()
    fun poll(): ShortArray? = available.poll()
    // Timed poll models the next real AudioRecord arrival. Untimed poll never
    // exposes future chunks; once the scripted source ends, capture is stopped.
    fun poll(timeout: Long, unit: TimeUnit): ShortArray? {
        val result = available.poll() ?: future.poll()
        if (result == null) endCapture()
        return result
    }
}
