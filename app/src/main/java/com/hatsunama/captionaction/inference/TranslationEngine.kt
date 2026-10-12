package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.util.Languages
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline MT owner: policy (input==target skip / Input as MT source hint) + ML Kit Translate.
 * ASR engines stay ASR; if they already filled [CaptionResult.translatedText] (e.g. whisper→EN), keep it.
 * Live hot path must never block on long Downloads — use short timeouts and skip if packs missing.
 */
interface TranslationEngine {
    fun supportedTargets(): Set<String>

    suspend fun ensureModels(
        targetLanguage: String,
        extraSources: Set<String> = emptySet(),
        onStatus: ((String) -> Unit)? = null
    ): EnsureResult

    suspend fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult

    /**
     * Mic-only explicit MT: picker [sourceLang]→[targetLang], no language-id guess,
     * no Live [AsrJunkFilter.hasEnoughContentForMt] crumb gate.
     * Default: unsupported (Live engines keep applyPolicy only).
     */
    suspend fun translateExplicit(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = null

    fun release()
}

sealed class EnsureResult {
    data object Ready : EnsureResult()
    data class Failed(val message: String) : EnsureResult()
}

class MlKitTranslationEngine(context: Context) : TranslationEngine {
    private val modelManager = RemoteModelManager.getInstance()
    private val downloadMutex = Mutex()
    private val lastStatus = AtomicReference("idle")
    private val clientLock = Any()
    // Factories may block. Retirement never waits for this lock; an unpublished client
    // returning after retirement is closed instead of being inserted into the cache.
    private val creationLock = Any()
    private class Client<C : Closeable>(val value: C, var uses: Int = 1)
    private val clients = mutableMapOf<String, Client<out Closeable>>()
    private var retired = false

    override fun supportedTargets(): Set<String> =
        Languages.all.map { it.code }.filter { toMlKitTag(it) != null }.toSet()

    fun statusLine(): String = lastStatus.get()

    override suspend fun ensureModels(
        targetLanguage: String,
        extraSources: Set<String>,
        onStatus: ((String) -> Unit)?
    ): EnsureResult = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val target = normalizeLang(targetLanguage)
            val targetTag = toMlKitTag(target)
            if (targetTag == null) {
                val msg = "Unsupported target language: $targetLanguage"
                lastStatus.set(msg)
                onStatus?.invoke(msg)
                return@withContext EnsureResult.Failed(msg)
            }

            val wanted = linkedSetOf(target)
            wanted.addAll(COMMON_SOURCES)
            wanted.addAll(extraSources.map { normalizeLang(it) }.filter { it.isNotEmpty() })
            val tags = wanted.mapNotNull { toMlKitTag(it) }.distinct()

