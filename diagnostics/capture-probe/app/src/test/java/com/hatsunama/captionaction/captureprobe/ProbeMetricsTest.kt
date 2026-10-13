package com.hatsunama.captionaction.captureprobe

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ProbeMetricsTest {
    @Test fun zeroIsSilenceNotProofOfCapture() {
        val m = ProbeMetrics(); m.accept(ShortArray(64000))
        assertEquals(0.0, m.rms(), 0.0); assertEquals(0L, m.nonzero)
        assertFalse(phasePassed(ProbePhase.PERMITTED_TONE, m.samples, m.rms(), 96000))
        assertTrue(phasePassed(ProbePhase.SILENT, m.samples, m.rms(), 96000))
    }
    @Test fun metricsAreChunkIndependentAndLittleEndian() {
        val a = ProbeMetrics(); a.accept(shortArrayOf(-32768, 0, 32767))
        val b = ProbeMetrics(); b.accept(shortArrayOf(-32768)); b.accept(shortArrayOf(0, 32767))
        assertEquals(32768, a.peak); assertEquals(2L, a.nonzero)
        assertEquals(a.rms(), b.rms(), 0.0)
        val expected = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0,-128,0,0,-1,127)).joinToString("") { "%02x".format(it) }
        assertEquals(expected, a.finishHash()); assertEquals(expected, b.finishHash())
    }
    @Test fun deniedSourceMustBeSilent() {
        assertFalse(phasePassed(ProbePhase.DENIED_TONE, 80000, 3000.0, 96000))
        assertTrue(phasePassed(ProbePhase.DENIED_TONE, 80000, 0.0, 96000))
    }
    @Test fun stalledSourceOrInsufficientCaptureCannotPass() {
        for (phase in ProbePhase.values()) {
            val rms = if (phase.tone && phase.allowed) 4000.0 else 0.0
            assertFalse(phasePassed(phase, 0, rms, 96000))
            assertFalse(phasePassed(phase, 80000, rms, 0))
            assertFalse(phasePassed(phase, 80000, Double.NaN, 96000))
        }
    }

    @Test fun positiveRequiresKnownToneNotArbitraryEnergy() {
        val m = ProbeMetrics()
        m.accept(ShortArray(64000) { (6000 * kotlin.math.sin(2 * Math.PI * 733 * it / 16000)).toInt().toShort() })
        assertTrue(m.toneFraction() > 0.99)
        assertTrue(phasePassed(ProbePhase.PERMITTED_TONE, m.samples, m.rms(), 96000, m.toneFraction()))
        assertFalse(phasePassed(ProbePhase.PERMITTED_TONE, m.samples, m.rms(), 96000, 0.2))
    }
    @Test fun outputGuardFailsClosed() {
        assertTrue(safeOutput(0, false, false, true))
        assertFalse(safeOutput(1, false, false, true))
        assertFalse(safeOutput(0, true, false, true))
        assertFalse(safeOutput(0, false, true, true))
        assertFalse(safeOutput(0, false, false, false))
    }
    @Test fun countExcludesStaleBufferTail() {
        val m = ProbeMetrics(); m.accept(shortArrayOf(0, 32767), 1)
        assertEquals(1L, m.samples); assertEquals(0.0, m.rms(), 0.0)
    }

    @Test fun offFrequencyToneCannotPass() {
        val m = ProbeMetrics()
        m.accept(ShortArray(64000) { (6000 * kotlin.math.sin(2 * Math.PI * 220 * it / 16000)).toInt().toShort() })
        assertTrue(m.rms() > 100.0); assertTrue(m.toneFraction() < 0.01)
        assertFalse(phasePassed(ProbePhase.PERMITTED_TONE, m.samples, m.rms(), 96000, m.toneFraction()))
    }
}
