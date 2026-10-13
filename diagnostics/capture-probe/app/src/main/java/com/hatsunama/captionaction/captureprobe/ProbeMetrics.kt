package com.hatsunama.captionaction.captureprobe

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/** Aggregate-only: no PCM or recognized/user text is retained by the report. */
class ProbeMetrics {
    var samples = 0L; private set
    var nonzero = 0L; private set
    var peak = 0; private set
    private var squared = 0.0
    private var sinSum = 0.0
    private var cosSum = 0.0
    private val digest = MessageDigest.getInstance("SHA-256")
    fun accept(pcm: ShortArray, count: Int = pcm.size) {
        require(count in 0..pcm.size)
        for (i in 0 until count) {
            val s = pcm[i].toInt()
            val angle = 2.0 * PI * 733.0 * samples / 16000.0
            sinSum += s * sin(angle)
            cosSum += s * cos(angle)
            samples++
            if (s != 0) nonzero++
            peak = maxOf(peak, abs(s))
            squared += s.toDouble() * s
            digest.update((s and 255).toByte())
            digest.update(((s ushr 8) and 255).toByte())
        }
    }
    fun toneFraction(): Double = if (squared == 0.0) 0.0 else (2.0 * (sinSum * sinSum + cosSum * cosSum) / (samples * squared)).coerceIn(0.0, 1.0)
    fun rms(): Double = if (samples == 0L) 0.0 else sqrt(squared / samples)
    fun finishHash(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

enum class ProbePhase(val allowed: Boolean, val tone: Boolean) {
    SILENT(true, false), PERMITTED_TONE(true, true), DENIED_TONE(false, true)
}

fun phasePassed(phase: ProbePhase, samples: Long, rms: Double, sourceFrames: Long, toneFraction: Double = 0.0): Boolean =
    samples >= 32000 && sourceFrames >= 64000 && rms.isFinite() &&
        if (phase == ProbePhase.PERMITTED_TONE) rms >= 100.0 && toneFraction >= 0.70 else rms < 2.0

/** Feed actual runtime state; reject fixed/external routes and non-normal audio modes. */
fun safeOutput(volume: Int, fixedVolume: Boolean, externalOutputs: Boolean, normalMode: Boolean): Boolean =
    volume == 0 && !fixedVolume && !externalOutputs && normalMode
