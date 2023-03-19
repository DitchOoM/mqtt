@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_FEATURE_WARNING")

package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.MalformedPacketException

fun String.validateMqttUTF8StringOrNull(): String? {
    if (validateMqttUTF8String()) {
        return this
    }
    return null
}

@Throws(InvalidMqttUtf8StringMalformedPacketException::class)
fun validateMqttUTF8StringOrThrowWith(string: String): String {
    val e = string.validateMqttString(true) ?: return string
    throw e
}

@Throws(InvalidMqttUtf8StringMalformedPacketException::class)
fun String.validateMqttUTF8StringOrThrow(): String {
    val e = validateMqttString(true) ?: return this
    throw e
}

fun String.validateMqttUTF8String(): Boolean {
    return validateMqttString(true) == null
}

class InvalidMqttUtf8StringMalformedPacketException(
    msg: String,
    indexOfError: Int,
    originalString: String
) :
    MalformedPacketException("Fails to match MQTT Spec for a UTF-8 String. Error:($msg) at index $indexOfError of $originalString")

private val controlCharactersRange by lazy(LazyThreadSafetyMode.NONE) { '\uD800'..'\uDFFF' }
private val shouldNotIncludeCharRange1 by lazy(LazyThreadSafetyMode.NONE) { '\u0001'..'\u001F' }
private val shouldNotIncludeCharRange2 by lazy(LazyThreadSafetyMode.NONE) { '\u007F'..'\u009F' }

/**
 * Cannot add planes 15 or 16 as it does not compile into a 'char' in kotlin
 * http://www.unicode.org/faq/private_use.html#pua2
 */
private val privateUseCharRange by lazy(LazyThreadSafetyMode.NONE) { '\uE000'..'\uF8FF' }

fun String.validateMqttString(includeWarnings: Boolean): InvalidMqttUtf8StringMalformedPacketException? {
    if (length > 65_535) {
        return InvalidMqttUtf8StringMalformedPacketException(
            "MQTT UTF-8 String too large",
            65_535,
            substring(0, 65_535)
        )
    }
    forEachIndexed { index, c ->
        if (c == '\u0000')
            return InvalidMqttUtf8StringMalformedPacketException(
                "Invalid Control Character null \\u0000",
                index,
                this
            )
        if (c in controlCharactersRange)
            return InvalidMqttUtf8StringMalformedPacketException(
                "Invalid Control Character (\\uD800..\\uDFFF)",
                index,
                this
            )
    }
    if (includeWarnings) {
        forEachIndexed { index, c ->
            if (c in shouldNotIncludeCharRange1)
                return InvalidMqttUtf8StringMalformedPacketException(
                    "Invalid Character in range (\\u0001..\\u001F)",
                    index,
                    this
                )
            if (c in shouldNotIncludeCharRange2)
                return InvalidMqttUtf8StringMalformedPacketException(
                    "Invalid Character in range (\\u007F..\\u009F)",
                    index,
                    this
                )
            if (c in privateUseCharRange)
                return InvalidMqttUtf8StringMalformedPacketException(
                    "Invalid Character in range (\\uE000..\\uF8FF)",
                    index,
                    this
                )
        }
    }
    return null
}

fun CharSequence.utf8Length(): Int {
    var count = 0
    var i = 0
    val len = length
    while (i < len) {
        val ch = get(i)
        when {
            ch.code <= 0x7F -> count++
            ch.code <= 0x7FF -> count += 2
            ch >= Char.MIN_HIGH_SURROGATE && ch.code < Char.MAX_HIGH_SURROGATE.code + 1 -> {
                count += 4
                ++i
            }

            else -> count += 3
        }
        i++
    }
    return count
}
