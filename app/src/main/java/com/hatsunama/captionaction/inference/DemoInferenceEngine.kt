package com.hatsunama.captionaction.inference

import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local demo engine: no network. Emits rotating sample captions when audio
 * energy is present (or on a timer if silence). Used until a native ASR
 * backend is linked. Models may still be downloaded for future use.
 */
class DemoInferenceEngine : InferenceEngine {
    override val name: String = "Demo (local stub)"

    private var loaded = false
    private var index = 0
    private var lastEmitMs = 0L

    private val samples = listOf(
        CaptionResult("Hello, welcome to Caption Action.", "en", 0.9f, 0, 2000),
        CaptionResult("Everything runs on your phone.", "en", 0.88f, 0, 2000),
        CaptionResult("No cloud. No accounts. No ads.", "en", 0.92f, 0, 2000),
        CaptionResult("Move and resize the overlay anytime.", "en", 0.85f, 0, 2000),
        CaptionResult("Same audio. A brighter world.", "en", 0.91f, 0, 2000)
    )

    override suspend fun load(modelFile: File): Boolean {
        // Demo does not require the file; mark ready either way.
        loaded = true
        return true
    }

    override suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult? {
        if (!loaded) return null
        val now = System.currentTimeMillis()
        if (now - lastEmitMs < 2200L) {
            delay(50)
            return null
        }
        val energy = rms(pcm16le)
        // Emit periodically; prefer when there is audible energy.
        if (energy < 80f && now - lastEmitMs < 4500L) return null
        lastEmitMs = now
        val base = samples[index % samples.size]
        index++
        return base.copy(
            startMs = now,
            endMs = now + 2000,
            confidence = (0.7f + (energy / 5000f).coerceIn(0f, 0.25f))
        )
    }

    override fun release() {
        loaded = false
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
