package regression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hatsunama.captionaction.service.CaptionDuplicateGate

class ProductionBodyRegressionTest {
    private val before = System.getProperty("regression.version") == "before"
    private fun pcm(start: Int, size: Int) = ShortArray(size) { (start + it).toShort() }
    private fun reader(ready: List<ShortArray>, arriving: List<ShortArray>): suspend (Int) -> ShortArray? {
        return if (before) BeforeAudioFixture(ready, arriving)::drainToNewestWindow
            else AfterAudioFixture(ready, arriving)::drainToNewestWindow
    }
    @Test fun queuedBacklog_returnsNewestContiguous18Samples() = runBlocking {
        val read = reader((0 until 6).map { pcm(it * 8, 8) }, emptyList())
        assertArrayEquals(pcm(30, 18), read(18))
    }
    @Test fun nonAlignedReadChunks_preserveAll80SamplesExactlyOnce() = runBlocking {
        val read = reader(emptyList(), (0 until 10).map { pcm(it * 8, 8) })
        val received = ArrayList<Short>()
        while (true) {
            val window = read(18) ?: break
            received.addAll(window.toList())
        }
        assertArrayEquals(pcm(0, 80), received.toShortArray())
    }
    @Test fun stoppedCapture_flushesShortSevenSampleWindow() = runBlocking {
        val read = reader(emptyList(), listOf(pcm(0, 7)))
        assertArrayEquals(pcm(0, 7), read(18))
    }
    @Test fun identicalCaption_afterLongPause_isAllowedAgain() {
        val original = BeforeDuplicateFixture()
        val fixed = CaptionDuplicateGate()
        original.onPublished("turn left at the lights", 1_000)
        fixed.onPublished("turn left at the lights", 1_000)
        assertFalse(if (before) original.shouldSuppress("turn left at the lights", 60_000)
            else fixed.shouldSuppress("turn left at the lights", 60_000))
    }
    @Test fun identicalCaption_insideRecentWindow_remainsSuppressed() {
        val original = BeforeDuplicateFixture()
        val fixed = CaptionDuplicateGate()
        original.onPublished("turn left at the lights", 1_000)
        fixed.onPublished("turn left at the lights", 1_000)
        assertTrue(if (before) original.shouldSuppress("turn left at the lights", 2_000)
            else fixed.shouldSuppress("turn left at the lights", 2_000))
    }
}
