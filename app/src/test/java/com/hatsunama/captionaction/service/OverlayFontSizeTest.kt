package com.hatsunama.captionaction.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFontSizeTest {

    @Test
    fun tallShortCaption_usesSpareRoomAboveOld16Cap() {
        // ~320dp tall bubble, short EN caption
        val sp = OverlayFontSize.computePrimarySp(
            heightPx = 960,
            density = 3f,
            primaryChars = 28,
            secondaryChars = 0
        )
        assertTrue("expected >16sp for tall+short, got $sp", sp > 16f)
        assertTrue("expected <= MAX, got $sp", sp <= OverlayFontSize.MAX_SP)
    }

    @Test
    fun longCaption_scalesDownTowardMin() {
        val shortSp = OverlayFontSize.computePrimarySp(960, 3f, 30, 0)
        val longSp = OverlayFontSize.computePrimarySp(960, 3f, 220, 180)
        assertTrue("long should be smaller: short=$shortSp long=$longSp", longSp < shortSp)
        assertTrue("long still >= MIN, got $longSp", longSp >= OverlayFontSize.MIN_SP)
    }

    @Test
    fun minBubble_staysReadableNotTiny() {
        // ~180dp min height
        val sp = OverlayFontSize.computePrimarySp(540, 3f, 40, 0)
        assertTrue("min readable, got $sp", sp >= OverlayFontSize.MIN_SP)
        assertTrue("not absurd on min bubble, got $sp", sp <= 18f)
    }

    @Test
    fun largestFitting_prefersFitOverCeilingWhenCrowded() {
        val density = 3f
        val heightPx = 600 // 200dp
        val widthPx = 600 // 200dp — narrow
        val ceiling = OverlayFontSize.computePrimarySp(heightPx, density, 180, 0)
        val fitted = OverlayFontSize.largestFittingSp(
            heightPx = heightPx,
            widthPx = widthPx,
            density = density,
            primaryChars = 180,
            secondaryChars = 0,
            statusVisible = false,
            cjkHeavy = false
        )
        assertTrue("fitted ($fitted) <= ceiling ($ceiling)", fitted <= ceiling + 0.01f)
        assertTrue("fitted >= MIN, got $fitted", fitted >= OverlayFontSize.MIN_SP)
    }

    @Test
    fun largestFitting_shortInTall_nearCeiling() {
        val density = 3f
        val heightPx = 1050 // 350dp
        val widthPx = 900 // 300dp
        val fitted = OverlayFontSize.largestFittingSp(
            heightPx = heightPx,
            widthPx = widthPx,
            density = density,
            primaryChars = 24,
            secondaryChars = 0,
            statusVisible = false,
            cjkHeavy = false
        )
        assertTrue("short+tall should be large, got $fitted", fitted >= 20f)
    }

    @Test
    fun cjkHeavy_detection() {
        assertTrue(OverlayFontSize.isCjkHeavy("这是一个测试句子用于字幕"))
        assertFalse(OverlayFontSize.isCjkHeavy("Hello everyone this is a test"))
    }

    @Test
    fun contentDensityFactor_bounds() {
        assertEquals(1f, OverlayFontSize.contentDensityFactor(10), 0.001f)
        assertEquals(0.48f, OverlayFontSize.contentDensityFactor(300), 0.001f)
        val mid = OverlayFontSize.contentDensityFactor(150)
        assertTrue(mid in 0.48f..1f)
    }
}
