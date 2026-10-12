package com.hatsunama.captionaction.data

import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real recorder sessions and UTF-8 filesystem output, not a model of its implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubtitleFileRecorderSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val instant = Date(1_700_000_000_123L)

    private class FilesContext(private val root: File, var external: File?) : ContextWrapper(null) {
        override fun getFilesDir() = File(root, "internal")
        override fun getExternalFilesDir(type: String?) = external
    }

    private fun context(): FilesContext {
        val root = temp.newFolder()
        return FilesContext(root, File(root, "external"))
    }

    private fun recorder(context: FilesContext) = SubtitleFileRecorder(context) { instant }
    private fun header(language: String, dual: Boolean): String =
        "# Caption Action\n" +
            "# Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(instant)}\n" +
            "# Target language: $language\n" +
            "# Dual subtitles: ${if (dual) "on" else "off"}\n" +
            "# Local only — no upload\n\n"

    private fun assertBytes(path: String, expected: String) {
        assertArrayEquals("UTF-8 output at $path", expected.toByteArray(Charsets.UTF_8), File(path).readBytes())
    }

    @Test fun sameSecondRestartRetainsBothCompleteTranscripts() {
        val recorder = recorder(context())
        val first = recorder.startSession("en", true)!!
        recorder.appendCaption("  Hello 🌏  ", "  こんにちは  ")
        assertEquals(first, recorder.stopSession())
        val firstBytes = File(first).readBytes()
        val second = recorder.startSession("ja", false)!!
        recorder.appendCaption("さようなら", null)
        assertEquals(second, recorder.stopSession())
        assertArrayEquals("Starting again must never truncate the first transcript", firstBytes, File(first).readBytes())
        assertNotEquals("Every session owns a distinct file", first, second)
        assertBytes(first, header("en", true) + "Hello 🌏 | こんにちは\n")
        assertBytes(second, header("ja", false) + "さようなら\n")
        // Optional reviewer artifacts are copied only after all byte assertions pass.
        System.getProperty("caption.recorder.outputEvidence")?.let { destination ->
            val directory = File(destination).also { it.mkdirs() }
            File(first).copyTo(File(directory, "first-session.txt"), overwrite = true)
            File(second).copyTo(File(directory, "second-session.txt"), overwrite = true)
        }
    }

    @Test fun replacingAnActiveSessionFlushesAndPreservesIt() {
        val recorder = recorder(context())
        val first = recorder.startSession("en", false)!!
        recorder.appendCaption("first session", null)
        val second = recorder.startSession("fr", true)!!
        recorder.appendCaption("deuxième", "second")
        assertEquals(second, recorder.currentPath)
        assertTrue(recorder.isRecording)
        recorder.stopSession()
        assertNotEquals(first, second)
        assertBytes(first, header("en", false) + "first session\n")
        assertBytes(second, header("fr", true) + "deuxième | second\n")
    }

    @Test fun concurrentRecorderInstancesCannotOverwriteEachOther() {
        val context = context()
        // Pre-create the directory: this test isolates filename ownership from directory creation.
        File(context.external, "captions").mkdirs()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val paths = pool.invokeAll((0 until 16).map { index -> Callable {
                val recorder = recorder(context)
                val path = recorder.startSession("en", false)!!
                recorder.appendCaption("session-$index 日本語", null)
                recorder.stopSession()
                path to index
            } }, 10, TimeUnit.SECONDS).map { it.get() }
            assertEquals(paths.size, paths.map { it.first }.toSet().size)
            paths.forEach { (path, index) -> assertBytes(path, header("en", false) + "session-$index 日本語\n") }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failedReplacementClosesOldSessionAndCanRecoverWithoutLosingIt() {
        val context = context()
        val recorder = recorder(context)
        val first = recorder.startSession("en", false)!!
        recorder.appendCaption("keep me", null)
        val healthyExternal = context.external
        val blockedBase = temp.newFile("not-a-directory")
        context.external = blockedBase
        assertNull(recorder.startSession("fr", true))
        assertFalse(recorder.isRecording)
        assertNull(recorder.currentPath)
        assertNull(recorder.stopSession())
        recorder.appendCaption("must not appear", null)
        assertBytes(first, header("en", false) + "keep me\n")
        context.external = healthyExternal
        val recovered = recorder.startSession("ja", false)!!
        recorder.appendCaption("復旧", null)
        recorder.discard()
        assertNotEquals(first, recovered)
        assertBytes(first, header("en", false) + "keep me\n")
        assertBytes(recovered, header("ja", false) + "復旧\n")
        assertFalse(recorder.isRecording)
        assertNull(recorder.currentPath)
    }

    @Test fun fallbackStorageAndRepeatedStopDiscardPreserveExactOutput() {
        val context = context().also { it.external = null }
        val recorder = recorder(context)
        recorder.appendCaption("ignored before start", null)
        assertNull(recorder.stopSession())
        recorder.discard()
        val path = recorder.startSession("en", true)!!
        assertEquals(File(context.filesDir, "Documents/captions").canonicalPath, File(path).parentFile.canonicalPath)
        recorder.appendCaption(" ", "ignored")
        recorder.appendCaption("  café  ", " café ")
        recorder.appendCaption("한글", " العربية ")
        assertBytes(path, header("en", true) + "café\n한글 | العربية\n") // flushed before stop
        assertEquals(path, recorder.stopSession())
        assertNull(recorder.stopSession())
        recorder.discard()
        recorder.appendCaption("ignored after stop", null)
        assertBytes(path, header("en", true) + "café\n한글 | العربية\n")
    }

    @Test fun seededCumulativeSessionsMatchEveryRetainedFileAfterEveryOperation() {
        for (seed in listOf(7L, 41L, 20261012L)) {
            val recorder = recorder(context())
            val random = Random(seed)
            val expected = linkedMapOf<String, String>()
            var active: String? = null
            repeat(160) { step ->
                when (random.nextInt(5)) {
                    0, 1 -> {
                        val dual = random.nextBoolean()
                        val language = if (dual) "ja" else "en"
                        val path = recorder.startSession(language, dual)!!
                        assertFalse("seed=$seed step=$step reused an earlier path", expected.containsKey(path))
                        expected[path] = header(language, dual)
                        active = path
                    }
                    2 -> {
                        val primary = "utterance-$seed-$step 🌸"
                        val secondary = if (random.nextBoolean()) "原文-$step" else null
                        recorder.appendCaption(primary, secondary)
                        active?.let { expected[it] = expected.getValue(it) + primary + (secondary?.let { s -> " | $s" } ?: "") + "\n" }
                    }
                    3 -> { assertEquals(active, recorder.stopSession()); active = null }
                    4 -> { recorder.discard(); active = null }
                }
                assertEquals(active, recorder.currentPath)
                assertEquals(active != null, recorder.isRecording)
                expected.forEach { (path, bytes) -> assertBytes(path, bytes) }
            }
            recorder.stopSession()
            expected.forEach { (path, bytes) -> assertBytes(path, bytes) }
        }
    }
}
