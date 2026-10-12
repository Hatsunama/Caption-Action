package com.hatsunama.captionaction.inference

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compatibility preflight only: loading a library does not prove inference/session correctness. */
@RunWith(AndroidJUnit4::class)
class WhisperNativeLoadPreflightTest {
    @Test(timeout = 20_000) fun existingArm64WhisperLibraryLoadsOnThisRuntime() {
        assertTrue("Emulator must advertise arm64-v8a support; got ${Build.SUPPORTED_ABIS.toList()}",
            Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        System.loadLibrary("whisper")
    }
}
