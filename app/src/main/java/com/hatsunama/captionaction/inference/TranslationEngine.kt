package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.util.Languages
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline MT owner: policy (passthrough / same-lang) + Google ML Kit on-device Translate.
 * ASR engines stay ASR; if they already filled [CaptionResult.translatedText] (e.g. whisper→EN), keep it.
 */
interface TranslationEngine {
    fun supportedTargets(): Set<String>

    suspend fun ensureModels(
        targetLanguage: String,
        extraSources: Set<String> = emptySet(),
        onStatus: ((String) -> Unit)? = null
    ): EnsureResult

    suspend fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult

    fun release()
}

sealed class EnsureResult {
    data object Ready : EnsureResult()
    data class Failed(val message: String) : EnsureResult()
}

class MlKitTranslationEngine(context: Context) : TranslationEngine {
    private val appContext = context.applicationContext
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val downloadMutex = Mutex()
    private val lastStatus = AtomicReference("idle")

    private val languageId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build()
        )
    }

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
                val already = downloadedTags()
                val missing = tags.filter { it !in already }
                if (missing.isEmpty()) {
                    val msg = "MT packs ready (${tags.size})"
                    lastStatus.set(msg)
                    onStatus?.invoke(msg)
                    return@withContext EnsureResult.Ready
                }
                for ((i, tag) in missing.withIndex()) {
                    val label = Languages.label(tag)
                    val msg = "Downloading MT pack $label (${i + 1}/${missing.size})…"
                    lastStatus.set(msg)
                    onStatus?.invoke(msg)
                    val model = TranslateRemoteModel.Builder(tag).build()
                    val conditions = DownloadConditions.Builder().build()
                    Tasks.await(modelManager.download(model, conditions), 180, TimeUnit.SECONDS)
                }
                val msg = "MT packs ready"
                lastStatus.set(msg)
                onStatus?.invoke(msg)
                EnsureResult.Ready
            } catch (t: Throwable) {
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
            val passthrough = settings.passthroughLanguages.map { normalizeLang(it) }.toSet()

            if (detected.isNotEmpty() && detected in passthrough) {
                return@withContext result.copy(translatedText = null)
            }
            if (detected.isNotEmpty() && detected == target) {
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

            val sourceCode = when {
                detected.isNotEmpty() && detected != target -> detected
                else -> identifyLanguage(result.text) ?: ""
            }
            if (sourceCode.isEmpty() || sourceCode == target) {
                return@withContext result.copy(translatedText = null)
            }

            val translated = translate(result.text, sourceCode, target)
            if (translated.isNullOrBlank() || translated == result.text) {
                result.copy(translatedText = null)
            } else {
                result.copy(translatedText = translated)
            }
        }

    private suspend fun identifyLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val tag = Tasks.await(languageId.identifyLanguage(text), 5, TimeUnit.SECONDS)
            val n = normalizeLang(tag ?: "")
            if (n.isEmpty() || n == "und") null else n
        } catch (t: Throwable) {
            Log.w(TAG, "language-id failed: ${t.message}")
            null
        }
    }

    private suspend fun translate(text: String, source: String, target: String): String? {
        val sourceTag = toMlKitTag(source) ?: return null
        val targetTag = toMlKitTag(target) ?: return null
        if (sourceTag == targetTag) return null
        return try {
            val translator = translatorFor(sourceTag, targetTag)
            // Ensure this pair's models exist (lazy download if Home didn't finish).
            Tasks.await(translator.downloadModelIfNeeded(), 120, TimeUnit.SECONDS)
            Tasks.await(translator.translate(text), 15, TimeUnit.SECONDS)?.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "translate $source→$target failed: ${t.message}")
            null
        }
    }

    private fun translatorFor(sourceTag: String, targetTag: String): Translator {
        val key = "$sourceTag>$targetTag"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()
            Translation.getClient(options)
        }
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
        translators.values.forEach { t ->
            try {
                t.close()
            } catch (_: Throwable) {
            }
        }
        translators.clear()
        try {
            languageId.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "MlKitTranslation"

        /** Prefer these sources cached with the user target so Fast/any ASR can MT quickly. */
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

object CaptionDisplay {
    fun primaryAndSecondary(policy: CaptionResult, dualSubtitles: Boolean): Pair<String, String?> {
        val original = policy.text
        val inTarget = policy.translatedText
        val primary = inTarget?.takeIf { it.isNotBlank() } ?: original
        val dualOk = dualSubtitles && inTarget != null && inTarget != original
        return if (dualOk) {
            primary to original
        } else {
            primary to null
        }
    }
}
