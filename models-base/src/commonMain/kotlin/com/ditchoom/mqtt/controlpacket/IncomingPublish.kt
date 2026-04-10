package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer

/**
 * Represents an incoming PUBLISH message received from a broker.
 *
 * This is the user-facing view of a received publish — it exposes only the fields
 * relevant to message consumption, not internal wire-format details like packet identifiers.
 *
 * The type parameter [P] represents the payload type:
 * - Wire-decoded messages have `IncomingPublish<ReadBuffer?>` (raw bytes)
 * - Typed subscriptions decode to `IncomingPublish<P>` (e.g., `IncomingPublish<ChatMessage>`)
 *
 * For MQTT v5 messages, this can be smart-cast to [IncomingPublishV5] to access
 * v5-specific properties like [IncomingPublishV5.responseTopic] and
 * [IncomingPublishV5.correlationData].
 */
interface IncomingPublish<out P> {
    /** The topic this message was published to. */
    val topic: TopicName

    /** Quality of Service level. */
    val qos: QualityOfService

    /** True if this is a duplicate delivery. */
    val dup: Boolean

    /** True if the broker retained this message. */
    val retain: Boolean

    /** The message payload. For raw messages this is [ReadBuffer]?; for typed subscriptions it is [P]. */
    val payload: P
}

/**
 * MQTT v5 incoming publish with additional properties.
 *
 * Extends [IncomingPublish] so that v4 consumers work transparently.
 * v5-specific fields are non-nullable where MQTT v5 guarantees a default,
 * and nullable only where the spec says the property is optional.
 */
interface IncomingPublishV5<out P> : IncomingPublish<P> {
    /**
     * True if the payload is UTF-8 encoded character data.
     * False (default) means unspecified bytes.
     */
    val payloadFormatIndicator: Boolean

    /** Message expiry interval in seconds, or null if the message does not expire. */
    val messageExpiryInterval: Long?

    /** Topic alias used by the broker, or null if not set. */
    val topicAlias: Int?

    /** Response topic for request/response pattern, or null if not a request. */
    val responseTopic: TopicName?

    /** Correlation data for request/response, or null if not set. Valid only during callback scope. */
    val correlationData: ReadBuffer?

    /** User properties as key-value pairs. Empty list if none. */
    val userProperty: List<Pair<String, String>>

    /** Subscription identifiers that matched this publish. Empty set if none. */
    val subscriptionIdentifier: Set<Long>

    /** MIME-type content type descriptor, or null if not set. */
    val contentType: String?

    /** True if this publish is a request (has a [responseTopic]). */
    val isRequest: Boolean get() = responseTopic != null
}
