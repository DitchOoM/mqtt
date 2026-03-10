package com.ditchoom.mqtt.codec.spi

import com.ditchoom.buffer.codec.processor.spi.CodecFieldProvider
import com.ditchoom.buffer.codec.processor.spi.CustomFieldDescriptor
import com.ditchoom.buffer.codec.processor.spi.FieldContext
import com.ditchoom.buffer.codec.processor.spi.FunctionRef

/**
 * SPI provider for `@MqttProperties` fields (MQTT v5 property sections).
 *
 * Delegates to extension functions defined in the models-v5 module:
 * - `ReadBuffer.readProperties(): Collection<Property>?`
 * - `WriteBuffer.writeProperties(properties: Collection<Property>?)`
 * - `propertiesSize(properties: Collection<Property>?): Int`
 *
 * These handle the VBI-prefixed property length + individual property encoding
 * as defined in MQTT v5 §2.2.2.
 */
class MqttPropertiesProvider : CodecFieldProvider {
    override val annotationFqn = "com.ditchoom.mqtt.codec.annotations.MqttProperties"

    override fun describe(context: FieldContext): CustomFieldDescriptor =
        CustomFieldDescriptor(
            readFunction = FunctionRef("com.ditchoom.mqtt5.controlpacket.properties", "readProperties"),
            writeFunction = FunctionRef("com.ditchoom.mqtt5.controlpacket.properties", "writeProperties"),
            fixedSize = -1,
            sizeOfFunction = FunctionRef("com.ditchoom.mqtt5.controlpacket.properties", "propertiesSize"),
        )
}
