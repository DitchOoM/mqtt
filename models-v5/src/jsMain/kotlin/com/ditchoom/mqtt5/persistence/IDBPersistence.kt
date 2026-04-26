package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.Persistence
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
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.PublishComplete
import com.ditchoom.mqtt5.controlpacket.PublishMessageV5
import com.ditchoom.mqtt5.controlpacket.PublishReceived
import com.ditchoom.mqtt5.controlpacket.PublishRelease
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest
import com.ditchoom.mqtt5.controlpacket.Subscription
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest
import com.ditchoom.mqtt5.controlpacket.reasonStringValue
import com.ditchoom.mqtt5.controlpacket.userProperties
import js.array.ReadonlyArray
import kotlinx.coroutines.suspendCancellableCoroutine
import web.events.EventHandler
import web.idb.IDBDatabase
import web.idb.IDBFactory
import web.idb.IDBKeyRange
import web.idb.IDBObjectStore
import web.idb.IDBRequest
import web.idb.IDBRequestReadyState
import web.idb.IDBTransaction
import web.idb.IDBTransactionMode
import web.idb.IDBValidKey
import web.idb.done
import web.idb.readonly
import web.idb.readwrite
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class IDBPersistence(
    private val db: IDBDatabase,
) : Persistence {
    private val dispatcher = defaultDispatcher(0, "unused")

    override suspend fun ackPub(
        broker: MqttBroker,
        packet: IPublishAcknowledgment,
    ) {
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        queuedMsgStore.delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(packet.packetIdentifier),
                    IDBValidKey(0),
                ),
            ),
        )
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
    ): IDBObjectStore = deleteUserPropertiesPostAction(tx, logName, brokerId, packetId, incoming, postAction) {}

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
            request.onsuccess =
                EventHandler {
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
                            postRequest.onsuccess =
                                EventHandler {
                                    postActionComplete(postRequest)
                                    cont.resume(Unit)
                                }
                            postRequest.onerror =
                                EventHandler {
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
            request.onerror =
                EventHandler {
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
        qos2MsgStore.delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(packet.packetIdentifier),
                    IDBValidKey(1),
                ),
            ),
        )
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
        queuedMsgStore.delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(incomingPubRecv.packetIdentifier),
                    IDBValidKey(0),
                ),
            ),
        )
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
                    (p.reasonCode ?: ReasonCode.SUCCESS.byte).toInt(),
                    p.properties.reasonStringValue(),
                ),
            )
            for ((key, value) in p.properties.userProperties()) {
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
                    (p.reasonCode ?: ReasonCode.SUCCESS.byte).toInt(),
                    p.properties.reasonStringValue(),
                ),
            )
            for ((key, value) in p.properties.userProperties()) {
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
        subMsgStore.delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(subAck.packetIdentifier),
                ),
            ),
        )
        deleteUserProperties(tx, "ackSub", broker.identifier, subAck.packetIdentifier, 0)
        commitTransaction(tx, "ackSub")
    }

    override suspend fun ackUnsub(
        broker: MqttBroker,
        unsubAck: IUnsubscribeAcknowledgment,
    ) {
        val key =
            IDBValidKey(
                arrayOf(IDBValidKey(broker.identifier), IDBValidKey(unsubAck.packetIdentifier)),
            )
        val tx = db.transaction(arrayOf(UNSUB_MSG, SUBSCRIPTION, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val unsubMsgStore = tx.objectStore(UNSUB_MSG)
        unsubMsgStore.delete(
            IDBValidKey(
                arrayOf(IDBValidKey(broker.identifier), IDBValidKey(unsubAck.packetIdentifier)),
            ),
        )
        val subStore = tx.objectStore(SUBSCRIPTION)
        deleteUserPropertiesPostAction(
            tx,
            "ackUnsub",
            broker.identifier,
            unsubAck.packetIdentifier,
            0,
            {
                val unsubIndex = subStore.index(UNSUB_INDEX)
                unsubIndex.getAll(key)
            },
        ) {
            val request = (it.result as ReadonlyArray<dynamic>)
            for (unsubscription in request) {
                subStore.delete(
                    IDBValidKey(
                        arrayOf(
                            IDBValidKey(broker.identifier),
                            IDBValidKey(unsubscription.topicFilter.unsafeCast<String>()),
                        ),
                    ),
                )
            }
        }
        commitTransaction(tx, "ackUnsub")
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean,
    ): Map<TopicFilter, ISubscription> {
        val tx = db.transaction(SUBSCRIPTION, IDBTransactionMode.readonly)
        val subStore = tx.objectStore(SUBSCRIPTION)
        val index = subStore.index(BROKER_INDEX)
        val subscriptionsRawRequest = index.getAll(IDBValidKey(broker.identifier))
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
            }.filter {
                if (includePendingUnsub) {
                    it.unsubscribeId > -1
                } else {
                    true
                }
            }.map { toSubscription(it) }
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
                storeCountRequest.onsuccess =
                    EventHandler {
                        val countOp = storeCountRequest.result.unsafeCast<Int>()
                        val broker = PersistableBroker(countOp, connections, persistableRequest)
                        store.put(broker)
                        for ((key, value) in connectionRequest.typedProperties.userProperty) {
                            propStore.put(PersistableUserProperty(countOp, 0, -1, key, value))
                        }
                        val willProps = connectionRequest.typedWillProperties?.userProperty
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
                brokersRequest.onsuccess =
                    EventHandler {
                        val brokers = brokersRequest.result
                        brokers.forEach { brokerObj ->
                            val d = brokerObj.asDynamic()
                            val id = d.id as Int
                            userPropertiesRequests[id] =
                                index.getAll(
                                    IDBValidKey(
                                        arrayOf(IDBValidKey(id), IDBValidKey(-1), IDBValidKey(0)),
                                    ),
                                )
                            userWillPropertiesRequests[id] =
                                index.getAll(
                                    IDBValidKey(
                                        arrayOf(IDBValidKey(id), IDBValidKey(-2), IDBValidKey(0)),
                                    ),
                                )
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
                    userPropertiesRequests[id]
                        ?.result
                        ?.map { Pair(it.asDynamic().key as String, it.asDynamic().value as String) } ?: emptyList()
                val willUserProperties =
                    userWillPropertiesRequests[id]
                        ?.result
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
            val resultRequest = store.get(IDBValidKey(arrayOf(IDBValidKey(identifier))))
            val userPropertiesRequest =
                index.getAll(
                    IDBValidKey(
                        arrayOf(IDBValidKey(identifier), IDBValidKey(-1), IDBValidKey(0)),
                    ),
                )
            val willUserPropertiesRequest =
                index.getAll(
                    IDBValidKey(
                        arrayOf(IDBValidKey(identifier), IDBValidKey(-2), IDBValidKey(0)),
                    ),
                )
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
        queued.delete(IDBValidKey(broker.identifier))
        packet.delete(IDBValidKey(broker.identifier))
        commitTransaction(tx, "clearMessages")
    }

    override suspend fun persistIncomingPublish(
        broker: MqttBroker,
        packet: PublishMessage,
    ) {
        if (packet.qualityOfService == QualityOfService.AT_MOST_ONCE) return
        val p = packet as PublishMessageV5
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        val pubStore = tx.objectStore(PUB_MSG)
        pubStore.put(PersistablePublishMessage(broker.identifier, true, p))
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in p.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 1, p.packetIdentifier, key, value))
        }
        commitTransaction(tx, "persistIncomingPublish")
    }

    override suspend fun incomingHandlerComplete(
        broker: MqttBroker,
        packetId: Int,
    ) {
        val readTx = db.transaction(PUB_MSG, IDBTransactionMode.readonly)
        val key =
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(packetId),
                    IDBValidKey(1),
                ),
            )
        val getReq = readTx.objectStore(PUB_MSG).get(key)
        commitTransaction(readTx, "incomingHandlerComplete.read")
        await(getReq)
        val existing = getReq.result?.unsafeCast<PersistablePublishMessage>() ?: return
        val writeTx = db.transaction(PUB_MSG, IDBTransactionMode.readwrite)
        val writeStore = writeTx.objectStore(PUB_MSG)
        when (existing.qos.toQos()) {
            QualityOfService.AT_LEAST_ONCE -> writeStore.delete(key)
            QualityOfService.EXACTLY_ONCE -> {
                val updated =
                    PersistablePublishMessage(
                        existing.brokerId,
                        existing.incoming,
                        existing.dup,
                        existing.qos,
                        existing.retain,
                        existing.topicName,
                        existing.packetId,
                        existing.payloadFormatIndicator,
                        existing.messageExpiryInterval,
                        existing.topicAlias,
                        existing.responseTopic,
                        existing.correlationData,
                        existing.subscriptionIdentifier,
                        existing.contentType,
                        existing.payload,
                        Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT,
                    )
                writeStore.put(updated)
            }
            QualityOfService.AT_MOST_ONCE -> Unit
        }
        commitTransaction(writeTx, "incomingHandlerComplete.write")
    }

    override suspend fun incomingMessagesToRedispatch(
        broker: MqttBroker,
    ): Collection<com.ditchoom.mqtt.IncomingPublishRecord> {
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readonly)
        val propStore = tx.objectStore(USER_PROPERTIES)
        val allPropsReq = propStore.index(BROKER_INDEX).getAll(IDBValidKey(broker.identifier))
        val pubReq =
            tx
                .objectStore(PUB_MSG)
                .index(BROKER_INCOMING_INDEX)
                .getAll(IDBValidKey(arrayOf(IDBValidKey(broker.identifier), IDBValidKey(1))))
        commitTransaction(tx, "incomingMessagesToRedispatch")
        await(pubReq)
        await(allPropsReq)
        val props =
            allPropsReq.result
                .map { it.unsafeCast<PersistableUserProperty>() }
                .filter { it.incoming == 1 }
        return pubReq.result.map {
            val persistable = it.unsafeCast<PersistablePublishMessage>()
            val pubProps =
                props
                    .filter { p -> p.packetId == persistable.packetId }
                    .map { p -> Pair(p.key, p.value) }
            val pub = toPub(persistable, pubProps)
            val state = persistable.asDynamic().state as? Int ?: 0
            com.ditchoom.mqtt.IncomingPublishRecord(pub, state)
        }
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val tx =
            db.transaction(
                arrayOf(PUB_MSG, USER_PROPERTIES, QOS2MSG, SUB_MSG, UNSUB_MSG, SUBSCRIPTION),
                IDBTransactionMode.readonly,
            )
        val propStore = tx.objectStore(USER_PROPERTIES)
        val allProps = propStore.index(BROKER_INDEX).getAll(IDBValidKey(broker.identifier))
        val pubRequest =
            tx.objectStore(PUB_MSG).index(BROKER_INCOMING_INDEX).getAll(
                IDBValidKey(arrayOf(IDBValidKey(broker.identifier), IDBValidKey(0))),
            )
        val allSubByBrokerRequest =
            tx
                .objectStore(SUBSCRIPTION)
                .index(BROKER_INDEX)
                .getAll(IDBValidKey(broker.identifier))
        val subscribeRequests =
            tx
                .objectStore(SUB_MSG)
                .index(BROKER_INDEX)
                .getAll(IDBValidKey(broker.identifier))
        val unsubscribeRequest =
            tx
                .objectStore(UNSUB_MSG)
                .index(BROKER_INDEX)
                .getAll(IDBValidKey(broker.identifier))
        val qos2PersistableRequest =
            tx
                .objectStore(QOS2MSG)
                .index(BROKER_INDEX)
                .getAll(IDBValidKey(broker.identifier))

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
                    packetIdentifier = sub.packetId.toUShort(),
                    subscriptions =
                        allSubscriptions
                            .filter { it.brokerId == sub.brokerId && it.subscribeId == sub.packetId }
                            .map { toSubscription(it) }
                            .toSet(),
                    reasonString = sub.reasonString,
                    userProperty = userProperties,
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
                        packetIdentifier = packetId.toUShort(),
                        topics = topics.map { TopicFilter.fromOrThrow(it) }.toSet(),
                        userProperty = userProperties,
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
                                packetIdentifier = msg.packetId,
                                reasonCode = pubRelOrPubCompReasonCode(msg.reasonCode),
                                reasonString = msg.reasonString,
                                userProperty = userProperties,
                            )
                        }

                        IPublishRelease.CONTROL_PACKET_VALUE -> {
                            val userProperties =
                                persistableUserProperties
                                    .filter { broker.identifier == it.brokerId && msg.packetId == it.packetId && it.incoming == 1 }
                                    .map { Pair(it.key, it.value) }
                            PublishRelease(
                                packetIdentifier = msg.packetId,
                                reasonCode = pubRelOrPubCompReasonCode(msg.reasonCode),
                                reasonString = msg.reasonString,
                                userProperty = userProperties,
                            )
                        }

                        IPublishComplete.CONTROL_PACKET_VALUE -> {
                            val userProperties =
                                persistableUserProperties
                                    .filter { broker.identifier == it.brokerId && msg.packetId == it.packetId && it.incoming == 0 }
                                    .map { Pair(it.key, it.value) }
                            PublishComplete(
                                packetIdentifier = msg.packetId,
                                reasonCode = pubRecvReasonCode(msg.reasonCode),
                                reasonString = msg.reasonString,
                                userProperty = userProperties,
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
        val tx = db.transaction(arrayOf(QOS2MSG, PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readwrite)
        tx.objectStore(QOS2MSG).delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(outPubComp.packetIdentifier),
                    IDBValidKey(0),
                ),
            ),
        )
        // new incoming QoS 2 row lives on PUB_MSG with incoming=1
        tx.objectStore(PUB_MSG).delete(
            IDBValidKey(
                arrayOf(
                    IDBValidKey(broker.identifier),
                    IDBValidKey(outPubComp.packetIdentifier),
                    IDBValidKey(1),
                ),
            ),
        )
        deleteUserProperties(tx, "onPubCompWritten", broker.identifier, outPubComp.packetIdentifier, 0)
        deleteUserProperties(tx, "onPubCompWritten", broker.identifier, outPubComp.packetIdentifier, 1)
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
        pub: PublishMessage,
    ): Int {
        val newPacketId = getAndIncrementPacketId(broker)
        val tx = db.transaction(arrayOf(PACKET_ID, USER_PROPERTIES, PUB_MSG), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PUB_MSG)
        val packetIdPub = pub.maybeCopyWithNewPacketIdentifier(newPacketId) as PublishMessageV5
        val persistablePub = PersistablePublishMessage(broker.identifier, false, packetIdPub)
        queuedMsgStore.put(persistablePub)
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in packetIdPub.properties.userProperty) {
            propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
        }
        commitTransaction(tx, "writePubGetPacketId")
        return newPacketId
    }

    override suspend fun getPubWithPacketId(
        broker: MqttBroker,
        packetId: Int,
    ): PublishMessage? {
        val tx = db.transaction(arrayOf(PUB_MSG, USER_PROPERTIES), IDBTransactionMode.readonly)
        try {
            val queuedMsgStore = tx.objectStore(PUB_MSG)
            val pubRequest =
                queuedMsgStore.get(
                    IDBValidKey(
                        arrayOf(
                            IDBValidKey(broker.identifier),
                            IDBValidKey(packetId),
                            IDBValidKey(0),
                        ),
                    ),
                )
            val propStore = tx.objectStore(USER_PROPERTIES)
            val propIndex = propStore.index(PROP_PACKET_ID_INDEX)
            val userPropertyRequest =
                propIndex.getAll(
                    IDBValidKey(
                        arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId), IDBValidKey(0)),
                    ),
                )
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
        val packetIdCurrentRequest = packetIdStore.get(brokerIdKey)
        return suspendCoroutine { cont ->
            packetIdCurrentRequest.onsuccess =
                EventHandler {
                    val result = packetIdCurrentRequest.result
                    val value =
                        if (result == undefined) {
                            1
                        } else {
                            result.unsafeCast<Int>()
                        }
                    val next = value.toString().toInt() + 1
                    packetIdStore.put(next, IDBValidKey(broker.identifier))
                    tx.commit()
                    cont.resume(value.toString().toInt())
                }
            packetIdCurrentRequest.onerror =
                EventHandler {
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
            PersistableSubscribe(broker.identifier, newSub.packetIdentifier, s.properties.reasonStringValue())
        subMsgStore.add(persistableSubscribe)
        val subStore = tx.objectStore(SUBSCRIPTION)
        for (subscription in newSub.subscriptions) {
            subStore.add(PersistableSubscription(broker.identifier, newPacketId, subscription as Subscription))
        }
        val propStore = tx.objectStore(USER_PROPERTIES)
        for ((key, value) in newSub.properties.userProperties()) {
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
        val subIndex = subscriptionStore.index(ALL_SUB_INDEX)
        val objRequest =
            subStore.get(
                IDBValidKey(
                    arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId)),
                ),
            )
        val subscriptionsRequest =
            subIndex.getAll(
                IDBValidKey(arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId))),
            )
        val propStore = tx.objectStore(USER_PROPERTIES)
        val propIndex = propStore.index(PROP_PACKET_ID_INDEX)
        val userPropertiesRequest =
            propIndex.getAll(
                IDBValidKey(
                    arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId), IDBValidKey(0)),
                ),
            )
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
            packetIdentifier = persistableSubscribe.packetId.toUShort(),
            subscriptions = subscriptions.toSet(),
            reasonString = persistableSubscribe.reasonString,
            userProperty = userProperties,
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
            for ((key, value) in newUnsub.properties.userProperties()) {
                propStore.put(PersistableUserProperty(broker.identifier, 0, newPacketId, key, value))
            }
            unsub.topics.map { topic ->
                val request =
                    subscriptions.get(
                        IDBValidKey(
                            arrayOf(IDBValidKey(broker.identifier), IDBValidKey(topic.toString())),
                        ),
                    )
                request.onsuccess =
                    EventHandler {
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
                        r.onsuccess =
                            EventHandler {
                                allTopics -= topic
                                if (allTopics.isEmpty()) {
                                    tx.commit()
                                    cont.resume(Unit)
                                }
                            }
                        r.onerror =
                            EventHandler {
                                cont.resumeWithException(
                                    Exception(
                                        "Failed to update subscription object for $topic",
                                        request.error,
                                    ),
                                )
                            }
                    }
                request.onerror =
                    EventHandler {
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
        val unsubCountRequest =
            tx.objectStore(UNSUB_MSG).count(
                IDBValidKey(arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId))),
            )
        val topicsRequest =
            tx
                .objectStore(SUBSCRIPTION)
                .index(UNSUB_INDEX)
                .getAll(IDBValidKey(arrayOf(IDBValidKey(broker.identifier), IDBValidKey(packetId))))
        val userPropertiesRequest =
            tx
                .objectStore(USER_PROPERTIES)
                .index(PROP_PACKET_ID_INDEX)
                .getAll(
                    IDBValidKey(
                        arrayOf(
                            IDBValidKey(broker.identifier),
                            IDBValidKey(packetId),
                            IDBValidKey(0),
                        ),
                    ),
                )
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
            packetIdentifier = packetId.toUShort(),
            topics = topics.map { TopicFilter.fromOrThrow(it) }.toSet(),
            userProperty = userProperties,
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
        return index.getAllKeys(
            IDBValidKey(
                arrayOf(IDBValidKey(brokerId), IDBValidKey(packetId), IDBValidKey(incoming)),
            ),
        )
    }

    override suspend fun updatePublishState(broker: MqttBroker, packetId: Int, state: Int) {
        // IDB persistence doesn't track QoS2 state separately
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
            request.onsuccess =
                EventHandler {
                    cont.resume(request.result)
                }
            request.onerror =
                EventHandler { e ->
                    console.error("request error, cast throwable", e)
                    cont.resumeWithException(request.error!!)
                }
        }
    }

    private suspend fun commitTransaction(
        tx: IDBTransaction,
        logName: String,
        customBlock: () -> Unit = {},
    ) = suspendCancellableCoroutine { cont ->
        tx.oncomplete =
            EventHandler {
                customBlock()
                cont.resume(Unit)
            }
        tx.onerror =
            EventHandler {
                cont.resumeWithException(Exception("error committing tx $logName", tx.error))
            }
        tx.onabort =
            EventHandler {
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
        private const val ALL_SUB_INDEX = "allSub"
        private const val UNSUB_MSG = "UnsubMsg"
        private const val UNSUB_INDEX = "unsub"
        private const val PROP_PACKET_ID_INDEX = "prop"

        suspend fun idbPersistence(
            indexedDb: IDBFactory,
            name: String,
        ): IDBPersistence {
            val database =
                suspendCoroutine<IDBDatabase> { cont ->
                    val openRequest = indexedDb.open(name, 1.0)
                    openRequest.onsuccess =
                        EventHandler {
                            cont.resume(openRequest.result)
                        }
                    openRequest.onupgradeneeded =
                        EventHandler {
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
                            subscriptionStore.createIndex(ALL_SUB_INDEX, arrayOf("brokerId", "subscribeId"))
                            subscriptionStore.createIndex(UNSUB_INDEX, arrayOf("brokerId", "unsubscribeId"))
                            propStore.createIndex(PROP_PACKET_ID_INDEX, arrayOf("brokerId", "packetId", "incoming"))
                            propStore.createIndex(BROKER_INDEX, "brokerId")
                        }
                    openRequest.onerror =
                        EventHandler {
                            cont.resumeWithException(openRequest.error as Throwable)
                        }
                }
            return IDBPersistence(database)
        }
    }
}
