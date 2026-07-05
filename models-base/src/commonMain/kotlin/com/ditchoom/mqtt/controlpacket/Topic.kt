package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.ProtocolError

sealed interface Topic {
    val root: Level

    fun matches(other: Topic): Boolean = root.matches(other.root)

    override fun toString(): String

    enum class Type {
        Name,
        Filter,
    }

    companion object {
        fun fromOrNull(
            topic: String,
            type: Type,
        ): Topic? =
            try {
                fromOrThrow(topic, type)
            } catch (e: MqttException) {
                null
            }

        @Throws(MqttException::class)
        fun fromOrThrow(
            topic: String,
            type: Type,
        ): Topic {
            val root = parseRoot(topic, type)
            return when (type) {
                Type.Name -> TopicName(root)
                Type.Filter -> TopicFilter(root)
            }
        }

        internal fun parseRoot(
            topic: String,
            type: Type,
        ): Level {
            val topicUtf8Length = topic.utf8Length()
            if (topicUtf8Length !in 1..65535) {
                val message =
                    if (topicUtf8Length < 1) {
                        "[MQTT-4.7.3-1] All Topic Names and Topic Filters MUST be at least one " +
                            "character long. ($topic)"
                    } else {
                        "[MQTT-4.7.3-3] Topic Names and Topic Filters are UTF-8 encoded strings, " +
                            "they MUST NOT encode to more than 65535 bytes. (size = " +
                            "$topicUtf8Length)"
                    }
                throw ProtocolError(message)
            }
            if (type == Type.Name && (topic.contains('+') || topic.contains('#'))) {
                throw ProtocolError(
                    "[MQTT-3.3.2-2] The Topic Name in the PUBLISH packet" +
                        " MUST NOT contain wildcard characters. ($topic)",
                )
            }
            val levelsFlat = topic.split('/')
            val reversed = levelsFlat.reversed().withIndex().iterator()
            var previousLevel: Level? = null
            while (reversed.hasNext()) {
                val next = reversed.next()
                val index = next.index
                val stringValue = next.value
                if (stringValue.length > 1 &&
                    (
                        stringValue.endsWith("+") ||
                            stringValue.endsWith(Level.MultiLevelWildcard.value)
                    )
                ) {
                    throw ProtocolError(
                        "[MQTT-4.7.1-2] The single-level wildcard can be used at any level " +
                            "in the Topic Filter, including first and last levels. Where it " +
                            "is used, it MUST occupy an entire level of the filter. ($topic)" +
                            " - ($stringValue)",
                    )
                }
                val currentLevel =
                    when (stringValue) {
                        "+" ->
                            when (type) {
                                Type.Name -> throw ProtocolError(
                                    "[MQTT-4.7.0-1] The wildcard characters can be used in " +
                                        "Topic Filters, but MUST NOT be used within a Topic " +
                                        "Name. ($topic) - ($stringValue)",
                                )
                                Type.Filter -> Level.SingleLevelWildcard(previousLevel)
                            }

                        Level.MultiLevelWildcard.value ->
                            when (type) {
                                Type.Name -> throw ProtocolError(
                                    "[MQTT-4.7.0-1] The wildcard characters can be used in " +
                                        "Topic Filters, but MUST NOT be used within a Topic " +
                                        "Name. ($topic) - ($stringValue)",
                                )
                                Type.Filter ->
                                    if (index > 0) {
                                        throw ProtocolError(
                                            "[MQTT-4.7.1-1] The multi-level wildcard " +
                                                "character MUST be specified either on its " +
                                                "own or following a topic level separator. " +
                                                "In either case it MUST be the last " +
                                                "character specified in the Topic Filter.",
                                        )
                                    } else {
                                        Level.MultiLevelWildcard
                                    }
                            }

                        else -> Level.StringLevel(stringValue, previousLevel)
                    }
                currentLevel.isPrefixedWithSlash = index == levelsFlat.size - 1 &&
                    topic.startsWith('/')
                currentLevel.isPostfixedWithSlash = index == 0 && topic.endsWith('/')
                previousLevel = currentLevel
            }
            return previousLevel ?: throw IllegalStateException(
                "Invalid state, should have " +
                    "thrown before this. $topic",
            )
        }
    }
}

/**
 * A concrete topic name — no wildcards allowed.
 * Used for publish targets and incoming message topics.
 */
class TopicName internal constructor(
    override val root: Level,
) : Topic {
    override fun toString(): String = root.toString()

    override fun equals(other: Any?): Boolean = other is TopicName && root == other.root

    override fun hashCode(): Int = root.hashCode()

    companion object {
        fun fromOrNull(topic: String): TopicName? =
            try {
                fromOrThrow(topic)
            } catch (e: MqttException) {
                null
            }

        @Throws(MqttException::class)
        fun fromOrThrow(topic: String): TopicName = TopicName(Topic.parseRoot(topic, Topic.Type.Name))
    }
}

/**
 * A topic filter — may contain + (single-level) and # (multi-level) wildcards.
 * Used for subscriptions and message filtering.
 */
class TopicFilter internal constructor(
    override val root: Level,
) : Topic {
    fun matches(name: TopicName): Boolean = root.matches(name.root)

    override fun toString(): String = root.toString()

    override fun equals(other: Any?): Boolean = other is TopicFilter && root == other.root

    override fun hashCode(): Int = root.hashCode()

    companion object {
        fun fromOrNull(topic: String): TopicFilter? =
            try {
                fromOrThrow(topic)
            } catch (e: MqttException) {
                null
            }

        @Throws(MqttException::class)
        fun fromOrThrow(topic: String): TopicFilter = TopicFilter(Topic.parseRoot(topic, Topic.Type.Filter))
    }
}
