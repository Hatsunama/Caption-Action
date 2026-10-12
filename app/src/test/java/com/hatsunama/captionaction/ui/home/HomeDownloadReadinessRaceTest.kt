package com.hatsunama.captionaction.ui.home

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Real Home/download/cache; only the settings-store completion order is controlled. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[28],application=CaptionActionApp::class,
    shadows=[HomeDownloadReadinessRaceTest.SettingsBoundary::class],
    instrumentedPackages=["com.hatsunama.captionaction.data.SettingsRepository"])
class HomeDownloadReadinessRaceTest {
    @Implements(SettingsRepository::class)
    class SettingsBoundary {
        @Implementation fun getSettingsFlow():Flow<AppSettings> = state
        @Implementation fun current(continuation:Continuation<AppSettings>):Any = state.value
        @Implementation fun update(transform:(AppSettings)->AppSettings,continuation:Continuation<Unit>):Any? {
            val operation:suspend()->Unit = {
                state.value=transform(state.value)
                if(hold) permit.await()
            }
            return operation.startCoroutineUninterceptedOrReturn(continuation)
        }
        companion object {
            lateinit var state:MutableStateFlow<AppSettings>
            var hold=false
            lateinit var permit:CompletableDeferred<Unit>
        }
    }
    private fun until(message:String,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message,condition())
    }
    @Test fun successfulDownloadKeepsReadyThroughOverlappingSettingsStatusRevalidation() {
        SettingsBoundary.state=MutableStateFlow(AppSettings(modelTierId=ModelTier.FAST.id,inputLanguage="en",targetLanguage="en"))
        SettingsBoundary.hold=false; SettingsBoundary.permit=CompletableDeferred()
        val controller=Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        val home=controller.get()
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val finalReads=AtomicInteger(); val calls=AtomicInteger()
        try {
            until("initial FAST status") { home.findViewById<TextView>(R.id.statusText).text.contains("SenseVoice") }
            val bytes=ByteArray(8193) { (it*31).toByte() }
            val id=ModelIdentity(bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val cache=ModelCache(RuntimeEnvironment.getApplication(),{id},object:ModelFileOperations() {
                override fun openRead(file:File):InputStream {
                    val real=super.openRead(file)
                    val block=file.name==ModelTier.BALANCED.fileName && finalReads.incrementAndGet()==2
                    return object:java.io.FilterInputStream(real) {
                        override fun read(b:ByteArray,off:Int,len:Int):Int {
                            if(block) { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
                            return super.read(b,off,len)
                        }
                    }
                }
            })
            HomeActivity::class.java.getDeclaredField("cache").apply { isAccessible=true }.set(home,cache)
            cache.partFileFor(ModelTier.BALANCED).writeBytes(bytes)
            HomeActivity::class.java.getDeclaredMethod("showModelGateThenStart",Function0::class.java).apply { isAccessible=true }.invoke(home,{calls.incrementAndGet();Unit})
            val gate=HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible=true }.get(home) as AlertDialog
            SettingsBoundary.hold=true
            gate.findViewById<View>(R.id.btnDownload)!!.performClick()
            until("download publishes bytes and settings-triggered status revalidation enters") { entered.count==0L }
            assertArrayEquals(bytes,cache.fileFor(ModelTier.BALANCED).readBytes())
            val job=HomeActivity::class.java.getDeclaredField("downloadJob").apply { isAccessible=true }.get(home) as Job
            SettingsBoundary.permit.complete(Unit)
            until("download continuation completes while status hash is still blocked") { job.isCompleted }
            assertEquals("a status recheck cannot retract already byte-verified download readiness",1,calls.get())
        } finally {
            release.countDown(); SettingsBoundary.permit.complete(Unit); SettingsBoundary.hold=false
            controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
