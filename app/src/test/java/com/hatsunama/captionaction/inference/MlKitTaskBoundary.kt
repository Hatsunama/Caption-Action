package com.hatsunama.captionaction.inference

import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModel
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Only ML Kit factory/task boundaries are synthetic. Engine/policy/owner code stays real. */
object MlKitTaskBoundary {
    class Client {
        val closes = AtomicInteger()
        val tasks = CopyOnWriteArrayList<TaskCompletionSource<String>>()
        val cancellations = CopyOnWriteArrayList<CancellationTokenSource>()
        val closeEntered = CountDownLatch(1)
        @Volatile var closeGate: CountDownLatch? = null
        @Volatile var closeThrows = false
        val invoked = CountDownLatch(1)
        @Volatile var immediate: String? = null
        @Volatile var synchronousFailure: Boolean = false
        fun call(): Task<String> {
            if (synchronousFailure) { invoked.countDown(); throw IllegalStateException("synthetic SDK failure") }
            val cancellation = CancellationTokenSource()
            val source = TaskCompletionSource<String>(cancellation.token)
            cancellations += cancellation
            tasks += source
            immediate?.let { source.setResult(it) }
            invoked.countDown()
            return source.task
        }
        fun complete(value: String = "The orange lantern guards our private phrase") {
            tasks.forEach { it.trySetResult(value) }
        }
        fun translator(): Translator = proxy(Translator::class.java)
        fun identifier(): LanguageIdentifier = proxy(LanguageIdentifier::class.java)
        private fun <T> proxy(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "translate", "identifyLanguage" -> call()
                "close" -> {
                    closes.incrementAndGet()
                    closeEntered.countDown()
                    closeGate?.let { check(it.await(10, TimeUnit.SECONDS)) { "SDK close gate not released" } }
                    if (closeThrows) throw IllegalStateException("Synthetic SDK close failure")
                    null
                }
                "toString" -> "ControlledMlKitClient"
                else -> error("Unexpected SDK method ${method.name}")
            }
        })
    }

    val translators = CopyOnWriteArrayList<Client>()
    val identifiers = CopyOnWriteArrayList<Client>()
    val factoryEntered = CountDownLatchHolder()
    val downloadedEntered = CountDownLatchHolder()
    val downloadCalls = AtomicInteger()
    val downloadLanguages = CopyOnWriteArrayList<String>()
    val downloadsByLanguage = java.util.concurrent.ConcurrentHashMap<String, Task<Void>>()
    val downloadEntered = CountDownLatchHolder()
    @Volatile var downloadTask: Task<Void>? = null
    @Volatile var downloadedTask: Task<Set<TranslateRemoteModel>>? = null
    @Volatile var factoryGate: CountDownLatch? = null
    @Volatile var translatorImmediate: String? = null
    @Volatile var identifierImmediate: String? = null
    @Volatile var translatorThrows = false
    @Volatile var factoryThrows = false
    val factoryCalls = AtomicInteger()
    @Volatile var translatorOverride: Translator? = null
    @Volatile var identifierOverride: LanguageIdentifier? = null
    class CountDownLatchHolder { @Volatile var latch = CountDownLatch(1) }
    fun reset() {
        translators.clear(); identifiers.clear()
        downloadLanguages.clear(); downloadsByLanguage.clear()
        factoryEntered.latch = CountDownLatch(1); factoryGate = null
        downloadedEntered.latch = CountDownLatch(1); downloadedTask = null; downloadCalls.set(0)
        downloadEntered.latch = CountDownLatch(1); downloadTask = null
        translatorImmediate = null; identifierImmediate = null; translatorThrows = false
        translatorOverride = null; identifierOverride = null
        factoryThrows = false; factoryCalls.set(0)
    }
    fun unblock() {
        factoryGate?.countDown()
        translators.forEach { it.closeGate?.countDown(); it.complete() }
        identifiers.forEach { it.closeGate?.countDown(); it.complete("es") }
    }

    @Implements(value = Translation::class, isInAndroidSdk = false)
    class TranslationFactory {
        companion object {
            @JvmStatic @Implementation fun getClient(options: TranslatorOptions): Translator {
                factoryCalls.incrementAndGet()
                if (factoryThrows) throw IllegalStateException("Synthetic SDK factory failure")
                translatorOverride?.let { return it }
                val client = Client().also { it.immediate = translatorImmediate; it.synchronousFailure = translatorThrows }
                translators += client
                factoryEntered.latch.countDown()
                factoryGate?.let { check(it.await(15, TimeUnit.SECONDS)) { "Translator factory gate not released" } }
                return client.translator()
            }
        }
    }
    @Implements(value = LanguageIdentification::class, isInAndroidSdk = false)
    class IdentifierFactory {
        companion object {
            @JvmStatic @Implementation fun getClient(options: LanguageIdentificationOptions): LanguageIdentifier =
                identifierOverride ?: Client().also { it.immediate = identifierImmediate; identifiers += it }.identifier()
        }
    }
    @Implements(value = RemoteModelManager::class, isInAndroidSdk = false)
    class ModelManager {
        @Implementation fun getDownloadedModels(type: Class<TranslateRemoteModel>): Task<Set<TranslateRemoteModel>> {
            downloadedTask?.let { downloadedEntered.latch.countDown(); return it }
            return Tasks.forResult((MlKitTranslationEngine.COMMON_SOURCES + setOf("en", "es", "zh")).map { TranslateRemoteModel.Builder(it).build() }.toSet())
        }
        @Implementation fun download(model: RemoteModel, conditions: DownloadConditions): Task<Void> {
            downloadCalls.incrementAndGet()
            val language = (model as TranslateRemoteModel).language
            downloadLanguages += language
            downloadsByLanguage[language]?.let { downloadEntered.latch.countDown(); return it }
            downloadTask?.let { downloadEntered.latch.countDown(); return it }
            return Tasks.forException(IllegalStateException("Synthetic model download failure"))
        }
        companion object {
            @JvmStatic @Implementation fun getInstance(): RemoteModelManager = Shadow.newInstanceOf(RemoteModelManager::class.java)
        }
    }
}
