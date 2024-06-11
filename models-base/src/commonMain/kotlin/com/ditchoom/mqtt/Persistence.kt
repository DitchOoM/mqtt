package com.ditchoom.mqtt

import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.Topic

interface Persistence {
    suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean = false,
    ): Map<Topic, ISubscription>

    suspend fun clearMessages(broker: MqttBroker)

    suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: IPublishMessage,
    ): Int

    suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IPublishMessage?

    suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int

    suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest?

    suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket>

    suspend fun incomingPublish(
        broker: MqttBroker,
        packet: IPublishMessage,
        replyMessage: ControlPacket,
    )

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
}
