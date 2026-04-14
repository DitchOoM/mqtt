package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.PublishMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * QoS 1 publish state machine: Queued → Sent → Acknowledged.
 */
sealed interface QoS1State {
    /** Message persisted locally, waiting to be written to wire. */
    data object Queued : QoS1State

    /** Written to wire, waiting for PUBACK. */
    data object Sent : QoS1State

    /** PUBACK received — delivery confirmed. */
    data class Acknowledged(
        val ack: IPublishAcknowledgment,
    ) : QoS1State
}

/**
 * QoS 2 publish state machine: Queued → Sent → Received → Released → Complete.
 */
sealed interface QoS2State {
    /** Message persisted locally, waiting to be written to wire. */
    data object Queued : QoS2State

    /** PUBLISH written to wire, waiting for PUBREC. */
    data object Sent : QoS2State

    /** PUBREC received from broker. */
    data object Received : QoS2State

    /** PUBREL written to wire, waiting for PUBCOMP. */
    data object Released : QoS2State

    /** PUBCOMP received — exactly-once delivery confirmed. */
    data class Complete(
        val comp: IPublishComplete,
    ) : QoS2State
}

/**
 * Result of a publish operation. QoS level determines available states at the type level —
 * impossible to observe PUBREC on QoS 1, or await PUBACK on QoS 0.
 */
sealed interface PublishResult {
    /** QoS 0: fire and forget. No state to observe. */
    data object QoS0Sent : PublishResult

    /** QoS 1: observe [state] for Queued → Sent → Acknowledged progression. */
    data class QoS1(
        val packetId: Int,
        val state: StateFlow<QoS1State>,
    ) : PublishResult

    /** QoS 2: observe [state] for Queued → Sent → Received → Released → Complete progression. */
    data class QoS2(
        val packetId: Int,
        val state: StateFlow<QoS2State>,
    ) : PublishResult
}

data class SubscribeOperation(
    val packetId: Int,
    val subscriptions: Map<ISubscription, Flow<PublishMessage>>,
    val subAck: Deferred<ISubscribeAcknowledgement>,
) : Flow<PublishMessage> {
    override suspend fun collect(collector: FlowCollector<PublishMessage>) {
        combine(subscriptions.values.asIterable()) { array ->
            array.forEach { collector.emit(it) }
        }
    }
}

data class UnsubscribeOperation(
    val packetId: Int,
    val unsubAck: Deferred<IUnsubscribeAcknowledgment>,
)
