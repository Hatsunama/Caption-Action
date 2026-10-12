package com.hatsunama.captionaction.lifecycle

import android.app.Application
import android.content.ContextWrapper
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import java.io.File
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controls for the expected-session APIs; real recorder locking, files, and UTF-8 bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExpectedRecorderSessionOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun recorder(): SubtitleFileRecorder {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = File(root, "internal")
            override fun getExternalFilesDir(type: String?) = File(root, "external")
        }
        return SubtitleFileRecorder(context) { Date(1_700_000_000_123L) }
    }

    @Test fun staleExpectedPathCannotAppendToOrCloseANewerSession() {
        val recorder = recorder()
        val oldPath = recorder.startSession("en", false)!!
        recorder.appendCaptionForSession(oldPath, "Retained first-session marker.", null)
        val oldBytes = File(oldPath).readBytes()
        val freshPath = recorder.startSession("en", false)!!
        assertNotEquals(oldPath, freshPath)
        val freshHeader = File(freshPath).readBytes()

        recorder.appendCaptionForSession(oldPath, "Stale append must never cross sessions.", null)
        assertNull("old cleanup must not close the fresh writer", recorder.stopSessionForSession(oldPath))
        assertTrue(recorder.isRecording)
        assertEquals(freshPath, recorder.currentPath)
        assertArrayEquals(oldBytes, File(oldPath).readBytes())
        assertArrayEquals(freshHeader, File(freshPath).readBytes())

        recorder.appendCaptionForSession(freshPath, "Fresh-session marker 日本語.", null)
        val freshBytes = File(freshPath).readBytes()
        assertTrue(File(freshPath).readText(Charsets.UTF_8).endsWith("Fresh-session marker 日本語.\n"))
        assertEquals(freshPath, recorder.stopSessionForSession(freshPath))
        assertFalse(recorder.isRecording)
        assertNull(recorder.currentPath)
        assertNull(recorder.stopSessionForSession(freshPath))
        recorder.appendCaptionForSession(freshPath, "Stopped writer must reject append.", null)
        assertArrayEquals(freshBytes, File(freshPath).readBytes())
        assertArrayEquals(oldBytes, File(oldPath).readBytes())
    }

    @Test fun staleCloseRemainsHarmlessAfterDisabledRecordingAndLaterReenable() {
        val recorder = recorder()
        val first = recorder.startSession("en", false)!!
        recorder.appendCaptionForSession(first, "First recorded marker.", null)
        val retained = File(first).readBytes()
        assertEquals(first, recorder.stopSessionForSession(first))
        assertFalse(recorder.isRecording)
        assertNull(recorder.currentPath)

        val later = recorder.startSession("es", false)!!
        assertNull(recorder.stopSessionForSession(first))
        recorder.appendCaptionForSession(later, "Nueva sesión.", null)
        assertTrue(recorder.isRecording)
        assertEquals(later, recorder.currentPath)
        assertTrue(File(later).readText(Charsets.UTF_8).endsWith("Nueva sesión.\n"))
        assertArrayEquals(retained, File(first).readBytes())
        assertEquals(later, recorder.stopSessionForSession(later))
    }
}
