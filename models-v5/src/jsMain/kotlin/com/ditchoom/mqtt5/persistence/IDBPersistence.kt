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
import js.core.ReadonlyArray
import kotlinx.coroutines.suspendCancellableCoroutine
import web.idb.IDBDatabase
import web.idb.IDBFactory
import web.idb.IDBKeyRange
import web.idb.IDBObjectStore
import web.idb.IDBRequest
import web.idb.IDBRequestReadyState
import web.idb.IDBTransaction
import web.idb.IDBTransactionMode
import web.idb.IDBValidKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class IDBPersistence(private val db: IDBDatabase) : Persistence {
    private val dispatcher = defaultDispatcher(0, "unused")

    override suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    ) {
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        queuedMsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 0))
        deleteUserProperties(tx, "ackPub", broker.identifier, packet.packetIdentifier, 0)
        commitTransaction(tx, "ackPub")
    }

    private suspend fun deleteUserProperties(
        tx: IDBTransaction,
        logName: String,
        brokerId: Int,
        packetId: Int,
        incoming: Int,
        postAction: (IDBObjectStore) -> IDBRequest<*>? = { null },
    ): IDBObjectStore {
        return deleteUserPropertiesPostAction(tx, logName, brokerId, packetId, incoming, postAction) {}
    }

    private suspend fun deleteUserPropertiesPostAction(
        tx: IDBTransaction,
        logName: String,
        brokerId: Int,
        packetId: Int,
        incoming: Int,
        postAction: (IDBObjectStore) -> IDBRequest<*>? = { null },
        postActionComplete: (IDBRequest<*>) -> Unit = {},
    ): IDBObjectStore {
        val userPropStore = tx.objectStore(USER_PROPERTIES)
        val request = getAllUserPropertyKeysRequest(userPropStore, brokerId, packetId, incoming)
        suspendCoroutine { cont ->
            request.onsuccess = {
                for (key in request.result) {
                    userPropStore.delete(key)
                }
                val postRequest = postAction(userPropStore)
                if (postRequest == null) {
                    cont.resume(Unit)
                } else {
                    if (postRequest.readyState == IDBRequestReadyState.done) {
                        postActionComplete(postRequest)
                        cont.resume(Unit)
                    } else {
                        postRequest.onsuccess = {
                            postActionComplete(postRequest)
                            cont.resume(Unit)
                        }
                        postRequest.onerror = {
                            cont.resumeWithException(
                                Exception(
                                    "Failed to process post request after delete user properties for transaction $logName",
                                    request.error,
                                ),
                            )
                        }
                    }
                }
            }
            request.onerror = {
                cont.resumeWithException(
                    Exception(
                        "Failed to delete user properties for transaction $logName",
                        request.error,
                    ),
                )
            }
        }
        return userPropStore
    }

    override suspend fun ackPubComplete(
        broker: MqttBroker,
        packet: IPublishComplete,
    ) {
        val tx = db.transaction(arrayOf(QOS2MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QOS2MSG)
        qos2MsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 1))
        deleteUserProperties(tx, "ackPubComplete", broker.identifier, packet.packetIdentifier, 1)
        commitTransaction(tx, "ackPubComplete")
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease,
    ) {
        val p = pubRel as PublishRelease
        val tx = db.transaction(arrayOf(PUB_MSG, QOS2MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        val qos2MsgStore = tx.objectStore(QOS2MSG)
        queuedMsgStore.delete(arrayOf(broker.identifier, incomingPubRecv.packetIdentifier, 0))
        deleteUserProperties(
            tx,
            "ackPubReceivedQueuePubRelease",
            broker.identifier,
            incomingPubRecv.packetIdentifier,
            0,
        ) { propStore ->
            qos2MsgStore.put(
                PersistableQos2Message(
                    broker.identifier,
                    p.packetIdentifier,
                    p.controlPacketValue,
                    1,
                    p.variable.reasonCode.byte.toInt(),
                    p.variable.properties.reasonString,
                ),
            )
            for ((key, value) in p.variable.properties.userProperty) {
                propStore.put(PersistableUserProperty(broker.identifier, 1, p.packetIdentifier, key, value))
            }
            null
        }
        commitTransaction(tx, "ackPubReceivedQueuePubRelease")
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete,
    ) {
        val p = outPubComp as PublishComplete
        val tx = db.transaction(arrayOf(QOS2MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QOS2MSG)
        deleteUserProperties(tx, "ackPubRelease", broker.identifier, incomingPubRel.packetIdentifier, 0) { propStore ->
            qos2MsgStore.put(
                PersistableQos2Message(
                    broker.identifier,
                    p.packetIdentifier,
                    p.controlPacketValue,
                    0,
                    p.variable.reasonCode.byte.toInt(),
                    p.variable.properties.reasonString,
                ),
            )
            for ((key, value) in p.variable.properties.userProperty) {
                propStore.put(PersistableUserProperty(broker.identifier, 0, p.packetIdentifier, key, value))
            }
            null
        }
        commitTransaction(tx, "ackPubRelease")
    }

    override suspend fun ackSub(
        broker: MqttBroker,
        subAck: ISubscribeAcknowledgement,
    ) {
        val tx = db.transaction(arrayOf(SUB_MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SUB_MSG)
        subMsgStore.delete(arrayOf(broker.identifier, subAck.packetIdentifier))
        deleteUserProperties(tx, "ackSub", broker.identifier, subAck.packetIdentifier, 0)
        commitTransaction(tx, "ackSub")
    }

    override suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    ) {
        val key = arrayOf(broker.identifier, unsubAck.packetIdentifier)
        val tx = db.transaction(arrayOf(UNSUB_MSG, SUBSCRIPTION, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val unsubMsgStore = tx.objectStore(UNSUB_MSG)
        unsubMsgStore.delete(arrayOf(broker.identifier, unsubAck.packetIdentifier))
        val subStore = tx.objectStore(SUBSCRIPTION)
        deleteUserPropertiesPostAction(tx, "ackUnsub", broker.identifier, unsubAck.packetIdentifier, 0, {
            val unsubIndex = subStore.index(UNSUB_INDEX)
            unsubIndex.getAll(key)
        }) {
            val request = (it.result as ReadonlyArray<dynamic>)
            for (unsubscription in request) {
                subStore.delete(arrayOf(broker.identifier, unsubscription.topicFilter))
            }
        }
        commitTransaction(tx, "ackUnsub")
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean,
    ): Map<Topic, ISubscription> {
        val tx = db.transaction(SUBSCRIPTION, IDBTransactionMode.readonly)
        val subStore = tx.objectStore(SUBSCRIPTION)
        val index = subStore.index(BROKER_INDEX)
        val subscriptionsRawRequest = index.getAll(broker.identifier)
        commitTransaction(tx, "activeSubscriptions")
        await(subscriptionsRawRequest)
        return subscriptionsRawRequest.result
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
                    d.retainHandling as Int,
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
        connectionRequest: IConnectionRequest,
    ): MqttBroker {
        val tx = db.transaction(arrayOf(BROKER, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val store = tx.objectStore(BROKER)
        val connections = PersistableSocketConnection.from(connectionOps)
        val persistableRequest = PersistableConnectionRequest.from(connectionRequest as ConnectionRequest)
        val storeCountRequest = store.count()
        val propStore = tx.objectStore(USER_PROPERTIES)
        val countOp =
            suspendCoroutine { cont ->
                storeCountRequest.onsuccess = {
                    val countOp = storeCountRequest.result.unsafeCast<Int>()
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
                    tx.commit()
                    cont.resume(countOp)
                }
            }
        return MqttBroker(countOp.unsafeCast<Int>(), connectionOps, connectionRequest)
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        val tx = db.transaction(arrayOf(BROKER, USER_PROPERTIES), IDBTransactionMode.readonly)
        val brokerStore = tx.objectStore(BROKER)
        val propStore = tx.objectStore(USER_PROPERTIES)
        val index = propStore.index(PROP_PACKET_ID_INDEX)
        val brokersRequest = brokerStore.getAll()
        val userPropertiesRequests = mutableMapOf<Int, IDBRequest<ReadonlyArray<*>>>()
        val userWillPropertiesRequests = mutableMapOf<Int, IDBRequest<ReadonlyArray<*>>>()
        val brokers =
            suspendCoroutine { cont ->
                brokersRequest.onsuccess = {
                    val brokers = brokersRequest.result
                    brokers.forEach { brokerObj ->
                        val d = brokerObj.asDynamic()
                        val id = d.id as Int
                        userPropertiesRequests[id] = index.getAll(arrayOf(id, -1, 0))
                        userWillPropertiesRequests[id] = index.getAll(arrayOf(id, -2, 0))
                    }
                    tx.commit()
                    cont.resume(brokers)
                }
            }
        awaitAll(userPropertiesRequests.values)
        awaitAll(userWillPropertiesRequests.values)

        val results =
            brokers.toList().map { persistableBroker ->
                val d = persistableBroker.asDynamic()
                val id = d.id as Int
                val userProperties =
                    userPropertiesRequests[id]?.result
                        ?.map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) } ?: emptyList()
                val willUserProperties =
                    userWillPropertiesRequests[id]?.result
                        ?.map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) } ?: emptyList()
                MqttBroker(
                    d.id as Int,
                    (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
                    toConnectionRequest(d.connectionRequest, userProperties, willUserProperties),
                )
            }
        return results
    }

    override suspend fun brokerWithId(identifier: Int): MqttBroker? {
        val tx = db.transaction(arrayOf(BROKER, USER_PROPERTIES), IDBTransactionMode.readonly)
        val store = tx.objectStore(BROKER)
        val propStore = tx.objectStore(USER_PROPERTIES)
        val index = propStore.index(PROP_PACKET_ID_INDEX)
        return try {
            val resultRequest = store[arrayOf(identifier)]
            val userPropertiesRequest = index.getAll(arrayOf(identifier, -1, 0))
            val willUserPropertiesRequest = index.getAll(arrayOf(identifier, -2, 0))
            commitTransaction(tx, "broker v5 $identifier")
            await(resultRequest)
            await(userPropertiesRequest)
            await(willUserPropertiesRequest)
            val d = resultRequest.result?.asDynamic() ?: return null
            val userProperties =
                userPropertiesRequest.result
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            val willUserProperties =
                willUserPropertiesRequest.result
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            MqttBroker(
                d.id as Int,
                (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
                toConnectionRequest(d.connectionRequest, userProperties, willUserProperties),
            )
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun clearMessages(broker: MqttBroker) {
        val tx = db.transaction(arrayOf(PUB_MSG, PACKET_ID), IDBTransactionMode.readwrite)
        val queued = tx.objectStore(PUB_MSG)
        val packet = tx.objectStore(PACKET_ID)
        queued.delete(broker.identifier)
        packet.delete(broker.identifier)
        commitTransaction(tx, "clearMessages")
    }

    override suspend fun incomingPublish(
        broker: MqttBroker,
        packet: IPublishMessage,
        replyMessage: ControlPacket,
    ) {
        if (packet.qualityOfService != QualityOfService.EXACTLY_ONCE) {
            return
        }
        val p = replyMessage as PublishReceived
        val tx = db.transaction(arrayOf(QOS2MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QOS2MSG)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                replyMessage.packetIdentifier,
                replyMessage.controlPacketValue,
                0,
                p.variable.reasonCode.byte.toInt(),
                p.variable.properties.reasonString,
            ),
        )
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in p.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, p.packetIdentifier, key, value))
        }
        commitTransaction(tx, "incomingPublish")
        isQueueClear(broker, true)
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val tx =
            db.transaction(
                arrayOf(PUB_MSG, USER_PROPERTIES, QOS2MSG, SUB_MSG, UNSUB_MSG, SUBSCRIPTION),
                IDBTransactionMode.readonly,
            )
        val propStore = tx.objectStore(USER_PROPERTIES)
        val allProps = propStore.index(BROKER_INDEX).getAll(broker.identifier)
        val pubRequest = tx.objectStore(PUB_MSG).index(BROKER_INCOMING_INDEX).getAll(arrayOf(broker.identifier, 0))
        val allSubByBrokerRequest = tx.objectStore(SUBSCRIPTION).index(BROKER_INDEX).getAll(broker.identifier)
        val subscribeRequests = tx.objectStore(SUB_MSG).index(BROKER_INDEX).getAll(broker.identifier)
        val unsubscribeRequest = tx.objectStore(UNSUB_MSG).index(BROKER_INDEX).getAll(broker.identifier)
        val qos2PersistableRequest = tx.objectStore(QOS2MSG).index(BROKER_INDEX).getAll(broker.identifier)

        commitTransaction(tx, "messagesToSendOnReconnect")

        await(allProps)
        val persistableUserProperties =
            allProps.result.map {
                val obj = it.asDynamic()
                PersistableUserProperty(
                    obj.brokerId as Int,
                    obj.incoming as Int,
                    obj.packetId as Int,
                    obj.key.toString(),
                    obj.value.toString(),
                )
            }
        await(pubRequest)
        val pubs =
            pubRequest.result.map { p ->
                val pub = p.unsafeCast<PersistablePublishMessage>()
                val userProperties =
                    persistableUserProperties
                        .filter { pub.brokerId == it.brokerId && pub.packetId == it.packetId && pub.incoming == it.incoming }
                        .map { Pair(it.key, it.value) }
                toPub(pub, userProperties).setDupFlagNewPubMessage()
            }
        await(allSubByBrokerRequest)
        val allSubscriptions =
            allSubByBrokerRequest.result.map {
                it.unsafeCast<PersistableSubscription>()
            }
        await(subscribeRequests)
        val retrievedSubscribeRequests =
            subscribeRequests.result.map { obj ->
                val d = obj.asDynamic()
                val sub = PersistableSubscribe(d.brokerId as Int, d.packetId as Int, d.reasonString as String?)
                val userProperties =
                    persistableUserProperties
                        .filter { sub.brokerId == it.brokerId && sub.packetId == it.packetId }
                        .map { Pair(it.key, it.value) }
                SubscribeRequest(
                    SubscribeRequest.VariableHeader(
                        sub.packetId,
                        SubscribeRequest.VariableHeader.Properties(
                            sub.reasonString,
                            userProperties,
                        ),
                    ),
                    allSubscriptions
                        .filter { it.brokerId == sub.brokerId && it.subscribeId == sub.packetId }
                        .map { toSubscription(it) }.toSet(),
                )
            }

        await(unsubscribeRequest)
        val unsubs =
            unsubscribeRequest.result
                .map { unsubscribeRequestObject ->
                    val obj = unsubscribeRequestObject.asDynamic()
                    val brokerId = obj.brokerId.unsafeCast<Int>()
                    val packetId = obj.packetId.unsafeCast<Int>()
                    val topics =
                        allSubscriptions
                            .filter { it.brokerId == brokerId && it.unsubscribeId == packetId }
                            .map { it.topicFilter }
                    val userProperties =
                        persistableUserProperties
                            .filter { brokerId == it.brokerId && packetId == it.packetId }
                            .map { Pair(it.key, it.value) }
                    UnsubscribeRequest(
                        UnsubscribeRequest.VariableHeader(
                            packetId,
                            UnsubscribeRequest.VariableHeader.Properties(userProperties),
                        ),
                        topics.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet(),
                    )
                }
        await(qos2PersistableRequest)
        val qos2 =
            qos2PersistableRequest.result.map { persistablePacket ->
                val dynamicIt = persistablePacket.asDynamic()
                val msg =
                    PersistableQos2Message(
                        dynamicIt.brokerId as Int,
                        dynamicIt.packetId as Int,
                        dynamicIt.type as Byte,
                        dynamicIt.incoming as Int,
                        dynamicIt.reasonCode as Int,
                        dynamicIt.reasonString as String?,
                    )
                val packet =
                    when (msg.type) {
                        IPublishReceived.CONTROL_PACKET_VALUE -> {
                            val userProperties =
                                persistableUserProperties
                                    .filter { broker.identifier == it.brokerId && msg.packetId == it.packetId && it.incoming == 0 }
                                    .map { Pair(it.key, it.value) }
                            PublishReceived(
                                PublishReceived.VariableHeader(
                                    msg.packetId,
                                    pubRelOrPubCompReasonCode(msg.reasonCode),
                                    PublishReceived.VariableHeader.Properties(msg.reasonString, userProperties),
                                ),
                            )
                        }

                        IPublishRelease.CONTROL_PACKET_VALUE -> {
                            val userProperties =
                                persistableUserProperties
                                    .filter { broker.identifier == it.brokerId && msg.packetId == it.packetId && it.incoming == 1 }
                                    .map { Pair(it.key, it.value) }
                            PublishRelease(
                                PublishRelease.VariableHeader(
                                    msg.packetId,
                                    pubRelOrPubCompReasonCode(msg.reasonCode),
                                    PublishRelease.VariableHeader.Properties(msg.reasonString, userProperties),
                                ),
                            )
                        }

                        IPublishComplete.CONTROL_PACKET_VALUE -> {
                            val userProperties =
                                persistableUserProperties
                                    .filter { broker.identifier == it.brokerId && msg.packetId == it.packetId && it.incoming == 0 }
                                    .map { Pair(it.key, it.value) }
                            PublishComplete(
                                PublishComplete.VariableHeader(
                                    msg.packetId,
                                    pubRecvReasonCode(msg.reasonCode),
                                    PublishComplete.VariableHeader.Properties(msg.reasonString, userProperties),
                                ),
                            )
                        }

                        else -> {
                            error("IDB Persistence failed to get a valid qos 2 type")
                        }
                    }
                packet
            }
        return (pubs + retrievedSubscribeRequests + unsubs + qos2).sortedBy { it.packetIdentifier }
    }

    private fun pubRelOrPubCompReasonCode(code: Int): ReasonCode =
        when (code.toUByte()) {
            ReasonCode.SUCCESS.byte -> ReasonCode.SUCCESS
            ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte -> ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
            else -> error("Invalid PublishRelease QOS Reason code $code")
        }

    private fun pubRecvReasonCode(code: Int): ReasonCode =
        when (code.toUByte()) {
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

    override suspend fun onPubCompWritten(
        broker: MqttBroker,
        outPubComp: IPublishComplete,
    ) {
        val tx = db.transaction(arrayOf(QOS2MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(QOS2MSG)
        queuedMsgStore.delete(arrayOf(broker.identifier, outPubComp.packetIdentifier, 0))
        deleteUserProperties(tx, "onPubCompWritten", broker.identifier, outPubComp.packetIdentifier, 0)
        commitTransaction(tx, "onPubCompWritten")
    }

    override suspend fun removeBroker(identifier: Int) {
        val tx =
            db.transaction(
                arrayOf(BROKER, PACKET_ID, PUB_MSG, SUBSCRIPTION, QOS2MSG, SUB_MSG, UNSUB_MSG),
                IDBTransactionMode.readwrite,
            )
        val packetIdStore = tx.objectStore(PACKET_ID)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        val subscriptionStore = tx.objectStore(SUBSCRIPTION)
        val brokerStore = tx.objectStore(BROKER)
        val qos2Store = tx.objectStore(QOS2MSG)
        val subStore = tx.objectStore(SUB_MSG)
        val unsubStore = tx.objectStore(UNSUB_MSG)

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

    override suspend fun writePubGetPacketId(
        broker: MqttBroker,
        pub: IPublishMessage,
    ): Int {
        val newPacketId = getAndIncrementPacketId(broker)
        val tx = db.transaction(arrayOf(PACKET_ID, USER_PROPERTIES, PUB_MSG), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        val packetIdPub = pub.maybeCopyWithNewPacketIdentifier(newPacketId) as PublishMessage
        val persistablePub = PersistablePublishMessage(broker.identifier, false, packetIdPub)
        queuedMsgStore.put(persistablePub)
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in packetIdPub.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writePubGetPacketId")
        return newPacketId
    }

    override suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IPublishMessage? {
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readonly)
        try {
            val queuedMsgStore = tx.objectStore(PUB_MSG)
            val pubRequest = queuedMsgStore[arrayOf(broker.identifier, packetId, 0)]
            val propStore = tx.objectStore(USER_PROPERTIES)
            val propIndex = propStore.index(PROP_PACKET_ID_INDEX)
            val userPropertyRequest = propIndex.getAll(arrayOf(broker.identifier, packetId, 0))
            commitTransaction(tx, "getPubWithPacketId")
            await(pubRequest)
            await(userPropertyRequest)
            val p = pubRequest.result ?: return null
            val userProperties =
                userPropertyRequest.result
                    .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
            return toPub(p.unsafeCast<PersistablePublishMessage>(), userProperties)
        } catch (t: Throwable) {
            return null
        }
    }

    private suspend fun getAndIncrementPacketId(broker: MqttBroker): Int {
        val tx = db.transaction(arrayOf(PACKET_ID), IDBTransactionMode.readwrite)
        val packetIdStore = tx.objectStore(PACKET_ID)
        val brokerIdKey = IDBKeyRange.only(broker.identifier)
        val packetIdCurrentRequest = packetIdStore[brokerIdKey]
        return suspendCoroutine { cont ->
            packetIdCurrentRequest.onsuccess = {
                val result = packetIdCurrentRequest.result
                val value =
                    if (result == undefined) {
                        1
                    } else {
                        result.unsafeCast<Int>()
                    }
                val next = value.toString().toInt() + 1
                packetIdStore.put(next, broker.identifier)
                tx.commit()
                cont.resume(value.toString().toInt())
            }
            packetIdCurrentRequest.onerror = {
                cont.resumeWithException(packetIdCurrentRequest.error!!)
            }
        }
    }

    override suspend fun writeSubUpdatePacketIdAndSimplifySubscriptions(
        broker: MqttBroker,
        sub: ISubscribeRequest,
    ): ISubscribeRequest {
        val newPacketId = getAndIncrementPacketId(broker)
        val s = sub as SubscribeRequest
        val tx = db.transaction(arrayOf(PACKET_ID, USER_PROPERTIES, SUB_MSG, SUBSCRIPTION), IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SUB_MSG)
        val newSub = sub.copyWithNewPacketIdentifier(newPacketId) as SubscribeRequest
        val persistableSubscribe =
            PersistableSubscribe(broker.identifier, newSub.packetIdentifier, s.variable.properties.reasonString)
        subMsgStore.add(persistableSubscribe)
        val subStore = tx.objectStore(SUBSCRIPTION)
        for (subscription in newSub.subscriptions) {
            subStore.add(PersistableSubscription(broker.identifier, newPacketId, subscription as Subscription))
        }
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in newSub.variable.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writeSubUpdatePacketIdAndSimplifySubscriptions")
        return newSub
    }

    override suspend fun getSubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): ISubscribeRequest? {
        val tx = db.transaction(arrayOf(SUB_MSG, SUBSCRIPTION, USER_PROPERTIES), IDBTransactionMode.readonly)
        val subStore = tx.objectStore(SUB_MSG)
        val subscriptionStore = tx.objectStore(SUBSCRIPTION)
        val subIndex = subscriptionStore.index(ALL_SUB_INDEx)
        val objRequest = subStore[arrayOf(broker.identifier, packetId)]
        val subscriptionsRequest = subIndex.getAll(arrayOf(broker.identifier, packetId))
        val propStore = tx.objectStore(USER_PROPERTIES)
        val propIndex = propStore.index(PROP_PACKET_ID_INDEX)
        val userPropertiesRequest = propIndex.getAll(arrayOf(broker.identifier, packetId, 0))
        commitTransaction(tx, "writeSubUpdatePacketIdAndSimplifySubscriptions")
        awaitAll(objRequest, subscriptionsRequest, userPropertiesRequest)
        val obj = objRequest.result ?: return null
        val persistableSubscribe =
            PersistableSubscribe(
                obj.asDynamic().brokerId as Int,
                obj.asDynamic().packetId as Int,
                obj.asDynamic().reasonString as String?,
            )
        val subscriptions =
            subscriptionsRequest.result
                .map { toSubscription(it.unsafeCast<PersistableSubscription>()) }

        val userProperties =
            userPropertiesRequest.result
                .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
        return SubscribeRequest(
            SubscribeRequest.VariableHeader(
                persistableSubscribe.packetId,
                SubscribeRequest.VariableHeader.Properties(
                    persistableSubscribe.reasonString,
                    userProperties,
                ),
            ),
            subscriptions.toSet(),
        )
    }

    override suspend fun writeUnsubGetPacketId(
        broker: MqttBroker,
        unsub: IUnsubscribeRequest,
    ): Int {
        val newPacketId = getAndIncrementPacketId(broker)
        suspendCoroutine { cont ->
            val tx =
                db.transaction(arrayOf(PACKET_ID, USER_PROPERTIES, UNSUB_MSG, SUBSCRIPTION), IDBTransactionMode.readwrite)
            val newUnsub = unsub.copyWithNewPacketIdentifier(newPacketId) as UnsubscribeRequest

            val persistableUnsub = PersistableUnsubscribe(broker.identifier, newUnsub)
            val unsubMsgStore = tx.objectStore(UNSUB_MSG)
            unsubMsgStore.put(persistableUnsub)
            val subscriptions = tx.objectStore(SUBSCRIPTION)
            val allTopics = HashSet(unsub.topics)

            val propStore = tx.objectStore(USER_PROPERTIES)
            for ((key, value) in newUnsub.variable.properties.userProperty) {
                propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
            }
            unsub.topics.map { topic ->
                val request = subscriptions[arrayOf(broker.identifier, topic.toString())]
                request.onsuccess = {
                    val persistableSubscription = request.result
                    val p =
                        PersistableSubscription(
                            persistableSubscription.asDynamic().brokerId as Int,
                            persistableSubscription.asDynamic().topicFilter as String,
                            persistableSubscription.asDynamic().subscribeId as Int,
                            newPacketId,
                            persistableSubscription.asDynamic().qos as Byte,
                            persistableSubscription.asDynamic().noLocal as Boolean,
                            persistableSubscription.asDynamic().retainAsPublished as Boolean,
                            persistableSubscription.asDynamic().retainHandling as Int,
                        )
                    val r = subscriptions.put(p)
                    r.onsuccess = {
                        allTopics -= topic
                        if (allTopics.isEmpty()) {
                            tx.commit()
                            cont.resume(Unit)
                        }
                    }
                    r.onerror = {
                        cont.resumeWithException(
                            Exception(
                                "Failed to update subscription object for $topic",
                                request.error,
                            ),
                        )
                    }
                }
                request.onerror = {
                    cont.resumeWithException(Exception("Failed to request subscription for $topic", request.error))
                }
            }
        }
        return newPacketId
    }

    override suspend fun getUnsubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): IUnsubscribeRequest? {
        val tx = db.transaction(arrayOf(UNSUB_MSG, SUBSCRIPTION, USER_PROPERTIES), IDBTransactionMode.readonly)
        val unsubCountRequest = tx.objectStore(UNSUB_MSG).count(arrayOf(broker.identifier, packetId))
        val topicsRequest = tx.objectStore(SUBSCRIPTION).index(UNSUB_INDEX).getAll(arrayOf(broker.identifier, packetId))
        val userPropertiesRequest =
            tx.objectStore(USER_PROPERTIES).index(PROP_PACKET_ID_INDEX).getAll(arrayOf(broker.identifier, packetId, 0))
        commitTransaction(tx, "getUnsubWithPacketId")
        await(unsubCountRequest)
        if (unsubCountRequest.result == 0) {
            return null
        }
        awaitAll(topicsRequest, userPropertiesRequest)
        if (topicsRequest.result.isEmpty()) {
            return null
        }
        val topics =
            topicsRequest.result
                .map { it.asDynamic().topicFilter.toString() }
        val userProperties =
            userPropertiesRequest.result
                .map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) }
        return UnsubscribeRequest(
            UnsubscribeRequest.VariableHeader(
                packetId,
                UnsubscribeRequest.VariableHeader.Properties(userProperties),
            ),
            topics.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet(),
        )
    }

    override suspend fun isQueueClear(
        broker: MqttBroker,
        includeSubscriptions: Boolean,
    ): Boolean {
        val packets = messagesToSendOnReconnect(broker)
        if (packets.isNotEmpty()) {
            println(packets.joinToString())
            return false
        }
        return true
    }

    private fun getAllUserPropertyKeysRequest(
        propStore: IDBObjectStore,
        brokerId: Int,
        packetId: Int,
        incoming: Int,
    ): IDBRequest<ReadonlyArray<IDBValidKey>> {
        val index = propStore.index(PROP_PACKET_ID_INDEX)
        return index.getAllKeys(arrayOf(brokerId, packetId, incoming))
    }

    private suspend fun awaitAll(vararg requests: IDBRequest<*>) {
        requests.forEach { await(it) }
    }

    private suspend fun awaitAll(requests: Collection<IDBRequest<*>>) {
        requests.forEach { await(it) }
    }

    private suspend fun await(request: IDBRequest<*>) {
        if (request.readyState == IDBRequestReadyState.done) {
            return
        }
        suspendCoroutine<Any?> { cont ->
            request.onsuccess = {
                cont.resume(request.result)
            }
            request.onerror = {
                console.error("request error, cast throwable", it)
                cont.resumeWithException(request.error!!)
            }
        }
    }

    private suspend fun commitTransaction(
        tx: IDBTransaction,
        logName: String,
        customBlock: () -> Unit = {},
    ) {
        return suspendCancellableCoroutine { cont ->
            tx.oncomplete = {
                customBlock()
                cont.resume(Unit)
            }
            tx.onerror = {
                cont.resumeWithException(Exception("error committing tx $logName", tx.error))
            }
            tx.onabort = {
                cont.resumeWithException(Exception("abort committing tx $logName", tx.error))
            }
            cont.invokeOnCancellation {
                if (!cont.isCompleted) {
                    tx.abort()
                }
            }
            try {
                tx.commit()
            } catch (e: Throwable) {
                console.error("Failed to commit $logName", e)
            }
        }
    }

    companion object {
        private const val BROKER = "Broker"
        private const val BROKER_INDEX = "BrokerId"
        private const val BROKER_INCOMING_INDEX = "brokerIncomingIndex"
        private const val PACKET_ID = "PacketId"
        private const val PUB_MSG = "PubMsg"
        private const val SUBSCRIPTION = "Subscription"
        private const val USER_PROPERTIES = "UserProperties"
        private const val QOS2MSG = "QoS2Msg"
        private const val SUB_MSG = "SubMsg"
        private const val SUB_INDEX = "sub"
        private const val ALL_SUB_INDEx = "allSub"
        private const val UNSUB_MSG = "UnsubMsg"
        private const val UNSUB_INDEX = "unsub"
        private const val PROP_PACKET_ID_INDEX = "prop"

        suspend fun idbPersistence(
            indexedDb: IDBFactory,
            name: String,
        ): IDBPersistence {
            val database =
                suspendCoroutine<IDBDatabase> { cont ->
                    val openRequest = indexedDb.open(name, 1)
                    openRequest.onsuccess = {
                        cont.resume(openRequest.result)
                    }
                    openRequest.onupgradeneeded = {
                        val db = openRequest.result
                        db.createObjectStore(BROKER, js("{ keyPath: [\"id\"] }"))
                        db.createObjectStore(PACKET_ID)
                        val pubStore =
                            db.createObjectStore(PUB_MSG, js("{ keyPath: [\"brokerId\", \"packetId\", \"incoming\"] }"))
                        val subscriptionStore =
                            db.createObjectStore(SUBSCRIPTION, js("{ keyPath: [\"brokerId\", \"topicFilter\"] }"))
                        val qos2Store =
                            db.createObjectStore(QOS2MSG, js("{ keyPath: [\"brokerId\", \"packetId\", \"incoming\"] }"))
                        val subStore = db.createObjectStore(SUB_MSG, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                        val unsubStore = db.createObjectStore(UNSUB_MSG, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                        val propStore = db.createObjectStore(USER_PROPERTIES, js("{ keyPath: \"id\", autoIncrement:true }"))
                        pubStore.createIndex(BROKER_INCOMING_INDEX, arrayOf("brokerId", "incoming"))
                        qos2Store.createIndex(BROKER_INDEX, "brokerId")
                        subStore.createIndex(BROKER_INDEX, "brokerId")
                        unsubStore.createIndex(BROKER_INDEX, "brokerId")
                        subscriptionStore.createIndex(BROKER_INDEX, "brokerId")
                        subscriptionStore.createIndex(SUB_INDEX, arrayOf("brokerId", "topicFilter", "subscribeId"))
                        subscriptionStore.createIndex(ALL_SUB_INDEx, arrayOf("brokerId", "subscribeId"))
                        subscriptionStore.createIndex(UNSUB_INDEX, arrayOf("brokerId", "unsubscribeId"))
                        propStore.createIndex(PROP_PACKET_ID_INDEX, arrayOf("brokerId", "packetId", "incoming"))
                        propStore.createIndex(BROKER_INDEX, "brokerId")
                    }
                    openRequest.onerror = {
                        cont.resumeWithException(openRequest.error as Throwable)
                    }
                }
            return IDBPersistence(database)
        }
    }
}
