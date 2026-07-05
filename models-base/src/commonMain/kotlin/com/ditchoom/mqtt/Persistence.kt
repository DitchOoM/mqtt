package com.ditchoom.mqtt

import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.TopicFilter

interface Persistence {
    suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean = false,
    ): Map<TopicFilter, ISubscription>

    suspend fun clearMessages(broker: MqttBroker)

    suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: PublishMessage,
    ): Int

    suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): PublishMessage?

    suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int

    suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest?

    suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket>

    /**
     * Persist an incoming QoS 1 or QoS 2 PUBLISH payload and metadata before dispatching
     * to the subscriber handler. Implementations MUST no-op for QoS 0.
     *
     * The row is written with state [INCOMING_STATE_RECEIVED_PENDING_HANDLER] and kept
     * on disk until [incomingHandlerComplete] (QoS 1) or the full QoS 2 handshake
     * completes. A process crash before acknowledgement preserves the message so
     * [incomingMessagesToRedispatch] can replay it on reconnect.
     */
    suspend fun persistIncomingPublish(
        broker: MqttBroker,
        packet: PublishMessage,
    )

    /**
     * Called after the subscriber handler returns successfully.
     *
     * - QoS 1: deletes the incoming row. Caller writes PUBACK to the wire immediately
     *   after this call returns.
     * - QoS 2: transitions the row to [INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT].
     *   Caller writes PUBREC immediately after. The row is deleted by [onPubCompWritten]
     *   once the broker sends PUBREL and we send PUBCOMP.
     *
     * No-op if no matching row exists (QoS 0 or already handled).
     */
    suspend fun incomingHandlerComplete(
        broker: MqttBroker,
        packetId: Int,
    )

    /**
     * Returns incoming QoS 1/2 publish records still on disk that need action on reconnect.
     *
     * Each [IncomingPublishRecord.state] dictates the caller's next step:
     * - [INCOMING_STATE_RECEIVED_PENDING_HANDLER] — redispatch to subscriber handler,
     *   then on success call [incomingHandlerComplete] and write PUBACK/PUBREC.
     * - [INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT] — do NOT redispatch the
     *   handler (already ran); resend PUBREC so the broker sends PUBREL.
     */
    suspend fun incomingMessagesToRedispatch(broker: MqttBroker): Collection<IncomingPublishRecord>

    suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    )

    suspend fun ackPubComplete(
        broker: MqttBroker,
        packet: IPublishComplete,
    )

    suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest

    suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest?

    suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease,
    )

    suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    )

    suspend fun onPubCompWritten(
        broker: MqttBroker,
        outPubComp: IPublishComplete,
    )

    suspend fun ackSub(
        broker: MqttBroker,
        subAck: ISubscribeAcknowledgement,
    )

    suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    )

    suspend fun addBroker(
        connectionOp: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ): MqttBroker = addBroker(listOf(connectionOp), connectionRequest)

    suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttBroker

    suspend fun allBrokers(): Collection<MqttBroker>

    suspend fun brokerWithId(identifier: Int): MqttBroker?

    suspend fun removeBroker(identifier: Int)

    suspend fun isQueueClear(
        broker: MqttBroker,
        includeSubscriptions: Boolean = true,
    ): Boolean

    /**
     * Update the publish state for an outbound message.
     * State values: 0=QUEUED, 1=SENT, 2=PUBREC_RECEIVED, 3=PUBREL_SENT.
     */
    suspend fun updatePublishState(
        broker: MqttBroker,
        packetId: Int,
        state: Int,
    )

    companion object {
        const val STATE_QUEUED = 0
        const val STATE_SENT = 1
        const val STATE_PUBREC_RECEIVED = 2
        const val STATE_PUBREL_SENT = 3

        /** Incoming row states for [persistIncomingPublish] / [incomingMessagesToRedispatch]. */
        const val INCOMING_STATE_RECEIVED_PENDING_HANDLER = 0
        const val INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT = 1
    }
}

/**
 * A persisted incoming publish awaiting reconnect-time action. Returned by
 * [Persistence.incomingMessagesToRedispatch]. The [state] is one of
 * [Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER] or
 * [Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT].
 */
data class IncomingPublishRecord(
    val packet: PublishMessage,
    val state: Int,
)
