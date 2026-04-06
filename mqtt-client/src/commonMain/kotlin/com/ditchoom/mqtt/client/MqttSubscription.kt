package com.ditchoom.mqtt.client

import com.ditchoom.buffer.flow.Receiver
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import kotlinx.coroutines.Deferred

/**
 * A typed MQTT subscription that extends [Receiver] to provide a `Flow<P>` of decoded messages.
 *
 * The consumer never sees raw bytes — each incoming PUBLISH payload is decoded by the
 * [PayloadDecoder] registered at subscribe time, and the resulting [P] is emitted in the flow.
 *
 * Covariant: a `MqttSubscription<ChatMessage>` can be used where `MqttSubscription<Any>` is expected.
 */
interface MqttSubscription<out P> : Receiver<P> {
    /** The topic filter this subscription matches against. */
    val topicFilter: String

    /** Completes when the broker acknowledges the subscription (SUBACK received). */
    val suback: Deferred<ISubscribeAcknowledgement>

    /** Sends UNSUBSCRIBE and returns a deferred UNSUBACK. */
    suspend fun unsubscribe(): Deferred<IUnsubscribeAcknowledgment>
}
