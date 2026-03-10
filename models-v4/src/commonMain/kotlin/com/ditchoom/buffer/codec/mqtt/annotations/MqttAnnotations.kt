package com.ditchoom.buffer.codec.mqtt.annotations

/**
 * Marks a field as an MQTT Variable Byte Integer (VBI).
 * Valid on `Int` fields only. Range: 0..268,435,455.
 *
 * The codec reads/writes using the MQTT VBI encoding (1-4 bytes, 7 bits per byte,
 * high bit as continuation flag).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class MqttVariableByteInteger

/**
 * Marks a field as an MQTT v5 Properties section.
 * Valid on `Collection<Property>?` fields.
 *
 * The codec reads/writes using the VBI-prefixed property length encoding
 * defined in MQTT v5 §2.2.2.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class MqttProperties
