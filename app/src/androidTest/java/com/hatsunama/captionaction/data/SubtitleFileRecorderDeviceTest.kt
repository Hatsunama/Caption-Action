package com.hatsunama.captionaction.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.UUID

/** Real Android storage and the actual recorder. Never starts audio, projection or inference. */
@RunWith(AndroidJUnit4::class)
class SubtitleFileRecorderDeviceTest {
    private lateinit var context: SessionContext
    private lateinit var recorder: SubtitleFileRecorder
    private val ownedDirectories = mutableListOf<File>()
    private val instant = Date(1_700_000_000_123L)

    private class SessionContext(base: Context, private val internal: File, var external: File?) : ContextWrapper(base) {
        override fun getFilesDir(): File = internal
        override fun getExternalFilesDir(type: String?): File? = external
    }

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "recorder-test-${UUID.randomUUID()}"
        val internal = File(target.filesDir, unique).also { ownedDirectories.add(it) }
        val external = target.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let {
            File(it, unique).also { directory -> ownedDirectories.add(directory) }
        }
        context = SessionContext(target, internal, external)
        recorder = SubtitleFileRecorder(context) { instant }
    }

    @After fun tearDown() {
        if (::recorder.isInitialized) recorder.discard()
        // Only this test's newly created, UUID-named directories are removed.
        ownedDirectories.forEach { assertTrue("Remove owned test directory: $it", !it.exists() || it.deleteRecursively()) }
    }

    private fun header(language: String, dual: Boolean): String =
        "# Caption Action\n# Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(instant)}\n" +
            "# Target language: $language\n# Dual subtitles: ${if (dual) "on" else "off"}\n# Local only — no upload\n\n"

    private fun assertBytes(path: String, expected: String) =
        assertArrayEquals(path, expected.toByteArray(Charsets.UTF_8), File(path).readBytes())

    @Test(timeout = 20_000) fun repeatedSessionsRetainBothUtf8Files() {
        val first = recorder.startSession("en", true)!!
        recorder.appendCaption("Hello 🌏", "こんにちは")
        assertEquals(first, recorder.stopSession())
        val saved = File(first).readBytes()
        val second = recorder.startSession("ja", false)!!
        recorder.appendCaption("また会いましょう", null)
        assertEquals(second, recorder.stopSession())
        assertNotEquals(first, second)
        assertArrayEquals(saved, File(first).readBytes())
        assertBytes(first, header("en", true) + "Hello 🌏 | こんにちは\n")
        assertBytes(second, header("ja", false) + "また会いましょう\n")
        assertNull(recorder.stopSession())
        assertFalse(recorder.isRecording)
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "recorder-ci-evidence")
        assertTrue(evidence.isDirectory || evidence.mkdirs())
        File(first).copyTo(File(evidence, "first-session.txt"), overwrite = true)
        File(second).copyTo(File(evidence, "second-session.txt"), overwrite = true)
    }

    @Test(timeout = 20_000) fun failedReplacementRecoversToInternalStorageWithoutErasingOldFile() {
        val first = recorder.startSession("en", false)!!
        recorder.appendCaption("retain this session", null)
        context.filesDir.mkdirs()
        val blocker = File(context.filesDir, "not-a-directory").apply { writeText("block") }
        context.external = blocker
        assertNull(recorder.startSession("ja", true))
        assertNull(recorder.currentPath)
        assertFalse(recorder.isRecording)
        recorder.appendCaption("ignored while stopped", null)
        assertBytes(first, header("en", false) + "retain this session\n")
        context.external = null
        val recovered = recorder.startSession("ja", false)!!
        recorder.appendCaption("復旧 🌸", null)
        recorder.discard()
        assertEquals(File(context.filesDir, "Documents/captions").canonicalPath, File(recovered).parentFile!!.canonicalPath)
        assertBytes(first, header("en", false) + "retain this session\n")
        assertBytes(recovered, header("ja", false) + "復旧 🌸\n")
    }

    @Test(timeout = 30_000) fun cumulativeInterruptionSequencePreservesEveryCompletedFile() {
        val random = Random(20261012L)
        val expected = linkedMapOf<String, String>()
        var active: String? = null
        repeat(64) { step ->
            when (random.nextInt(4)) {
                0 -> {
                    val path = recorder.startSession("en", false)!!
                    assertFalse("A later session reused an earlier file", expected.containsKey(path))
                    expected[path] = header("en", false)
                    active = path
                }
                1 -> {
                    val text = "line-$step café 日本語"
                    recorder.appendCaption(text, null)
                    active?.let { expected[it] = expected.getValue(it) + "$text\n" }
                }
                2 -> { assertEquals(active, recorder.stopSession()); active = null }
                3 -> { recorder.discard(); active = null }
            }
            assertEquals(active, recorder.currentPath)
            assertEquals(active != null, recorder.isRecording)
            expected.forEach { (path, bytes) -> assertBytes(path, bytes) }
        }
        recorder.stopSession()
        expected.forEach { (path, bytes) -> assertBytes(path, bytes) }
    }
}
