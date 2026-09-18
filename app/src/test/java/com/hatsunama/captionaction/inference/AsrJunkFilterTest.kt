package com.hatsunama.captionaction.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Language-agnostic gates: near-dup must not drop phrase extensions;
 * wrong-script ASR must be held off the target overlay; crumbs vs real speech.
 */
class AsrJunkFilterTest {

    @Test
    fun nearDuplicate_exactMatch_isDuplicate() {
        assertTrue(AsrJunkFilter.isNearDuplicate("hello world", "Hello world!"))
    }

    @Test
    fun nearDuplicate_phraseExtension_isNotDuplicate() {
        // Partial window then fuller phrase — must keep the fuller text (all languages).
        assertFalse(
            AsrJunkFilter.isNearDuplicate(
                "I think we should",
                "I think we should go home now"
            )
        )
        assertFalse(
            AsrJunkFilter.isNearDuplicate(
                "nosotros vamos",
                "nosotros vamos a la playa juntos"
            )
        )
        assertFalse(
            AsrJunkFilter.isNearDuplicate(
                "我们一起",
                "我们一起去看电影吧"
            )
        )
    }

    @Test
    fun nearDuplicate_similarLengthPrefix_isDuplicate() {
        // Near-equal lengths with shared prefix → still duplicate (filler drift).
        assertTrue(AsrJunkFilter.isNearDuplicate("good morning", "good mornin"))
        assertTrue(AsrJunkFilter.isNearDuplicate("good morning", "good morning"))
    }

    @Test
    fun holdSource_cjkSourceLatinTarget_holds() {
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("这是一个测试句子", "en"))
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("これは試験です", "en"))
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("이것은 테스트입니다", "fr"))
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("你好世界", "es"))
    }

    @Test
    fun holdSource_latinSourceLatinTarget_doesNotHold() {
        assertFalse(AsrJunkFilter.shouldHoldSourceOffOverlay("Hello everyone", "en"))
        assertFalse(AsrJunkFilter.shouldHoldSourceOffOverlay("Bonjour le monde", "fr"))
        assertFalse(AsrJunkFilter.shouldHoldSourceOffOverlay("Hola amigos", "es"))
    }

    @Test
    fun holdSource_latinSourceCjkTarget_holds() {
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("Hello everyone", "zh"))
        assertTrue(AsrJunkFilter.shouldHoldSourceOffOverlay("Buenos dias", "ja"))
    }

    @Test
    fun holdSource_cjkSourceCjkTarget_doesNotHold() {
        assertFalse(AsrJunkFilter.shouldHoldSourceOffOverlay("这是一个测试", "zh"))
    }

    @Test
    fun scriptFamily_detectsCjkAndLatin() {
        assertEquals(AsrJunkFilter.ScriptFamily.CJK, AsrJunkFilter.scriptFamilyOf("中文字幕"))
        assertEquals(AsrJunkFilter.ScriptFamily.LATIN, AsrJunkFilter.scriptFamilyOf("Hola mundo"))
        assertEquals(AsrJunkFilter.ScriptFamily.LATIN, AsrJunkFilter.scriptFamilyForLang("es"))
        assertEquals(AsrJunkFilter.ScriptFamily.LATIN, AsrJunkFilter.scriptFamilyForLang("en"))
        assertEquals(AsrJunkFilter.ScriptFamily.CJK, AsrJunkFilter.scriptFamilyForLang("zh"))
        assertEquals(AsrJunkFilter.ScriptFamily.CJK, AsrJunkFilter.scriptFamilyForLang("ja"))
    }

    @Test
    fun enoughForMt_realShortSpeechPasses() {
        assertTrue(AsrJunkFilter.hasEnoughContentForMt("你好")) // 2-char CJK word
        assertTrue(AsrJunkFilter.hasEnoughContentForMt("Hello there"))
        assertTrue(AsrJunkFilter.hasEnoughContentForMt("Bonjour"))
        assertFalse(AsrJunkFilter.hasEnoughContentForMt("yeah"))
        assertFalse(AsrJunkFilter.hasEnoughContentForMt("的"))
    }

    @Test
    fun sanitize_keepsRealSpeech() {
        assertEquals("你好", AsrJunkFilter.sanitizeOrNull("你好"))
        assertEquals("Hola amigos", AsrJunkFilter.sanitizeOrNull("Hola amigos"))
        // sanitizeOrNull logs via android.util.Log on reject — use isJunk on JVM unit tests.
        assertTrue(AsrJunkFilter.isJunk("yeah"))
        assertTrue(AsrJunkFilter.isJunk("嗯"))
    }

    @Test
    fun holdSource_mixedLatinMajorityWithCjk_holdsForLatinTarget() {
        // Majority Latin must not bypass the gate when CJK glyphs are present.
        assertTrue(
            AsrJunkFilter.shouldHoldSourceOffOverlay(
                "Hello everyone this is a long English line 你好",
                "en"
            )
        )
        assertTrue(
            AsrJunkFilter.shouldHoldSourceOffOverlay(
                "Muchas gracias por todo 谢谢",
                "es"
            )
        )
    }

    @Test
    fun holdSource_pureLatin_stillPassesForLatinTarget() {
        assertFalse(
            AsrJunkFilter.shouldHoldSourceOffOverlay(
                "Hello everyone this is a long English line only",
                "en"
            )
        )
    }
}
