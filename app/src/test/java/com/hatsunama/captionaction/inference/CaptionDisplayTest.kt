package com.hatsunama.captionaction.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final paint layout: wrong-script source must not become primary when target is set
 * and dual is off; dual secondary only with real MT.
 */
class CaptionDisplayTest {

    private fun result(text: String, translated: String? = null, lang: String = "auto") =
        CaptionResult(
            text = text,
            language = lang,
            confidence = 0.9f,
            startMs = 0L,
            endMs = 1000L,
            translatedText = translated
        )

    @Test
    fun dualOff_wrongScriptSource_returnsBlankPrimary() {
        val (primary, secondary) = CaptionDisplay.primaryAndSecondary(
            result("这是一个测试句子"),
            dualSubtitles = false,
            targetLang = "en"
        )
        assertEquals("", primary)
        assertNull(secondary)
    }

    @Test
    fun dualOff_mtNull_sameScript_showsSource() {
        val (primary, secondary) = CaptionDisplay.primaryAndSecondary(
            result("Hello everyone"),
            dualSubtitles = false,
            targetLang = "en"
        )
        assertEquals("Hello everyone", primary)
        assertNull(secondary)
    }

    @Test
    fun dualOff_mtFilled_showsTargetOnly() {
        val (primary, secondary) = CaptionDisplay.primaryAndSecondary(
            result("这是一个测试", translated = "This is a test"),
            dualSubtitles = false,
            targetLang = "en"
        )
        assertEquals("This is a test", primary)
        assertNull(secondary)
    }

    @Test
    fun dualOn_mtFilled_showsTargetPlusSource() {
        val (primary, secondary) = CaptionDisplay.primaryAndSecondary(
            result("这是一个测试", translated = "This is a test"),
            dualSubtitles = true,
            targetLang = "en"
        )
        assertEquals("This is a test", primary)
        assertEquals("这是一个测试", secondary)
    }

    @Test
    fun dualOn_mtNull_wrongScript_doesNotInventDual() {
        val (primary, secondary) = CaptionDisplay.primaryAndSecondary(
            result("这是一个测试"),
            dualSubtitles = true,
            targetLang = "en"
        )
        assertEquals("", primary)
        assertNull(secondary)
    }

    @Test
    fun dualOff_mixedScriptSource_blankPrimary() {
        val (primary, _) = CaptionDisplay.primaryAndSecondary(
            result("Hello world 你好世界"),
            dualSubtitles = false,
            targetLang = "en"
        )
        assertTrue(primary.isEmpty())
    }
}
