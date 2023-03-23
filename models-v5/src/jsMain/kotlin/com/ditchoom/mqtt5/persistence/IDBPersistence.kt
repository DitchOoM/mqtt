package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.Persistence
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
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishMessage
import com.ditchoom.mqtt5.controlpacket.PublishReceived
import com.ditchoom.mqtt5.controlpacket.PublishRelease
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import web.errors.DOMException
import web.idb.IDBDatabase
import web.idb.IDBFactory
import web.idb.IDBKeyRange
import web.idb.IDBObjectStore
import web.idb.IDBRequest
import web.idb.IDBTransaction
import web.idb.IDBTransactionMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class IDBPersistence(private val db: IDBDatabase) : Persistence {

    private val dispatcher = defaultDispatcher(0, "unused")
    override suspend fun ackPub(broker: MqttBroker, packet: IPublishAcknowledgment) {
        val tx = db.transaction(arrayOf(PubMsg, UserProperties), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        queuedMsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 0))
        deleteUserProperties(tx, broker.identifier, packet.packetIdentifier, 0)
        commitTransaction(tx, "ackPub")
    }

    override suspend fun ackPubComplete(broker: MqttBroker, packet: IPublishComplete) {
        val tx = db.transaction(arrayOf(QoS2Msg, UserProperties), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 1))
        deleteUserProperties(tx, broker.identifier, packet.packetIdentifier, 1)
        commitTransaction(tx, "ackPubComplete")
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease
    ) {
        val p = pubRel as PublishRelease
        val tx = db.transaction(arrayOf(PubMsg, QoS2Msg, UserProperties), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, incomingPubRecv.packetIdentifier, 0))
        val propStore = deleteUserProperties(tx, broker.identifier, incomingPubRecv.packetIdentifier, 0)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                p.packetIdentifier,
                p.controlPacketValue,
                1,
                p.variable.reasonCode.byte.toInt(),
                p.variable.properties.reasonString
            )
        )
        for ((key, value) in p.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 1, p.packetIdentifier, key, value))
        }
        commitTransaction(tx, "ackPubReceivedQueuePubRelease")
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete
    ) {
        val p = outPubComp as PublishComplete
        val tx = db.transaction(arrayOf(QoS2Msg, UserProperties), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        val propStore = deleteUserProperties(tx, broker.identifier, incomingPubRel.packetIdentifier, 0)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                p.packetIdentifier,
                p.controlPacketValue,
                0,
                p.variable.reasonCode.byte.toInt(),
                p.variable.properties.reasonString
            )
        )
        for ((key, value) in p.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, p.packetIdentifier, key, value))
        }
        commitTransaction(tx, "ackPubRelease")
    }

    override suspend fun ackSub(broker: MqttBroker, subAck: ISubscribeAcknowledgement) {
        val tx = db.transaction(arrayOf(SubMsg, UserProperties), IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SubMsg)
        subMsgStore.delete(arrayOf(broker.identifier, subAck.packetIdentifier))
        deleteUserProperties(tx, broker.identifier, subAck.packetIdentifier, 0)
        commitTransaction(tx, "ackSub")
    }

    override suspend fun ackUnsub(broker: MqttBroker, unsubAck: IUnsubscribeAcknowledgment) {
        val key = arrayOf(broker.identifier, unsubAck.packetIdentifier)
        val tx = db.transaction(arrayOf(UnsubMsg, Subscription, UserProperties), IDBTransactionMode.readwrite)
        val unsubMsgStore = tx.objectStore(UnsubMsg)
        unsubMsgStore.delete(arrayOf(broker.identifier, unsubAck.packetIdentifier))
        deleteUserProperties(tx, broker.identifier, unsubAck.packetIdentifier, 0)
        val subStore = tx.objectStore(Subscription)
        val unsubIndex = subStore.index(UnsubIndex)

        val unsubSubscriptions = request { unsubIndex.getAll(key) }
        for (unsubscription in unsubSubscriptions) {
            val d = unsubscription.asDynamic()
            PersistableUnsubscribe(d.brokerId.unsafeCast<Int>(), unsubAck.packetIdentifier)
            subStore.delete(arrayOf(broker.identifier, d.topicFilter))
        }
        commitTransaction(tx, "ackUnsub")
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean
    ): Map<Topic, ISubscription> {
        val tx = db.transaction(Subscription, IDBTransactionMode.readonly)
        val subStore = tx.objectStore(Subscription)
        val index = subStore.index(BrokerIndex)
        val subscriptionsRaw = request { index.getAll(broker.identifier) }
        commitTransaction(tx, "activeSubscriptions")
        return subscriptionsRaw
            .map {
                val d = it.asDynamic()
                PersistableSubscription(
                    d.brokerId as Int,
                    d.topicFilter as String,
                    d.subscribeId as Int,
                    d.unsubscribeId as Int,
                    d.qos as Byte,
                    d.noLocal as Boolean,
                    d.retainAsPublished as Boolean,
                    d.retainHandling as Int
                )
            }
            .filter {
                if (includePendingUnsub) {
                    it.unsubscribeId > -1
                } else {
                    true
                }
            }
            .map { toSubscription(it) }
            .associateBy { it.topicFilter }
    }

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker {
        val tx = db.transaction(arrayOf(Broker, UserProperties), IDBTransactionMode.readwrite)
        val store = tx.objectStore(Broker)
        val propStore = tx.objectStore(UserProperties)
        val connections = PersistableSocketConnection.from(connectionOps)
        val persistableRequest = PersistableConnectionRequest.from(connectionRequest as ConnectionRequest)
        val countOp = request { store.count() }
        val broker = PersistableBroker(countOp, connections, persistableRequest)
        store.put(broker)
        for ((key, value) in connectionRequest.variableHeader.properties.userProperty) {
            propStore.put(PersistableUserProperty(countOp, 0, -1, key, value))
        }
        val willProps = connectionRequest.payload.willProperties?.userProperty
        if (!willProps.isNullOrEmpty()) {
            for ((key, value) in willProps) {
                propStore.put(PersistableUserProperty(countOp, 0, -2, key, value))
            }
        }
        commitTransaction(tx, "addBroker")
        return MqttBroker(countOp.unsafeCast<Int>(), connectionOps, connectionRequest)
    }

    private suspend fun <T> request(block: () -> IDBRequest<T>): T = withContext(dispatcher) {
        suspendCoroutine { cont ->
            val op = block()
            op.onsuccess = {
                cont.resume(op.result)
            }
            op.onerror = {
                cont.resumeWithException(op.error as Throwable)
            }
        }
    }

    private suspend fun commitTransaction(tx: IDBTransaction, logName: String) = withContext(dispatcher) {
        suspendCancellableCoroutine { cont ->
            tx.oncomplete = {
                cont.resume(Unit)
            }
            tx.onerror = {
                console.error("failed to commit tx $logName", tx)
                cont.resumeWithException(Exception(tx.error))
            }
            tx.onabort = {
                console.error("failed to commit tx because of abort $logName", tx)
                cont.resumeWithException(Exception(tx.error))
            }
            cont.invokeOnCancellation {
                if (!cont.isCompleted) {
                    tx.abort()
                }
            }
            tx.commit()
        }
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        console.log("all brokers v5")
        val tx = db.transaction(arrayOf(Broker, UserProperties), IDBTransactionMode.readonly)
        try {
            val brokerStore = tx.objectStore(Broker)
            val brokers = request { brokerStore.getAll() }
            if (brokers.isEmpty()) {
                return emptyList()
            }
            val propStore = tx.objectStore(UserProperties)
            val index = propStore.index(PropPacketIdIndex)
            val results = brokers.toList().map { persistableBroker ->
                val d = persistableBroker.asDynamic()
                val userProperties = request { index.getAll(arrayOf(d.id as Int, -1, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                val willUserProperties = request { index.getAll(arrayOf(d.id as Int, -2, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                MqttBroker(
                    d.id as Int,
                    (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
                    toConnectionRequest(d.connectionRequest, userProperties, willUserProperties)
                )
            }
            return results
        } finally {
            try {
                commitTransaction(tx, "allBrokers v5")
            } catch (e: DOMException) {
                // ignore. happens when it's an empty list
            }
        }
    }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? {
        val tx = db.transaction(arrayOf(Broker, UserProperties), IDBTransactionMode.readonly)
        try {
            val store = tx.objectStore(Broker)
            val result = request { store.get(arrayOf(identifier)) } ?: return null
            val propStore = tx.objectStore(UserProperties)
            val index = propStore.index(PropPacketIdIndex)

            console.log("brokers result $identifier v5", result)
            val d = result.asDynamic()
            val userProperties = request { index.getAll(arrayOf(d.id as Int, -1, 0)) }
                .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            val willUserProperties = request { index.getAll(arrayOf(d.id as Int, -2, 0)) }
                .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            return MqttBroker(
                d.id as Int,
                (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
                toConnectionRequest(d.connectionRequest, userProperties, willUserProperties)
            )
        } finally {
            commitTransaction(tx, "broker v5 $identifier")
        }
    }

    override suspend fun clearMessages(broker: MqttBroker) {
        val tx = db.transaction(arrayOf(PubMsg, PacketId), IDBTransactionMode.readwrite)
        val queued = tx.objectStore(PubMsg)
        val packet = tx.objectStore(PacketId)
        queued.delete(broker.identifier)
        packet.delete(broker.identifier)
        commitTransaction(tx, "clearMessages")
    }

    override suspend fun incomingPublish(broker: MqttBroker, packet: IPublishMessage, replyMessage: ControlPacket) {
        if (packet.qualityOfService != QualityOfService.EXACTLY_ONCE) {
            return
        }
        val p = replyMessage as PublishReceived
        val tx = db.transaction(arrayOf(QoS2Msg, UserProperties), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                replyMessage.packetIdentifier,
                replyMessage.controlPacketValue,
                0, // TODO: How this this correct?
                p.variable.reasonCode.byte.toInt(),
                p.variable.properties.reasonString
            )
        )
        val propStore = tx.objectStore(UserProperties)
        for ((key, value) in p.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, p.packetIdentifier, key, value))
        }
        commitTransaction(tx, "incomingPublish")
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val tx = db.transaction(
            arrayOf(PubMsg, UserProperties, QoS2Msg, SubMsg, UnsubMsg, Subscription),
            IDBTransactionMode.readonly
        )
        val propStore = tx.objectStore(UserProperties)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2Store = tx.objectStore(QoS2Msg)
        val subStore = tx.objectStore(SubMsg)
        val subscriptionStore = tx.objectStore(Subscription)
        val unsubStore = tx.objectStore(UnsubMsg)
        val pub = request { queuedMsgStore.index(BrokerIncomingIndex).getAll(arrayOf(broker.identifier, 0)) }
        val propIndex = propStore.index(PropPacketIdIndex)
        val pubs = pub.map { p ->
            val userProperties =
                request { propIndex.getAll(arrayOf(broker.identifier, p.asDynamic().packetId, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            toPub(p.unsafeCast<PersistablePublishMessage>(), userProperties).setDupFlagNewPubMessage()
        }
        val qos2Persistable = request { qos2Store.index(BrokerIndex).getAll(broker.identifier) }
        val subscribeRequests = request { subStore.index(BrokerIndex).getAll(broker.identifier) }
        val subIndex = subscriptionStore.index(AllSubIndex)
        val retrievedSubscribeRequests = if (subscribeRequests.size > 0) {
            subscribeRequests
                .map {
                    val d = it.asDynamic()
                    PersistableSubscribe(d.brokerId as Int, d.packetId as Int, d.reasonString as String?)
                }
                .map { persistableSubscribe ->
                    val subscriptions =
                        request { subIndex.getAll(arrayOf(broker.identifier, persistableSubscribe.packetId)) }
                            .map { toSubscription(it.unsafeCast<PersistableSubscription>()) }
                    val userProperties =
                        request { propIndex.getAll(arrayOf(broker.identifier, persistableSubscribe.packetId, 0)) }
                            .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                    SubscribeRequest(
                        SubscribeRequest.VariableHeader(
                            persistableSubscribe.packetId,
                            SubscribeRequest.VariableHeader.Properties(
                                persistableSubscribe.reasonString,
                                userProperties
                            )
                        ),
                        subscriptions.toSet()
                    )
                }
        } else {
            emptyList()
        }
        val unsubscribeRequests = request { unsubStore.index(BrokerIndex).getAll(broker.identifier) }
        val unsubIndex = subscriptionStore.index(UnsubIndex)
        val unsubs = unsubscribeRequests
            .map { unsubscribeRequestObject ->
                val packetId = unsubscribeRequestObject.asDynamic().packetId.unsafeCast<Int>()
                val topics =
                    request { unsubIndex.getAll(arrayOf(broker.identifier, packetId)) }
                        .map { it.asDynamic().topicFilter.toString() }
                val userProperties =
                    request { propIndex.getAll(arrayOf(broker.identifier, packetId, 0)) }
                        .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                UnsubscribeRequest(
                    UnsubscribeRequest.VariableHeader(
                        packetId,
                        UnsubscribeRequest.VariableHeader.Properties(userProperties)
                    ),
                    topics.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet()
                )
            }

        val qos2 = qos2Persistable.map { persistablePacket ->
            val dynamicIt = persistablePacket.asDynamic()
            val msg = PersistableQos2Message(
                dynamicIt.brokerId as Int,
                dynamicIt.packetId as Int,
                dynamicIt.type as Byte,
                dynamicIt.incoming as Int,
                dynamicIt.reasonCode as Int,
                dynamicIt.reasonString as String?
            )
            val packet = when (msg.type) {
                IPublishReceived.controlPacketValue -> {
                    val userProperties =
                        request { propIndex.getAll(arrayOf(broker.identifier, msg.packetId, 0)) }
                            .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                    PublishReceived(
                        PublishReceived.VariableHeader(
                            msg.packetId,
                            pubRelOrPubCompReasonCode(msg.reasonCode),
                            PublishReceived.VariableHeader.Properties(msg.reasonString, userProperties)
                        )
                    )
                }

                IPublishRelease.controlPacketValue -> {
                    val userProperties =
                        request { propIndex.getAll(arrayOf(broker.identifier, msg.packetId, 1)) }
                            .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                    PublishRelease(
                        PublishRelease.VariableHeader(
                            msg.packetId,
                            pubRelOrPubCompReasonCode(msg.reasonCode),
                            PublishRelease.VariableHeader.Properties(msg.reasonString, userProperties)
                        )
                    )
                }

                IPublishComplete.controlPacketValue -> {
                    val userProperties =
                        request { propIndex.getAll(arrayOf(broker.identifier, msg.packetId, 0)) }
                            .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
                    PublishComplete(
                        PublishComplete.VariableHeader(
                            msg.packetId,
                            pubRecvReasonCode(msg.reasonCode),
                            PublishComplete.VariableHeader.Properties(msg.reasonString, userProperties)
                        )
                    )
                }

                else -> {
                    error("IDB Persistence failed to get a valid qos 2 type")
                }
            }
            packet
        }
        commitTransaction(tx, "messagesToSendOnReconnect")
        return (pubs + retrievedSubscribeRequests + unsubs + qos2).sortedBy { it.packetIdentifier }
    }

    private fun pubRelOrPubCompReasonCode(code: Int): ReasonCode = when (code.toUByte()) {
        ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
        ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte -> ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
        else -> error("Invalid PublishRelease QOS Reason code $code")
    }

    private fun pubRecvReasonCode(code: Int): ReasonCode = when (code.toUByte()) {
        ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
        ReasonCode.NO_MATCHING_SUBSCRIBERS.byte -> ReasonCode.NO_MATCHING_SUBSCRIBERS
        ReasonCode.UNSPECIFIED_ERROR.byte -> ReasonCode.UNSPECIFIED_ERROR
        ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR.byte -> ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
        ReasonCode.NOT_AUTHORIZED.byte -> ReasonCode.NOT_AUTHORIZED
        ReasonCode.TOPIC_NAME_INVALID.byte -> ReasonCode.TOPIC_NAME_INVALID
        ReasonCode.PACKET_IDENTIFIER_IN_USE.byte -> ReasonCode.PACKET_IDENTIFIER_IN_USE
        ReasonCode.QUOTA_EXCEEDED.byte -> ReasonCode.QUOTA_EXCEEDED
        ReasonCode.PAYLOAD_FORMAT_INVALID.byte -> ReasonCode.PAYLOAD_FORMAT_INVALID
        else -> error("Invalid PublishReceived QOS Reason code $code")
    }

    override suspend fun onPubCompWritten(broker: MqttBroker, outPubComp: IPublishComplete) {
        val tx = db.transaction(arrayOf(QoS2Msg, UserProperties), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, outPubComp.packetIdentifier, 0))
        deleteUserProperties(tx, broker.identifier, outPubComp.packetIdentifier, 0)
        commitTransaction(tx, "onPubCompWritten")
    }

    override suspend fun removeBroker(identifier: Int) {
        val tx = db.transaction(
            arrayOf(Broker, PacketId, PubMsg, Subscription, QoS2Msg, SubMsg, UnsubMsg),
            IDBTransactionMode.readwrite
        )
        val packetIdStore = tx.objectStore(PacketId)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val subscriptionStore = tx.objectStore(Subscription)
        val brokerStore = tx.objectStore(Broker)
        val qos2Store = tx.objectStore(QoS2Msg)
        val subStore = tx.objectStore(SubMsg)
        val unsubStore = tx.objectStore(UnsubMsg)

        val key = IDBKeyRange.only(arrayOf(identifier))
        packetIdStore.delete(key)
        queuedMsgStore.delete(key)
        subscriptionStore.delete(key)
        brokerStore.delete(key)
        qos2Store.delete(key)
        subStore.delete(key)
        unsubStore.delete(key)
        commitTransaction(tx, "removeBroker")
    }

    override suspend fun writePubGetPacketId(broker: MqttBroker, pub: IPublishMessage): Int {
        val tx = db.transaction(arrayOf(PacketId, UserProperties, PubMsg), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val packetIdPub = pub.maybeCopyWithNewPacketIdentifier(newPacketId) as PublishMessage
        val persistablePub = PersistablePublishMessage(broker.identifier, false, packetIdPub)
        queuedMsgStore.put(persistablePub)
        val propStore = tx.objectStore(UserProperties)
        for ((key, value) in packetIdPub.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writePubGetPacketId")
        return newPacketId
    }

    override suspend fun getPubWithPacketId(broker: MqttBroker, packetId: Int): IPublishMessage? {
        val tx = db.transaction(arrayOf(PubMsg, UserProperties), IDBTransactionMode.readonly)
        try {
            val queuedMsgStore = tx.objectStore(PubMsg)
            if (request { queuedMsgStore.count(arrayOf(broker.identifier, packetId, 0)) } == 0) {
                return null
            }
            val p = request { queuedMsgStore.get(arrayOf(broker.identifier, packetId, 0)) }
            val propStore = tx.objectStore(UserProperties)
            val propIndex = propStore.index(PropPacketIdIndex)
            val userProperties =
                request { propIndex.getAll(arrayOf(broker.identifier, p.asDynamic().packetId, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            return toPub(p.unsafeCast<PersistablePublishMessage>(), userProperties)
        } finally {
            commitTransaction(tx, "getPubWithPacketId")
        }
    }

    private suspend fun getAndIncrementPacketId(tx: IDBTransaction, broker: MqttBroker): Int {
        val packetIdStore = tx.objectStore(PacketId)
        val brokerIdKey = IDBKeyRange.only(broker.identifier)
        val result = request { packetIdStore.get(brokerIdKey) }
        val value = if (result == undefined) {
            1
        } else {
            result.unsafeCast<Int>()
        }
        val next = value.toString().toInt() + 1
        request { packetIdStore.put(next, broker.identifier) }
        return value.toString().toInt()
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest
    ): ISubscribeRequest {
        val s = sub as SubscribeRequest
        val tx = db.transaction(arrayOf(PacketId, UserProperties, SubMsg, Subscription), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val subMsgStore = tx.objectStore(SubMsg)
        val newSub = sub.copyWithNewPacketIdentifier(newPacketId) as SubscribeRequest
        val persistableSubscribe =
            PersistableSubscribe(broker.identifier, newSub.packetIdentifier, s.variable.properties.reasonString)
        subMsgStore.add(persistableSubscribe)
        val subStore = tx.objectStore(Subscription)
        for (subscription in newSub.subscriptions) {
            subStore.add(PersistableSubscription(broker.identifier, newPacketId, subscription as Subscription))
        }
        val propStore = tx.objectStore(UserProperties)
        for ((key, value) in newSub.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writeSubUpdatePacketIdAndSimplifySubscriptions")
        return newSub
    }

    override suspend fun getSubWithPacketId(broker: MqttBroker, packetId: Int): ISubscribeRequest? {
        val tx = db.transaction(arrayOf(SubMsg, Subscription, UserProperties), IDBTransactionMode.readonly)
        try {
            val subStore = tx.objectStore(SubMsg)
            if (request { subStore.count(arrayOf(broker.identifier, packetId)) } == 0) {
                return null
            }
            val subscriptionStore = tx.objectStore(Subscription)
            val subIndex = subscriptionStore.index(AllSubIndex)
            val obj = request { subStore.get(arrayOf(broker.identifier, packetId)) }
            val persistableSubscribe = PersistableSubscribe(
                obj.asDynamic().brokerId as Int,
                obj.asDynamic().packetId as Int,
                obj.asDynamic().reasonString as String?
            )
            val subscriptions =
                request { subIndex.getAll(arrayOf(broker.identifier, persistableSubscribe.packetId)) }
                    .map { toSubscription(it.unsafeCast<PersistableSubscription>()) }
            val propStore = tx.objectStore(UserProperties)
            val propIndex = propStore.index(PropPacketIdIndex)

            val userProperties =
                request { propIndex.getAll(arrayOf(broker.identifier, persistableSubscribe.packetId, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            return SubscribeRequest(
                SubscribeRequest.VariableHeader(
                    persistableSubscribe.packetId,
                    SubscribeRequest.VariableHeader.Properties(
                        persistableSubscribe.reasonString,
                        userProperties
                    )
                ),
                subscriptions.toSet()
            )
        } finally {
            commitTransaction(tx, "writeSubUpdatePacketIdAndSimplifySubscriptions")
        }
    }

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        val tx = db.transaction(arrayOf(PacketId, UserProperties, UnsubMsg, Subscription), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val newUnsub = unsub.copyWithNewPacketIdentifier(newPacketId) as UnsubscribeRequest

        val persistableUnsub = PersistableUnsubscribe(broker.identifier, newUnsub)
        val unsubMsgStore = tx.objectStore(UnsubMsg)
        unsubMsgStore.put(persistableUnsub)
        val subscriptions = tx.objectStore(Subscription)
        val persistableSubscriptions = unsub.topics.map {
            val persistableSubscription = request { subscriptions.get(arrayOf(broker.identifier, it.toString())) }
            PersistableSubscription(
                persistableSubscription.asDynamic().brokerId as Int,
                persistableSubscription.asDynamic().topicFilter as String,
                persistableSubscription.asDynamic().subscribeId as Int,
                newPacketId,
                persistableSubscription.asDynamic().qos as Byte,
                persistableSubscription.asDynamic().noLocal as Boolean,
                persistableSubscription.asDynamic().retainAsPublished as Boolean,
                persistableSubscription.asDynamic().retainHandling as Int
            )
        }
        persistableSubscriptions.forEach {
            subscriptions.put(it)
        }
        val propStore = tx.objectStore(UserProperties)
        for ((key, value) in newUnsub.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writeUnsubGetPacketId")
        return newPacketId
    }

    override suspend fun getUnsubWithPacketId(broker: MqttBroker, packetId: Int): IUnsubscribeRequest? {
        val tx = db.transaction(arrayOf(UnsubMsg, Subscription, UserProperties), IDBTransactionMode.readonly)
        try {
            val unsubStore = tx.objectStore(UnsubMsg)
            if (request { unsubStore.count(arrayOf(broker.identifier, packetId)) } == 0) {
                return null
            }
            val subStore = tx.objectStore(Subscription)
            val unsubIndex = subStore.index(UnsubIndex)
            val topics =
                request { unsubIndex.getAll(arrayOf(broker.identifier, packetId)) }
                    .map { it.asDynamic().topicFilter.toString() }
            val propStore = tx.objectStore(UserProperties)
            val propIndex = propStore.index(PropPacketIdIndex)
            val userProperties =
                request { propIndex.getAll(arrayOf(broker.identifier, packetId, 0)) }
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            return UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(
                    packetId,
                    UnsubscribeRequest.VariableHeader.Properties(userProperties)
                ),
                topics.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet()
            )
        } finally {
            commitTransaction(tx, "getUnsubWithPacketId")
        }
    }

    override suspend fun isQueueClear(broker: MqttBroker, includeSubscriptions: Boolean): Boolean {
        val packets = messagesToSendOnReconnect(broker)
        if (packets.isNotEmpty()) {
            println(packets.joinToString())
            return false
        }
        return true
    }

    private suspend fun deleteUserProperties(
        tx: IDBTransaction,
        brokerId: Int,
        packetId: Int,
        incoming: Int
    ): IDBObjectStore {
        val propStore = tx.objectStore(UserProperties)
        val index = propStore.index(PropPacketIdIndex)
        val keysToDelete = request { index.getAllKeys(arrayOf(brokerId, packetId, incoming)) }
        for (key in keysToDelete) {
            propStore.delete(key)
        }
        return propStore
    }

    companion object {
        private const val Broker = "Broker"
        private const val BrokerIndex = "BrokerId"
        private const val BrokerIncomingIndex = "brokerIncomingIndex"
        private const val PacketId = "PacketId"
        private const val PubMsg = "PubMsg"
        private const val Subscription = "Subscription"
        private const val UserProperties = "UserProperties"
        private const val QoS2Msg = "QoS2Msg"
        private const val SubMsg = "SubMsg"
        private const val SubIndex = "sub"
        private const val AllSubIndex = "allSub"
        private const val UnsubMsg = "UnsubMsg"
        private const val UnsubIndex = "unsub"
        private const val PropPacketIdIndex = "prop"

        suspend fun idbPersistence(indexedDb: IDBFactory, name: String): IDBPersistence {
            val database = suspendCoroutine<IDBDatabase> { cont ->
                val openRequest = indexedDb.open(name, 1)
                openRequest.onsuccess = {
                    cont.resume(openRequest.result)
                }
                openRequest.onupgradeneeded = {
                    val db = openRequest.result
                    db.createObjectStore(Broker, js("{ keyPath: [\"id\"] }"))
                    db.createObjectStore(PacketId)
                    val pubStore =
                        db.createObjectStore(PubMsg, js("{ keyPath: [\"brokerId\", \"packetId\", \"incoming\"] }"))
                    val subscriptionStore =
                        db.createObjectStore(Subscription, js("{ keyPath: [\"brokerId\", \"topicFilter\"] }"))
                    val qos2Store =
                        db.createObjectStore(QoS2Msg, js("{ keyPath: [\"brokerId\", \"packetId\", \"incoming\"] }"))
                    val subStore = db.createObjectStore(SubMsg, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                    val unsubStore = db.createObjectStore(UnsubMsg, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                    val propStore = db.createObjectStore(UserProperties, js("{ keyPath: \"id\", autoIncrement:true }"))
                    pubStore.createIndex(BrokerIncomingIndex, arrayOf("brokerId", "incoming"))
                    qos2Store.createIndex(BrokerIndex, "brokerId")
                    subStore.createIndex(BrokerIndex, "brokerId")
                    unsubStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(SubIndex, arrayOf("brokerId", "topicFilter", "subscribeId"))
                    subscriptionStore.createIndex(AllSubIndex, arrayOf("brokerId", "subscribeId"))
                    subscriptionStore.createIndex(UnsubIndex, arrayOf("brokerId", "unsubscribeId"))
                    propStore.createIndex(PropPacketIdIndex, arrayOf("brokerId", "packetId", "incoming"))
                }
                openRequest.onerror = {
                    cont.resumeWithException(openRequest.error as Throwable)
                }
            }
            return IDBPersistence(database)
        }
    }
}
