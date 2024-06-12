package com.ditchoom.mqtt.controlpacket

import kotlin.js.JsName
import kotlin.jvm.JvmName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MQTT Conformance Character data in a UTF-8 Encoded String MUST be well-formed UTF-8 as defined by the Unicode specification Unicode and restated in RFC 3629
 */
class StringTests {
    @Test
    fun invalidMqttString() = assertFalse("abc\u0001def".validateMqttUTF8String())

    @Test
    fun validMqttString() = assertTrue("abc\u002Fdef".validateMqttUTF8String())

    @Test
    fun invalidMqttStringPoint2() = assertFalse("abc\u007fdef".validateMqttUTF8String())

    @Test
    fun validMqttStringBasic() = assertTrue("abcdef".validateMqttUTF8String())

    @Test
    @JsName("utf8DoesNotHaveNull")
    @JvmName("utf8DoesNotHaveNull")
    fun `MQTT Conformance A UTF-8 Encoded String MUST NOT include an encoding of the null character U+0000`() {
        assertFalse { "\u0000".validateMqttUTF8String() }
    }

    // TODO: Fix this conformance test
//
//    @Test
//    @JsName("zeroWidthNoBreakSpace")
//    fun `MQTT Conformance A UTF-8 encoded sequence 0xEF 0xBB 0xBF is always interpreted as U+FEFF ZERO WIDTH NO-BREAK SPACE wherever it appears in a string and MUST NOT be skipped over or stripped off by a packet receiver `() {
//        val bytes = PlatformBuffer.wrap(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
//        val string = bytes.readString(3, Charset.UTF8)
//        assertEquals(0xFEFF.toChar().code, string[0].code)
//    }

    // The string AuD869uDED4 which is LATIN CAPITAL Letter A followed by the code point U+2A6D4
    // which represents a CJK IDEOGRAPH EXTENSION B character is encoded
    @Test
    @JsName("latinCaptialNoNormativeTest")
    @JvmName("latinCaptialNoNormativeTest")
    fun latinTest() {
        val string = "A\uD869\uDED4"
        assertFalse { string.validateMqttUTF8String() }
        assertEquals("A𪛔", "A\uD869\uDED4")
    }

    @Test
    fun controlCharacterU0001toU001F() {
        for (c in 0x0001..0x001F) {
            val string = c.toString()
            assertTrue { string.validateMqttUTF8String() }
        }
    }

    @Test
    fun stringLengthOverflow() {
        assertFalse { "a".repeat(65_536).validateMqttUTF8String() }
    }

    @Test
    fun controlCharacterUD800toUDFFF() {
        for (c in '\uD800'..'\uDFFF') {
            assertFalse { c.toString().validateMqttUTF8String() }
        }
    }

    @Test
    fun controlCharacterU007FtoU009F() {
        for (c in '\u007F'..'\u009F') {
            assertFalse { c.toString().validateMqttUTF8String() }
        }
    }
}
