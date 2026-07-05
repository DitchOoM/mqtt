package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling
import com.ditchoom.mqtt.controlpacket.format.ReasonCode

/**
 * Polymorphic constructors for the protocol packets that are *not* version-typed by
 * a `<P : Payload>` parameter — SUBSCRIBE / UNSUBSCRIBE / DISCONNECT / PING. Each
 * call site otherwise needs a `when (broker.protocolVersion)` to choose between the
 * v4 and v5 concrete types; routing through this factory keeps the version dispatch
 * at one site per packet kind.
 *
 * Version-typed packets (PUBLISH) construct via the typed [MqttClient.publish]
 * overload directly; wire-bytes decode flows through [MqttCodec] / the generated
 * `ControlPacketV*Codec.decodeAggregating`. Both used to live here too as a generic
 * `from(buffer) / publish(payload: ReadBuffer?)` pair — moved out under buffer-v1
 * because they duplicated the more direct paths.
 */
interface ControlPacketFactory {
    val protocolVersion: Int

    fun pingRequest(): IPingRequest

    fun pingResponse(): IPingResponse

    fun subscribe(
        topicFilter: TopicFilter,
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

    fun unsubscribe(
        topic: TopicFilter,
        userProperty: List<Pair<String, String>> = emptyList(),
    ) = unsubscribe(setOf(topic), userProperty)

    fun unsubscribe(
        topics: Set<TopicFilter>,
        userProperty: List<Pair<String, String>> = emptyList(),
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
        inMemory: Boolean = false,
    ): Persistence
}
