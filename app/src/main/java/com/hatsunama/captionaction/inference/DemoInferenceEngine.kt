package com.hatsunama.captionaction.inference

import java.io.File
import kotlin.math.sqrt

/**
 * Local demo engine: no network. Keeps emitting rotating captions on a steady
 * timer so the overlay never freezes after the greeting. Energy only nudges
 * confidence / pace — silence still advances lines.
 */
class DemoInferenceEngine : InferenceEngine {
    override val name: String = "Demo (local stub)"

    private var loaded = false
    private var index = 0
    private var lastEmitMs = 0L

    private data class Line(val original: String, val translated: String, val lang: String)

    private val samples = listOf(
        Line("Hello, welcome to Caption Action.", "Hola, bienvenido a Caption Action.", "en"),
        Line("Everything runs on your phone.", "Todo funciona en tu teléfono.", "en"),
        Line("No cloud. No accounts. No ads.", "Sin nube. Sin cuentas. Sin anuncios.", "en"),
        Line("Move and resize the overlay anytime.", "Mueve y redimensiona el overlay cuando quieras.", "en"),
        Line("Captions keep updating from live audio.", "Los subtítulos siguen actualizándose del audio.", "en"),
        Line("Same audio. A brighter world.", "El mismo audio. Un mundo más brillante.", "en"),
        Line("Playback capture or microphone — your choice.", "Captura de reproducción o micrófono — tú eliges.", "en"),
        Line("Tap the bottom X anytime to end.", "Toca la X inferior cuando quieras terminar.", "en")
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
        // Faster cadence when there is audible energy; still emit on silence.
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
