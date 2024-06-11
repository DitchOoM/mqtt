package com.ditchoom.mqtt5.controlpacket.properties

enum class Type {
    BYTE,
    TWO_BYTE_INTEGER,
    FOUR_BYTE_INTEGER,
    UTF_8_ENCODED_STRING,
    BINARY_DATA,
    VARIABLE_BYTE_INTEGER,
    UTF_8_STRING_PAIR,
}