            try {
                currentCoroutineContext().ensureActive()
                val already = downloadedTags()
                currentCoroutineContext().ensureActive()
                val missing = tags.filter { it !in already }
                if (missing.isEmpty()) {
                    val msg = "MT packs ready (${tags.size})"
                    lastStatus.set(msg)
                    onStatus?.invoke(msg)
                    return@withContext EnsureResult.Ready
                }
                for ((i, tag) in missing.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val label = Languages.label(tag)
                    val msg = "Downloading MT pack $label (${i + 1}/${missing.size})…"
                    lastStatus.set(msg)
                    onStatus?.invoke(msg)
                    val model = TranslateRemoteModel.Builder(tag).build()
                    val conditions = DownloadConditions.Builder().build()
                    Tasks.await(modelManager.download(model, conditions), 180, TimeUnit.SECONDS)
                    currentCoroutineContext().ensureActive()
                }
                val msg = "MT packs ready"
                lastStatus.set(msg)
                onStatus?.invoke(msg)
                EnsureResult.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // A failed blocking SDK wait can return after its readiness owner was
                // canceled. Do not turn that retired failure into new status/output.
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "ensureModels failed", t)
                val msg = t.message?.takeIf { it.isNotBlank() }
                    ?: "Translation pack download failed"
                lastStatus.set(msg)
                onStatus?.invoke(msg)
                EnsureResult.Failed(msg)
            }
        }
    }

    override suspend fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult =
        withContext(Dispatchers.IO) {
            val detected = normalizeLang(result.language)
            val target = normalizeLang(settings.targetLanguage)
            val input = normalizeLang(settings.inputLanguage)

            // User-chosen input == target → ASR-only (replaces deprecated passthrough set).
            if (input.isNotEmpty() && input == target) {
                return@withContext result.copy(translatedText = null)
            }
            // Same ASR tag as target still needs MT when glyphs are a different script
            // (language-agnostic: mis-tagged ASR must not skip MT and flash wrong script).
            // When Whisper auto-translate-en / en-direct already yielded target lang, skip MT.
            if (detected.isNotEmpty() && detected != "auto" && detected == target &&
                !AsrJunkFilter.shouldHoldSourceOffOverlay(result.text, target)
            ) {
                return@withContext result.copy(translatedText = null)
            }
            if (target.isEmpty()) {
                return@withContext result.copy(translatedText = null)
            }

            val existing = result.translatedText
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(result.text, ignoreCase = false) }
            if (existing != null) {
                return@withContext result.copy(translatedText = existing)
            }

            // Language-agnostic source resolve (Input is a strong hint, not a hard lock):
            // 1) Dominant script on ASR text beats contradictory / target-identical / auto tags
            //    (SenseVoice auto often mis-tags; wrong source ⇒ garbage MT).
            // 2) Else trust ASR tag when it differs from target and is not "auto".
            // 3) Else Home inputLanguage when set and ≠ target (Live MT hint).
            // 4) Else ML Kit language-id.
            val script = guessScriptLang(result.text)
            val sourceCode = when {
                script != null && (
                    detected.isEmpty() ||
                        detected == "auto" ||
                        detected == target ||
                        (AsrJunkFilter.scriptFamilyForLang(detected) == AsrJunkFilter.ScriptFamily.LATIN &&
                            AsrJunkFilter.scriptFamilyOf(result.text) == AsrJunkFilter.ScriptFamily.CJK)
                    ) -> script
                detected.isNotEmpty() && detected != "auto" && detected != target -> detected
                input.isNotEmpty() && input != target -> input
                else -> script
                    ?: identifyLanguage(result.text)
                    ?: input.takeIf { it.isNotEmpty() && it != target }.orEmpty()
            }
            if (sourceCode.isEmpty() || sourceCode == target) {
                return@withContext result.copy(translatedText = null)
            }
            // Skip MT on crumbs — short source → nonsense target.
            if (!AsrJunkFilter.hasEnoughContentForMt(result.text)) {
                Log.i(TAG, "NMT skip $sourceCode→$target (insufficient content)")
                return@withContext result.copy(translatedText = null)
            }

            val translated = translateHotPath(result.text, sourceCode, target)
            val clean = AsrJunkFilter.sanitizeOrNull(translated)
            if (clean.isNullOrBlank() || clean == result.text) {
                Log.i(TAG, "NMT skip $sourceCode→$target (empty, identical, or junk EN)")
                result.copy(translatedText = null)
            } else if (!AsrJunkFilter.hasEnoughContentForMt(clean) &&
                clean.length < result.text.length
            ) {
                // Tiny EN crumb from longer source is usually a failed MT — drop.
                Log.i(TAG, "NMT skip $sourceCode→$target (crumb outChars=${clean.length})")
                result.copy(translatedText = null)
            } else {
                Log.i(
                    TAG,
                    "NMT ok $sourceCode→$target inChars=${result.text.length} outChars=${clean.length}"
                )
                result.copy(translatedText = clean)
            }
        }

    /**
     * Mic From≠To path: trust picker languages, allow short real words (Hola / Ciao / 你好),
     * reject junk/music tags, never call hasEnoughContentForMt (Live crumb protection stays).
     */
    override suspend fun translateExplicit(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? = withContext(Dispatchers.IO) {
        val source = normalizeLang(sourceLang)
        val target = normalizeLang(targetLang)
        if (source.isEmpty() || target.isEmpty() || source == target) return@withContext null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null
        if (AsrJunkFilter.isJunk(trimmed) || AsrJunkFilter.isMusicOrSoundTag(trimmed)) {
            Log.i(TAG, "NMT explicit skip $source→$target (junk/music)")
            return@withContext null
        }
        if (!AsrJunkFilter.hasEnoughContentForExplicitMt(trimmed)) {
            Log.i(TAG, "NMT explicit skip $source→$target (no real word)")
            return@withContext null
        }
        val translated = translateHotPath(trimmed, source, target)
        val clean = AsrJunkFilter.sanitizeOrNull(translated)
        if (clean.isNullOrBlank() || clean == trimmed) {
            Log.i(TAG, "NMT explicit skip $source→$target (empty, identical, or junk out)")
            null
        } else {
            Log.i(
                TAG,
                "NMT explicit ok $source→$target inChars=${trimmed.length} outChars=${clean.length}"
            )
            clean
        }
    }

    private suspend fun identifyLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val tag = useClientTask("language-id", 2_000L, {
                LanguageIdentification.getClient(
                    LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
                )
            }) { it.identifyLanguage(text) }
            val n = normalizeLang(tag ?: "")
            if (n.isEmpty() || n == "und") null else n
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "language-id failed errorType=${t.javaClass.simpleName}")
            null
        }
    }

    /** Fast script→lang when ASR left language auto/blank (SenseVoice live). */
    private fun guessScriptLang(text: String): String? {
        var han = 0
        var hiraKata = 0
        var hangul = 0
        var letters = 0
        for (ch in text) {
            when {
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                    han++; letters++
                }
                ch.code in 0x3040..0x30FF -> {
                    hiraKata++; letters++
                }
                ch.code in 0xAC00..0xD7AF -> {
                    hangul++; letters++
                }
                ch.isLetter() -> letters++
            }
        }
        if (letters == 0) return null
        return when {
            han * 2 >= letters -> "zh"
            hiraKata * 2 >= letters -> "ja"
            hangul * 2 >= letters -> "ko"
            else -> null
        }
    }

    /** Live MT: short timeout; never await long model downloads on the caption path. */
    private suspend fun translateHotPath(text: String, source: String, target: String): String? {
        val sourceTag = toMlKitTag(source) ?: return null
        val targetTag = toMlKitTag(target) ?: return null
        if (sourceTag == targetTag) return null
        return try {
            useClientTask("$sourceTag>$targetTag", TRANSLATE_TIMEOUT_SEC * 1_000L, {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceTag)
                    .setTargetLanguage(targetTag)
                    .build()
                Translation.getClient(options)
            }) { it.translate(text) }?.trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "translate $source→$target failed/skipped errorType=${t.javaClass.simpleName}")
            null
        }
    }

    /** Reserve before invoking SDK code; only actual Task completion releases that use. */
    private suspend fun <C : Closeable> useClientTask(
        key: String,
        timeoutMs: Long,
        factory: () -> C,
        operation: (C) -> Task<String>
    ): String? {
        currentCoroutineContext().ensureActive()
        val client = acquireClient(key, factory) ?: return null
        val task = try {
            currentCoroutineContext().ensureActive()
            operation(client.value)
        } catch (t: Throwable) {
            finishUse(client)
            throw t
        }
        // Explicit executor: cleanup must not depend on Main/Activity delivery. An already
        // complete Task may invoke this inline, so the use was reserved before invocation.
        task.addOnCompleteListener(DIRECT_EXECUTOR) { finishUse(client) }
        val result = withTimeoutOrNull(timeoutMs) { task.await() }
        currentCoroutineContext().ensureActive()
        return synchronized(clientLock) { if (retired) null else result }
    }

    private fun <C : Closeable> acquireClient(key: String, factory: () -> C): Client<C>? =
        synchronized(creationLock) {
            synchronized(clientLock) {
                if (retired) return null
                // Keys are private and each key has one SDK client type.
                @Suppress("UNCHECKED_CAST")
                val cached = clients[key] as Client<C>?
                if (cached != null) {
                    cached.uses++
                    return cached
                }
            }
            val created = Client(factory())
            val accepted = synchronized(clientLock) {
                if (retired) false else {
                    clients[key] = created
                    true
                }
            }
            if (accepted) created else {
                closeClient(created.value)
                null
            }
        }

    private fun finishUse(client: Client<out Closeable>) {
        val close = synchronized(clientLock) {
            client.uses--
            retired && client.uses == 0
        }
        if (close) closeClient(client.value)
    }

    private fun closeClient(client: Closeable) {
        try { client.close() } catch (_: Throwable) { }
    }

    private fun downloadedTags(): Set<String> {
        return try {
            val models = Tasks.await(
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java),
                15,
                TimeUnit.SECONDS
            )
            models.map { it.language }.toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "list downloaded MT models failed: ${t.message}")
            emptySet()
        }
    }

    override fun release() {
        val idle = synchronized(clientLock) {
            if (retired) return
            retired = true
            clients.values.filter { it.uses == 0 }.also { clients.clear() }
        }
        idle.forEach { closeClient(it.value) }
        // Busy clients remain owned by their SDK completion listeners, even after an
        // await timeout or coroutine cancellation. No unused language-ID client is created.
    }

    companion object {
        private const val TAG = "MlKitTranslation"
        private const val TRANSLATE_TIMEOUT_SEC = 4L
        private val DIRECT_EXECUTOR = Executor { it.run() }

        val COMMON_SOURCES: Set<String> = setOf(
            "en", "es", "fr", "de", "zh", "ja", "ko", "pt", "it", "ru"
        )

        fun toMlKitTag(code: String): String? {
            val n = normalizeLangStatic(code)
            if (n.isEmpty()) return null
            return TranslateLanguage.fromLanguageTag(n)
        }

        fun normalizeLangStatic(code: String): String {
            val c = code.trim().lowercase()
            if (c.isEmpty() || c == "auto" || c == "unknown" || c == "und") return ""
            return c
                .removePrefix("<|")
                .removeSuffix("|>")
                .substringBefore('-')
                .substringBefore('_')
        }

        fun isSupportedTarget(code: String): Boolean = toMlKitTag(code) != null
    }
}

