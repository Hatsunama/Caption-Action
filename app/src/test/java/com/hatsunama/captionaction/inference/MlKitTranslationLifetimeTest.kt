package com.hatsunama.captionaction.inference

import android.os.Looper

import com.hatsunama.captionaction.data.AppSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class, MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages = ["com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"])
class MlKitTranslationLifetimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engines = mutableListOf<MlKitTranslationEngine>()
    @Before fun reset() = MlKitTaskBoundary.reset()
    @After fun cleanup() { MlKitTaskBoundary.unblock(); engines.forEach { it.release() }; scope.cancel() }
    private fun engine() = MlKitTranslationEngine(RuntimeEnvironment.getApplication()).also { engines += it }
    private fun translate(engine: MlKitTranslationEngine) = scope.async { engine.translateExplicit("La linterna guarda nuestro secreto", "es", "en") }
    private fun identify(engine: MlKitTranslationEngine) = scope.async {
        engine.applyPolicy(CaptionResult("The secret lantern travels northward", "auto", 1f, 0, 1000), AppSettings(inputLanguage = "", targetLanguage = "en"))
    }
    private fun until(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { if (System.nanoTime() >= deadline) fail(message); Thread.yield() }
    }
    private fun translator(): MlKitTaskBoundary.Client {
        until("real engine must invoke SDK translation") { MlKitTaskBoundary.translators.any { it.invoked.count == 0L } }
        return MlKitTaskBoundary.translators.first()
    }
    private fun identifier(): MlKitTaskBoundary.Client {
        until("real engine must invoke SDK language identification") { MlKitTaskBoundary.identifiers.any { it.invoked.count == 0L } }
        return MlKitTaskBoundary.identifiers.first()
    }

    @Test fun releaseDefersCloseUntilActualTranslationTaskCompletes() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        owner.release()
        assertEquals("pending SDK task retains client even after release", 0, client.closes.get())
        client.complete(); withTimeout(5000) { work.await() }
        until("completed retired client must close") { client.closes.get() == 1 }
        owner.release(); assertEquals(1, client.closes.get())
    }
    @Test fun translationTimeoutDoesNotCompleteSdkTaskOrReleaseItsLease() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        assertNull(withTimeout(8000) { work.await() })
        assertFalse(client.tasks.single().task.isComplete)
        owner.release(); assertEquals("4-second await expiry is not SDK completion", 0, client.closes.get())
        client.complete(); until("late SDK completion must close") { client.closes.get() == 1 }
    }
    @Test fun languageIdTimeoutDoesNotCompleteSdkTaskOrReleaseItsLease() = runBlocking {
        val owner = engine(); val work = identify(owner); val client = identifier()
        assertNull(withTimeout(5000) { work.await() }.translatedText)
        assertFalse(client.tasks.single().task.isComplete)
        owner.release(); assertEquals("2-second await expiry is not SDK completion", 0, client.closes.get())
        client.complete("es"); until("late identifier completion must close") { client.closes.get() == 1 }
        assertTrue(MlKitTaskBoundary.translators.isEmpty())
    }
    @Test fun repeatedUnusedReleaseDoesNotConstructLanguageIdentifierOrAllowNewClient() = runBlocking {
        val owner = engine(); owner.release(); owner.release()
        assertTrue("release must not initialize a client merely to close it", MlKitTaskBoundary.identifiers.isEmpty())
        assertNull(withTimeout(5000) { translate(owner).await() })
        assertTrue(MlKitTaskBoundary.translators.isEmpty())
    }
    @Test fun retirementDuringBlockedFactoryCannotPublishOrUseNewClient() = runBlocking {
        MlKitTaskBoundary.factoryGate = CountDownLatch(1)
        val owner = engine(); val work = translate(owner)
        assertTrue(MlKitTaskBoundary.factoryEntered.latch.await(5, TimeUnit.SECONDS))
        val retired = scope.async { owner.release() }
        withTimeout(2000) { retired.await() }
        MlKitTaskBoundary.factoryGate!!.countDown()
        withTimeout(6000) { work.await() }
        val client = MlKitTaskBoundary.translators.single()
        assertTrue("a factory returning after retirement must never launch translation", client.tasks.isEmpty())
        assertEquals("unpublished client must close once", 1, client.closes.get())
    }
    @Test fun concurrentSamePairCreatesOnlyOneClientAndKeepsAllTasksAlive() = runBlocking {
        MlKitTaskBoundary.factoryGate = CountDownLatch(1)
        val owner = engine(); val first = translate(owner)
        assertTrue(MlKitTaskBoundary.factoryEntered.latch.await(5, TimeUnit.SECONDS))
        val second = translate(owner)
        // Old getOrPut reaches a second factory; corrected serialized creation waits on its
        // monitor. Observe either actual boundary rather than using a timing sleep.
        until("second acquisition must reach factory or engine creation monitor") {
            MlKitTaskBoundary.translators.size == 2 || Thread.getAllStackTraces().any { (thread, stack) ->
                thread.state == Thread.State.BLOCKED && stack.any { it.className.contains("MlKitTranslationEngine") }
            }
        }
        MlKitTaskBoundary.factoryGate!!.countDown()
        until("both SDK tasks must start") { MlKitTaskBoundary.translators.sumOf { it.tasks.size } == 2 }
        assertEquals("same-pair concurrent acquisition must not leak a losing client", 1, MlKitTaskBoundary.translators.size)
        val client = MlKitTaskBoundary.translators.single(); owner.release()
        assertEquals(0, client.closes.get())
        client.tasks[0].setResult("The first orange lantern")
        assertEquals("second task still owns client", 0, client.closes.get())
        client.tasks[1].setResult("The second orange lantern")
        withTimeout(5000) { first.await(); second.await() }
        until("last SDK task releases client") { client.closes.get() == 1 }
    }
    @Test fun callerCancellationFinishesPromptlyButDoesNotClosePendingTask() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        work.cancel()
        withTimeout(1000) { work.join() }
        assertFalse(client.tasks.single().task.isComplete)
        owner.release(); assertEquals(0, client.closes.get())
        client.complete(); until("canceled awaiter still retires on actual task completion") { client.closes.get() == 1 }
    }
    @Test fun canceledLanguageIdentificationCannotCascadeIntoTranslation() = runBlocking {
        val owner = engine(); val work = identify(owner); val client = identifier()
        work.cancel(); client.complete("es")
        withTimeout(5000) { work.join() }
        assertTrue("canceled owner cannot start downstream translation", MlKitTaskBoundary.translators.isEmpty())
        owner.release(); assertEquals(1, client.closes.get())
    }
    @Test fun alreadyCompletedTaskAndRepeatedReleaseCloseExactlyOnce() = runBlocking {
        MlKitTaskBoundary.translatorImmediate = "The orange lantern guards our private phrase"
        val owner = engine(); assertNotNull(withTimeout(5000) { translate(owner).await() })
        val client = translator(); assertEquals(0, client.closes.get())
        owner.release(); owner.release(); assertEquals(1, client.closes.get())
    }
    @Test fun failedSdkTaskStillClosesExactlyOnceAfterRetirement() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        client.tasks.single().setException(IllegalStateException("synthetic failure"))
        assertNull(withTimeout(5000) { work.await() }); owner.release(); owner.release()
        assertEquals(1, client.closes.get())
    }
    @Test fun synchronousSdkFailureReturnsReservationAndClosesOnce() = runBlocking {
        MlKitTaskBoundary.translatorThrows = true
        val owner = engine(); assertNull(withTimeout(5000) { translate(owner).await() })
        owner.release(); owner.release(); assertEquals(1, translator().closes.get())
    }
    @Test fun factoryThrowLeavesNoOwnedClientAndLaterAcquisitionCanSucceed() = runBlocking {
        MlKitTaskBoundary.factoryThrows = true
        val owner = engine()
        assertNull(withTimeout(5000) { translate(owner).await() })
        assertEquals(1, MlKitTaskBoundary.factoryCalls.get())
        assertTrue("a factory that throws returned no client to own", MlKitTaskBoundary.translators.isEmpty())
        MlKitTaskBoundary.factoryThrows = false
        MlKitTaskBoundary.translatorImmediate = "The orange lantern guards our private phrase"
        assertNotNull(withTimeout(5000) { translate(owner).await() })
        assertEquals(2, MlKitTaskBoundary.factoryCalls.get())
        owner.release(); owner.release()
        assertEquals(1, MlKitTaskBoundary.translators.single().closes.get())
    }
    @Test fun throwingCloseDoesNotPreventOtherClientsRetiringOrRepeatClose() = runBlocking {
        MlKitTaskBoundary.translatorImmediate = "The orange lantern guards our private phrase"
        val owner = engine()
        assertNotNull(withTimeout(5000) { translate(owner).await() })
        assertNotNull(withTimeout(5000) { owner.translateExplicit("La lanterne garde notre secret", "fr", "en") })
        assertEquals(2, MlKitTaskBoundary.translators.size)
        MlKitTaskBoundary.translators.first().closeThrows = true
        owner.release(); owner.release()
        assertEquals(listOf(1, 1), MlKitTaskBoundary.translators.map { it.closes.get() })
    }
    @Test fun sdkTaskCancellationPropagatesAndReturnsClientUse() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        client.cancellations.single().cancel()
        // The real token API dispatches cancellation through its Main executor.
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5000) { work.join() }
        assertTrue("actual SDK cancellation must not become a successful null result", work.isCancelled)
        assertTrue(client.tasks.single().task.isCanceled)
        assertEquals("active owner retains idle cache", 0, client.closes.get())
        owner.release(); owner.release()
        assertEquals("SDK cancellation returned its use exactly once", 1, client.closes.get())
    }
    @Test fun taskCleanupOverlappingRepeatedReleaseAttemptsCloseExactlyOnce() = runBlocking {
        val owner = engine(); val work = translate(owner); val client = translator()
        owner.release()
        assertEquals("release must first retain the pending SDK use", 0, client.closes.get())
        client.closeGate = CountDownLatch(1)
        val completion = scope.async { client.complete() }
        assertTrue("actual Task completion must enter SDK close", client.closeEntered.await(5, TimeUnit.SECONDS))
        // Completion is inside close(), outside the engine lock. Reentrant retirement
        // must finish promptly without another close attempt or waiting on SDK cleanup.
        withTimeout(1000) { scope.async { owner.release() }.await() }
        assertEquals(1, client.closes.get())
        client.closeGate!!.countDown()
        withTimeout(5000) { completion.await(); work.await() }
        owner.release(); assertEquals(1, client.closes.get())
    }

}
