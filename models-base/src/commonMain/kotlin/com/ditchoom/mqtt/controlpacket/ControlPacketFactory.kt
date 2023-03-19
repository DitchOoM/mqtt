package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling
import com.ditchoom.mqtt.controlpacket.format.ReasonCode

interface ControlPacketFactory {
    val protocolVersion: Int

    fun from(buffer: ReadBuffer): ControlPacket {
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        return from(buffer, byte1, remainingLength)
    }

    fun from(buffer: ReadBuffer, byte1: UByte, remainingLength: Int): ControlPacket

    fun pingRequest(): IPingRequest
    fun pingResponse(): IPingResponse

    fun subscribe(
        topicFilter: Topic,
        maximumQos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        noLocal: Boolean = false,
        retainAsPublished: Boolean = false,
        retainHandling: RetainHandling = RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
        serverReference: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): ISubscribeRequest

    fun subscribe(
        subscriptions: Set<ISubscription>,
        serverReference: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): ISubscribeRequest

    fun publish(
        dup: Boolean = false,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        retain: Boolean = false,
        topicName: Topic,
        payload: ReadBuffer? = null,
        // MQTT 5 Properties
        payloadFormatIndicator: Boolean = false,
        messageExpiryInterval: Long? = null,
        topicAlias: Int? = null,
        responseTopic: Topic? = null,
        correlationData: ReadBuffer? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
        subscriptionIdentifier: Set<Long> = emptySet(),
        contentType: String? = null
    ): IPublishMessage

    fun unsubscribe(
        topic: Topic,
        userProperty: List<Pair<String, String>> = emptyList()
    ) = unsubscribe(setOf(topic), userProperty)

    fun unsubscribe(
        topics: Set<Topic>,
        userProperty: List<Pair<String, String>> = emptyList()
    ): IUnsubscribeRequest

    fun disconnect(
        reasonCode: ReasonCode = ReasonCode.NORMAL_DISCONNECTION,
        sessionExpiryIntervalSeconds: ULong? = null,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): IDisconnectNotification

    suspend fun defaultPersistence(
        androidContext: Any? = null,
        name: String = "mqtt$protocolVersion.db",
        inMemory: Boolean = false
    ): Persistence
}
