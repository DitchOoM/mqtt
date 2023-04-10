package com.ditchoom.mqtt3.persistence

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
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.Subscription
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import web.idb.IDBDatabase
import web.idb.IDBFactory
import web.idb.IDBKeyRange
import web.idb.IDBRequest
import web.idb.IDBRequestReadyState
import web.idb.IDBTransaction
import web.idb.IDBTransactionMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class IDBPersistence(private val db: IDBDatabase) : Persistence {

    override suspend fun ackPub(broker: MqttBroker, packet: IPublishAcknowledgment) {
        val tx = db.transaction(PubMsg, IDBTransactionMode.readwrite)
        tx.objectStore(PubMsg).delete(arrayOf(broker.identifier, packet.packetIdentifier, 0))
        commitTransaction(tx, "ackPub")
    }

    override suspend fun ackPubComplete(broker: MqttBroker, packet: IPublishComplete) {
        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 1))
        commitTransaction(tx, "ackPubComplete")
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease
    ) {
        val tx = db.transaction(storeNames = arrayOf(PubMsg, QoS2Msg), mode = IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, incomingPubRecv.packetIdentifier, 0))
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                pubRel.packetIdentifier,
                pubRel.controlPacketValue,
                1
            )
        )
        commitTransaction(tx, "ackPubReceivedQueuePubRelease")
    }

    override suspend fun ackPubRelease(
        broker: MqttBroker,
        incomingPubRel: IPublishRelease,
        outPubComp: IPublishComplete
    ) {
        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                outPubComp.packetIdentifier,
                outPubComp.controlPacketValue,
                0
            )
        )
        commitTransaction(tx, "ackPubRelease")
    }

    override suspend fun ackSub(broker: MqttBroker, subAck: ISubscribeAcknowledgement) {
        val tx = db.transaction(SubMsg, IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SubMsg)
        subMsgStore.delete(arrayOf(broker.identifier, subAck.packetIdentifier))
        commitTransaction(tx, "ackSub")
    }

    override suspend fun ackUnsub(broker: MqttBroker, unsubAck: IUnsubscribeAcknowledgment) {
        val key = arrayOf(broker.identifier, unsubAck.packetIdentifier)
        val tx = db.transaction(arrayOf(UnsubMsg, Subscription), IDBTransactionMode.readwrite)
        val unsubMsgStore = tx.objectStore(UnsubMsg)
        unsubMsgStore.delete(arrayOf(broker.identifier, unsubAck.packetIdentifier))
        val subStore = tx.objectStore(Subscription)
        val unsubIndex = subStore.index(UnsubIndex)
        val unsubSubscriptionsRequest = unsubIndex.getAll(key)
        unsubSubscriptionsRequest.onsuccess = {
            for (unsubscription in unsubSubscriptionsRequest.result) {
                val d = unsubscription.asDynamic()
                subStore.delete(arrayOf(broker.identifier, d.topicFilter))
            }
            tx.commit()
        }
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean
    ): Map<Topic, ISubscription> {
        val tx = db.transaction(Subscription, IDBTransactionMode.readonly)
        val subStore = tx.objectStore(Subscription)
        val index = subStore.index(BrokerIndex)
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
                    d.qos as Byte
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
        val tx = db.transaction(Broker, IDBTransactionMode.readwrite)
        val store = tx.objectStore(Broker)
        val connections = PersistableSocketConnection.from(connectionOps)
        val persistableRequest = PersistableConnectionRequest.from(connectionRequest as ConnectionRequest)
        val storeCountRequest = store.count()
        val countOp = suspendCoroutine { cont ->
            storeCountRequest.onsuccess = {
                val countOp = storeCountRequest.result.unsafeCast<Int>()
                val broker = PersistableBroker(countOp, connections, persistableRequest)
                store.put(broker)
                tx.commit()
                cont.resume(countOp)
            }
        }
        return MqttBroker(countOp, connectionOps, connectionRequest)
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

    private suspend fun commitTransaction(tx: IDBTransaction, logName: String) {
        return suspendCancellableCoroutine { cont ->
            tx.oncomplete = {
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

    override suspend fun brokerWithId(identifier: Int): MqttBroker? {
        val tx = db.transaction(Broker, IDBTransactionMode.readonly)
        val store = tx.objectStore(Broker)
        val brokerObjRequest = store[arrayOf(identifier)]
        commitTransaction(tx, "brokerWithId v4")
        try {
            await(brokerObjRequest)
        } catch (t: Throwable) {
            return null
        }
        val result = brokerObjRequest.result ?: return null
        val d = result.asDynamic()
        return MqttBroker(
            d.id as Int,
            (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
            toConnectionRequest(d.connectionRequest)
        )
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        val tx = db.transaction(Broker, IDBTransactionMode.readonly)
        val store = tx.objectStore(Broker)
        val allBrokersRequest = store.getAll()
        commitTransaction(tx, "allBrokers v4")
        await(allBrokersRequest)
        val results = allBrokersRequest.result
        if (results.isEmpty()) {
            return emptyList()
        }
        return results.toList().map { persistableBroker ->
            val d = persistableBroker.asDynamic()
            MqttBroker(
                d.id as Int,
                (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toSet(),
                toConnectionRequest(d.connectionRequest)
            )
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

        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                replyMessage.packetIdentifier,
                replyMessage.controlPacketValue,
                0
            )
        )
        commitTransaction(tx, "incomingPublish")
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val tx = db.transaction(arrayOf(PubMsg, QoS2Msg, SubMsg, UnsubMsg, Subscription), IDBTransactionMode.readonly)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2Store = tx.objectStore(QoS2Msg)
        val subStore = tx.objectStore(SubMsg)
        val subscriptionStore = tx.objectStore(Subscription)
        val unsubStore = tx.objectStore(UnsubMsg)
        val subscriptionsRequest = subscriptionStore.index(AllSubBrokerId).getAll(broker.identifier)
        val pubIdbRequest = queuedMsgStore.index(BrokerIncomingIndex).getAll(arrayOf(broker.identifier, 0))
        val qos2PersistableIdbRequest = qos2Store.index(BrokerIndex).getAll(broker.identifier)
        val subscribeRequestIdbRequest = subStore.index(BrokerIndex).getAll(broker.identifier)
        val unsubscribeRequestsIdbRequest = unsubStore.index(BrokerIndex).getAll(broker.identifier)
        commitTransaction(tx, "messagesToSendOnReconnect")
        await(pubIdbRequest)
        val pubs =
            pubIdbRequest.result.map { toPub(it.unsafeCast<PersistablePublishMessage>()).setDupFlagNewPubMessage() }
        await(qos2PersistableIdbRequest)
        val qos2 = qos2PersistableIdbRequest.result.map {
            val dynamicIt = it.asDynamic()
            val msg = PersistableQos2Message(
                dynamicIt.brokerId as Int,
                dynamicIt.packetId as Int,
                dynamicIt.type as Byte,
                dynamicIt.incoming as Int
            )
            when (msg.type) {
                IPublishReceived.controlPacketValue -> PublishReceived(msg.packetId)
                IPublishRelease.controlPacketValue -> PublishRelease(msg.packetId)
                IPublishComplete.controlPacketValue -> PublishComplete(msg.packetId)
                else -> error("IDB Persistence failed to get a valid qos 2 type")
            }
        }
        await(subscriptionsRequest)
        val persistableSubscriptions = subscriptionsRequest.result.map {
            it.unsafeCast<PersistableSubscription>()
        }.toTypedArray()
        val subscriptionsBySubscribePacketId = HashMap<Int, MutableSet<Subscription>>()
        persistableSubscriptions.forEach {
            val subscriptionsById = subscriptionsBySubscribePacketId.getOrPut(it.subscribeId) { HashSet() }
            subscriptionsById.add(toSubscription(it))
        }
        await(subscribeRequestIdbRequest)
        val retrievedSubscribeRequests = if (subscribeRequestIdbRequest.result.isNotEmpty()) {
            subscribeRequestIdbRequest.result
                .map {
                    PersistableSubscribe(it.asDynamic().brokerId as Int, it.asDynamic().packetId as Int)
                }
                .map { persistableSubscribe ->
                    val subscriptions = checkNotNull(subscriptionsBySubscribePacketId[persistableSubscribe.packetId])
                    SubscribeRequest(persistableSubscribe.packetId, subscriptions)
                }
        } else {
            emptyList()
        }
        val unsubscriptionsByUnsubscribePacketId = HashMap<Int, MutableSet<String>>()
        persistableSubscriptions.forEach {
            val subscriptionsById = unsubscriptionsByUnsubscribePacketId.getOrPut(it.unsubscribeId) { HashSet() }
            subscriptionsById.add(it.topicFilter)
        }
        await(unsubscribeRequestsIdbRequest)
        val unsubs = unsubscribeRequestsIdbRequest.result
            .mapNotNull { unsubscribeRequestObject ->
                val packetId = unsubscribeRequestObject.asDynamic().packetId.unsafeCast<Int>()
                val topics = unsubscriptionsByUnsubscribePacketId[packetId]
                if (topics != null) {
                    UnsubscribeRequest(packetId, topics)
                } else {
                    null
                }
            }
        return (pubs + retrievedSubscribeRequests + unsubs + qos2).sortedBy { it.packetIdentifier }
    }

    override suspend fun onPubCompWritten(broker: MqttBroker, outPubComp: IPublishComplete) {
        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, outPubComp.packetIdentifier, 0))
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
        val newPacketId = getAndIncrementPacketId(broker)
        val tx = db.transaction(arrayOf(PacketId, PubMsg), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val packetIdPub = pub.maybeCopyWithNewPacketIdentifier(newPacketId) as PublishMessage
        val persistablePub = PersistablePublishMessage(broker.identifier, false, packetIdPub)
        queuedMsgStore.put(persistablePub)
        commitTransaction(tx, "writePubGetPacketId")
        return newPacketId
    }

    override suspend fun getPubWithPacketId(broker: MqttBroker, packetId: Int): IPublishMessage? {
        val tx = db.transaction(arrayOf(PubMsg), IDBTransactionMode.readonly)
        val pubRequest = tx.objectStore(PubMsg)[arrayOf(broker.identifier, packetId, 0)]
        commitTransaction(tx, "getPubWithPacketId")
        await(pubRequest)
        val persistablePub = pubRequest.result?.unsafeCast<PersistablePublishMessage>() ?: return null
        return toPub(persistablePub)
    }

    private suspend fun getAndIncrementPacketId(broker: MqttBroker): Int {
        val tx = db.transaction(arrayOf(PacketId), IDBTransactionMode.readwrite)
        val packetIdStore = tx.objectStore(PacketId)
        val brokerIdKey = IDBKeyRange.only(broker.identifier)
        val packetIdCurrentRequest = packetIdStore[brokerIdKey]
        return suspendCoroutine { cont ->
            packetIdCurrentRequest.onsuccess = {
                val result = packetIdCurrentRequest.result
                val value = if (result == undefined) {
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
        sub: ISubscribeRequest
    ): ISubscribeRequest {
        val newPacketId = getAndIncrementPacketId(broker)
        val newSub = sub.copyWithNewPacketIdentifier(newPacketId)
        val persistableSubscribe = PersistableSubscribe(broker.identifier, newSub.packetIdentifier)
        val tx = db.transaction(arrayOf(PacketId, SubMsg, Subscription), IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SubMsg)
        subMsgStore.add(persistableSubscribe)
        val subStore = tx.objectStore(Subscription)
        for (subscription in sub.subscriptions) {
            subStore.add(PersistableSubscription(broker.identifier, newPacketId, subscription))
        }
        commitTransaction(tx, "writeSubUpdatePacketIdAndSimplifySubscriptions")
        return newSub
    }

    override suspend fun getSubWithPacketId(broker: MqttBroker, packetId: Int): ISubscribeRequest? {
        val tx = db.transaction(arrayOf(SubMsg, Subscription), IDBTransactionMode.readonly)
        val subStore = tx.objectStore(SubMsg)

        val objRequest = subStore[arrayOf(broker.identifier, packetId)]
        val subscriptionStore = tx.objectStore(Subscription)
        val subIndex = subscriptionStore.index(BrokerIdPacketIdSubIndex)
        val allSubRequest = subIndex.getAll(arrayOf(broker.identifier, packetId))

        commitTransaction(tx, "getSubWIthPacketId")
        await(objRequest)
        val obj = objRequest.result ?: return null
        await(allSubRequest)
        if (allSubRequest.result.isEmpty()) {
            return null
        }
        val persistableSubscribe =
            PersistableSubscribe(obj.asDynamic().brokerId as Int, obj.asDynamic().packetId as Int)

        val subscriptions =
            allSubRequest.result
                .map { toSubscription(it.unsafeCast<PersistableSubscription>()) }
        val s = SubscribeRequest(persistableSubscribe.packetId, subscriptions.toSet())
        return s
    }

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        val newPacketId = getAndIncrementPacketId(broker)
        suspendCoroutine { cont ->
            val newUnsub = unsub.copyWithNewPacketIdentifier(newPacketId)
            val persistableUnsub = PersistableUnsubscribe(broker.identifier, newUnsub as UnsubscribeRequest)

            val tx = db.transaction(arrayOf(PacketId, UnsubMsg, Subscription), IDBTransactionMode.readwrite)
            val unsubMsgStore = tx.objectStore(UnsubMsg)
            unsubMsgStore.put(persistableUnsub)
            val subscriptions = tx.objectStore(Subscription)

            val topicsLeft = HashSet(unsub.topics)
            unsub.topics.forEach { topicObj ->
                val topic = topicObj.toString()
                val request = subscriptions[arrayOf(broker.identifier, topic)]
                request.onsuccess = {
                    val persistableSubscription = request.result.asDynamic()
                    subscriptions.put(
                        PersistableSubscription(
                            broker.identifier,
                            topic,
                            persistableSubscription.subscribeId as Int,
                            newPacketId,
                            persistableSubscription.qos as Byte
                        )
                    )
                    topicsLeft -= topicObj
                    if (topicsLeft.isEmpty()) {
                        tx.commit()
                        cont.resume(Unit)
                    }
                }
                request.onerror = {
                    cont.resumeWithException(request.error!!)
                }
            }
        }
        return newPacketId
    }

    override suspend fun getUnsubWithPacketId(broker: MqttBroker, packetId: Int): IUnsubscribeRequest? {
        val tx = db.transaction(arrayOf(UnsubMsg, Subscription), IDBTransactionMode.readonly)
        val subStore = tx.objectStore(Subscription)
        val unsubIndex = subStore.index(UnsubIndex)
        val topicsRequest = unsubIndex.getAll(arrayOf(broker.identifier, packetId))
        commitTransaction(tx, "getUnsubWithPacketId")
        await(topicsRequest)
        if (topicsRequest.result.isEmpty()) {
            return null
        }
        val topics = topicsRequest.result.map { it.asDynamic().topicFilter.toString() }
        return UnsubscribeRequest(packetId, topics)
    }

    override suspend fun isQueueClear(broker: MqttBroker, includeSubscriptions: Boolean): Boolean {
        val packets = messagesToSendOnReconnect(broker)
        if (packets.isNotEmpty()) {
            println(packets.joinToString())
            return false
        }
        return true
    }

    companion object {
        private const val Broker = "Broker"
        private const val BrokerIndex = "BrokerId"
        private const val BrokerIncomingIndex = "brokerIncomingIndex"
        private const val PacketId = "PacketId"
        private const val PubMsg = "PubMsg"
        private const val Subscription = "Subscription"
        private const val QoS2Msg = "QoS2Msg"
        private const val SubMsg = "SubMsg"
        private const val SubIndex = "sub"
        private const val BrokerIdPacketIdSubIndex = "brokerIdPacketIdSubIndex"
        private const val AllSubBrokerId = "brokerId"
        private const val UnsubMsg = "UnsubMsg"
        private const val UnsubIndex = "unsub"

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
                    pubStore.createIndex(BrokerIncomingIndex, arrayOf("brokerId", "incoming"))
                    qos2Store.createIndex(BrokerIndex, "brokerId")
                    subStore.createIndex(BrokerIndex, "brokerId")
                    unsubStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(SubIndex, arrayOf("brokerId", "topicFilter", "subscribeId"))
                    subscriptionStore.createIndex(BrokerIdPacketIdSubIndex, arrayOf("brokerId", "subscribeId"))
                    subscriptionStore.createIndex(AllSubBrokerId, "brokerId")
                    subscriptionStore.createIndex(UnsubIndex, arrayOf("brokerId", "unsubscribeId"))
                }
                openRequest.onerror = {
                    console.error("open request error, cast throwable", it)
                    cont.resumeWithException(openRequest.error as Throwable)
                }
            }
            return IDBPersistence(database)
        }
    }
}
