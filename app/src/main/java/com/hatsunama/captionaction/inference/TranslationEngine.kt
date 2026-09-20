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
 * Offline MT owner: policy (input==target skip / prefer inputLanguage source) + ML Kit Translate.
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
            val input = normalizeLang(settings.inputLanguage)

            // User-chosen input == target → ASR-only (replaces deprecated passthrough set).
            if (input.isNotEmpty() && input == target) {
                return@withContext result.copy(translatedText = null)
            }
            // Same ASR tag as target still needs MT when glyphs are a different script
            // (language-agnostic: mis-tagged ASR must not skip MT and flash wrong script).
            if (detected.isNotEmpty() && detected == target &&
                input.isEmpty() &&
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

            // Language-agnostic source resolve:
            // 0) Prefer Home inputLanguage when set and ≠ target (Live Whisper tags it too).
            // 1) Dominant script on ASR text beats contradictory / target-identical tags
            //    (SenseVoice auto often mis-tags; wrong source ⇒ garbage MT).
            // 2) Else trust ASR tag when it differs from target.
            // 3) Else ML Kit language-id.
            val script = guessScriptLang(result.text)
            val sourceCode = when {
                input.isNotEmpty() && input != target -> input
                script != null && (
                    detected.isEmpty() ||
                        detected == target ||
                        (AsrJunkFilter.scriptFamilyForLang(detected) == AsrJunkFilter.ScriptFamily.LATIN &&
                            AsrJunkFilter.scriptFamilyOf(result.text) == AsrJunkFilter.ScriptFamily.CJK)
                    ) -> script
                detected.isNotEmpty() && detected != target -> detected
                else -> script
                    ?: identifyLanguage(result.text)
                    ?: ""
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
                Log.i(TAG, "NMT skip $sourceCode→$target (crumb out=${clean.take(40)})")
                result.copy(translatedText = null)
            } else {
                Log.i(
                    TAG,
                    "NMT ok $sourceCode→$target in=${result.text.take(40)} out=${clean.take(40)}"
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
                "NMT explicit ok $source→$target in=${trimmed.take(40)} out=${clean.take(40)}"
            )
            clean
        }
    }

    private suspend fun identifyLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val tag = Tasks.await(languageId.identifyLanguage(text), 2, TimeUnit.SECONDS)
            val n = normalizeLang(tag ?: "")
            if (n.isEmpty() || n == "und") null else n
        } catch (t: Throwable) {
            Log.w(TAG, "language-id failed: ${t.message}")
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
            val translator = translatorFor(sourceTag, targetTag)
            Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)?.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "translate $source→$target failed/skipped: ${t.message}")
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
        private const val TRANSLATE_TIMEOUT_SEC = 4L

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
