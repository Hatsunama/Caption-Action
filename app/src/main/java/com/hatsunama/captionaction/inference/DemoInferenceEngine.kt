package com.hatsunama.captionaction.inference

import java.io.File
import kotlin.math.sqrt

/**
 * Local demo engine: no network. Emits rotating caption-like lines on a steady
 * timer so the overlay stays alive. No greeting / hello loop — content reads as
 * live captions from the start.
 */
class DemoInferenceEngine : InferenceEngine {
    override val name: String = "Demo (local stub)"

    private var loaded = false
    private var index = 0
    private var lastEmitMs = 0L

    private data class Line(val original: String, val translated: String, val lang: String)

    private val samples = listOf(
        Line("The match is tied going into the final minutes.", "El partido está empatado en los minutos finales.", "en"),
        Line("Download complete. Restart to apply the update.", "Descarga completa. Reinicia para aplicar la actualización.", "en"),
        Line("Next stop: Central Station.", "Próxima parada: Estación Central.", "en"),
        Line("She said the recipe needs more garlic.", "Ella dijo que la receta necesita más ajo.", "en"),
        Line("Volume up — this chorus hits hard.", "Sube el volumen — este estribillo pega fuerte.", "en"),
        Line("Quest updated: find the blue keycard.", "Misión actualizada: encuentra la tarjeta azul.", "en"),
        Line("Weather later: clear skies, light breeze.", "Clima más tarde: cielos despejados, brisa ligera.", "en"),
        Line("Skip intro is available in settings.", "Omitir intro está disponible en ajustes.", "en"),
        Line("Left lane clears after the bridge.", "El carril izquierdo se libera después del puente.", "en"),
        Line("Same audio. Captions stay on your phone.", "El mismo audio. Los subtítulos se quedan en tu teléfono.", "en")
    )

    override suspend fun load(modelFile: File): Boolean {
        loaded = true
        lastEmitMs = 0L
        index = 0
        return true
    }

    override suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult? {
        if (!loaded) return null
        val now = System.currentTimeMillis()
        val energy = rms(pcm16le)
        val minGap = if (energy >= 120f) 1600L else 2200L
        if (lastEmitMs != 0L && now - lastEmitMs < minGap) {
            return null
        }
        lastEmitMs = now
        val line = samples[index % samples.size]
        index++
        return CaptionResult(
            text = line.original,
            language = line.lang,
            confidence = (0.72f + (energy / 4000f).coerceIn(0f, 0.25f)),
            startMs = now,
            endMs = now + minGap,
            translatedText = line.translated
        )
    }

    override fun release() {
        loaded = false
        lastEmitMs = 0L
    }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        val step = (samples.size / 512).coerceAtLeast(1)
        var n = 0
        var i = 0
        while (i < samples.size) {
            val v = samples[i].toDouble()
            sum += v * v
            n++
            i += step
        }
        return sqrt(sum / n.coerceAtLeast(1)).toFloat()
    }
}