private fun normalizeLang(code: String): String = MlKitTranslationEngine.normalizeLangStatic(code)

/**
 * Overlay / recorder layout: target-language text is always primary when MT filled
 * [CaptionResult.translatedText]. Dual = target primary + source secondary.
 */
object CaptionDisplay {
    /**
     * Target-language primary whenever MT filled [CaptionResult.translatedText].
     * Dual honesty: secondary (source) only when dual is on **and** a real translation exists —
     * never invent a dual pair from source-only captions.
     *
     * When [targetLang] is set and dual is off: never return wrong-script source as primary
     * (blank primary → caller must not paint). Dual-on still allows source as secondary only.
     */
    fun primaryAndSecondary(
        policy: CaptionResult,
        dualSubtitles: Boolean,
        targetLang: String = ""
    ): Pair<String, String?> {
        val original = policy.text
        val inTarget = policy.translatedText?.takeIf { it.isNotBlank() }
        val target = targetLang.trim()
        if (inTarget != null) {
            val dualOk = dualSubtitles && inTarget != original
            // If MT text itself is wrong-script for target, do not paint it as primary.
            if (target.isNotEmpty() &&
                AsrJunkFilter.shouldHoldSourceOffOverlay(inTarget, target)
            ) {
                return "" to null
            }
            return if (dualOk) inTarget to original else inTarget to null
        }
        // No MT: only show source when same script as target (or no target set).
        if (target.isNotEmpty() &&
            AsrJunkFilter.shouldHoldSourceOffOverlay(original, target)
        ) {
            return "" to null
        }
        return original to null
    }
}
