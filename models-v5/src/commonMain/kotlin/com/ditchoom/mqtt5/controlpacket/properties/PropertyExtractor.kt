package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError

/**
 * Type-safe extractor for MQTT v5 property collections.
 *
 * Provides [single] for properties that must appear at most once (throws [ProtocolError] on
 * duplicates) and [list] for repeatable properties like [UserProperty]. After extraction,
 * [rejectUnknown] ensures no unexpected property types were present.
 *
 * Usage:
 * ```
 * fun from(props: Collection<MqttProperty>?): Properties {
 *     val p = PropertyExtractor(props, "CONNACK")
 *     val sessionExpiry = p.single<SessionExpiryInterval>()?.seconds
 *     val userProps = p.list<UserProperty>().map { it.key to it.value }
 *     p.rejectUnknown()
 *     return Properties(sessionExpiry, userProps)
 * }
 * ```
 */
class PropertyExtractor(
    properties: Collection<MqttProperty>?,
    private val packetName: String,
) {
    @PublishedApi
    internal val byType: Map<Any, List<MqttProperty>> =
        properties?.groupBy { it::class } ?: emptyMap()
    @PublishedApi
    internal val consumed: MutableSet<Any> = mutableSetOf()

    /**
     * Extracts a single property of type [T], or null if not present.
     * Throws [ProtocolError] if the property appears more than once.
     */
    inline fun <reified T : MqttProperty> single(): T? {
        consumed += T::class
        val list = byType[T::class] ?: return null
        if (list.size > 1) {
            throw ProtocolError("${T::class.simpleName} included multiple times")
        }
        @Suppress("UNCHECKED_CAST")
        return list.single() as T
    }

    /**
     * Extracts all properties of type [T] (for repeatable properties like [UserProperty]).
     */
    inline fun <reified T : MqttProperty> list(): List<T> {
        consumed += T::class
        @Suppress("UNCHECKED_CAST")
        return (byType[T::class] ?: emptyList()) as List<T>
    }

    /**
     * Throws [MalformedPacketException] if the collection contained any property types
     * that were not extracted via [single] or [list].
     */
    fun rejectUnknown() {
        for (key in byType.keys) {
            if (key !in consumed) {
                val typeName = (byType[key]?.firstOrNull())?.let { it::class.simpleName } ?: key.toString()
                throw MalformedPacketException(
                    "Invalid $packetName property type: $typeName",
                )
            }
        }
    }
}
