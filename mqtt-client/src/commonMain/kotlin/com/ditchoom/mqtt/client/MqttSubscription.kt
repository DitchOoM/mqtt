package com.ditchoom.mqtt.client

import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IncomingPublish
import kotlinx.coroutines.Deferred

/**
 * A typed MQTT subscription that extends [Receiver] to provide a `Flow<IncomingPublish<P>>`
 * of decoded messages with full message metadata (topic, qos, dup, retain, v5 properties).
 *
 * The consumer gets both the decoded payload and the message context — critical for
 * wildcard subscriptions and request/response patterns.
 *
 * Covariant: a `MqttSubscription<ChatMessage>` can be used where `MqttSubscription<Any>` is expected.
 */
interface MqttSubscription<out P> : Receiver<IncomingPublish<P>> {
    /** The topic filter this subscription matches against. */
    val topicFilter: String

    /** Completes when the broker acknowledges the subscription (SUBACK received). */
    val suback: Deferred<ISubscribeAcknowledgement>

    /** Sends UNSUBSCRIBE and returns a deferred UNSUBACK. */
    suspend fun unsubscribe(): Deferred<IUnsubscribeAcknowledgment>
}
