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
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import web.idb.IDBDatabase
import web.idb.IDBFactory
import web.idb.IDBKeyRange
import web.idb.IDBRequest
import web.idb.IDBTransaction
import web.idb.IDBTransactionMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class IDBPersistence(private val db: IDBDatabase) : Persistence {

    private val dispatcher = defaultDispatcher(0, "unused")
    override suspend fun ackPub(broker: MqttBroker, packet: IPublishAcknowledgment) {
        val tx = db.transaction(PubMsg, IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        request { queuedMsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier)) }
        commitTransaction(tx)
    }

    override suspend fun ackPubComplete(broker: MqttBroker, packet: IPublishComplete) {
        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        qos2MsgStore.delete(arrayOf(broker.identifier, packet.packetIdentifier, 1))
        commitTransaction(tx)
    }

    override suspend fun ackPubReceivedQueuePubRelease(
        broker: MqttBroker,
        incomingPubRecv: IPublishReceived,
        pubRel: IPublishRelease
    ) {
        val tx = db.transaction(arrayOf(PubMsg, QoS2Msg), IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2MsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, incomingPubRecv.packetIdentifier))
        qos2MsgStore.put(
            PersistableQos2Message(
                broker.identifier,
                pubRel.packetIdentifier,
                pubRel.controlPacketValue,
                1
            )
        )
        commitTransaction(tx)
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
        commitTransaction(tx)
    }

    override suspend fun ackSub(broker: MqttBroker, subAck: ISubscribeAcknowledgement) {
        val tx = db.transaction(SubMsg, IDBTransactionMode.readwrite)
        val subMsgStore = tx.objectStore(SubMsg)
        subMsgStore.delete(arrayOf(broker.identifier, subAck.packetIdentifier))
        commitTransaction(tx)
    }

    override suspend fun ackUnsub(broker: MqttBroker, unsubAck: IUnsubscribeAcknowledgment) {
        val key = arrayOf(broker.identifier, unsubAck.packetIdentifier)
        val tx = db.transaction(arrayOf(UnsubMsg, Subscription), IDBTransactionMode.readwrite)
        val unsubMsgStore = tx.objectStore(UnsubMsg)
        unsubMsgStore.delete(arrayOf(broker.identifier, unsubAck.packetIdentifier))
        val subStore = tx.objectStore(Subscription)
        val unsubIndex = subStore.index(UnsubIndex)

        val unsubSubscriptions = request { unsubIndex.getAll(key) }
        for (unsubscription in unsubSubscriptions) {
            val d = unsubscription.asDynamic()
            PersistableUnsubscribe(d.brokerId.unsafeCast<Int>(), unsubAck.packetIdentifier)
            subStore.delete(arrayOf(broker.identifier, d.topicFilter))
        }
        commitTransaction(tx)
    }

    override suspend fun activeSubscriptions(
        broker: MqttBroker,
        includePendingUnsub: Boolean
    ): Map<Topic, ISubscription> {
        val tx = db.transaction(Subscription, IDBTransactionMode.readonly)
        val subStore = tx.objectStore(Subscription)
        val index = subStore.index(BrokerIndex)
        val subscriptionsRaw = request { index.getAll(broker.identifier) }
        commitTransaction(tx)
        return subscriptionsRaw
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
        val countOp = request { store.count() }
        val connections = PersistableSocketConnection.from(connectionOps)
        val persistableRequest = PersistableConnectionRequest.from(connectionRequest as ConnectionRequest)
        val broker = PersistableBroker(countOp, connections, persistableRequest)
        store.put(broker, countOp)
        commitTransaction(tx)
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

    private suspend fun commitTransaction(tx: IDBTransaction) = withContext(dispatcher) {
        suspendCancellableCoroutine { cont ->
            tx.oncomplete = {
                cont.resume(Unit)
            }
            tx.onerror = {
                cont.resumeWithException(tx.error as Throwable)
            }
            cont.invokeOnCancellation {
//                if (!cont.isCompleted) {
//                    tx.abort()
//                }
            }
            tx.commit()
        }
    }

    override suspend fun allBrokers(): Collection<MqttBroker> {
        val tx = db.transaction(Broker, IDBTransactionMode.readonly)
        val store = tx.objectStore(Broker)
        val results = request { store.getAll() }
        return results.toList().map { persistableBroker ->
            val d = persistableBroker.asDynamic()
            MqttBroker(
                d.id as Int,
                (d.connectionOptions as Array<*>).map { toSocketConnection(it) }.toList(),
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
        commitTransaction(tx)
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
        commitTransaction(tx)
    }

    override suspend fun messagesToSendOnReconnect(broker: MqttBroker): Collection<ControlPacket> {
        val tx = db.transaction(arrayOf(PubMsg, QoS2Msg, SubMsg, UnsubMsg, Subscription), IDBTransactionMode.readonly)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val qos2Store = tx.objectStore(QoS2Msg)
        val subStore = tx.objectStore(SubMsg)
        val subscriptionStore = tx.objectStore(Subscription)
        val unsubStore = tx.objectStore(UnsubMsg)
        val pub = request { queuedMsgStore.index(BrokerIndex).getAll(broker.identifier) }
        val qos2Persistable = request { qos2Store.index(BrokerIndex).getAll(broker.identifier) }
        val subscribeRequests = request { subStore.index(BrokerIndex).getAll(broker.identifier) }
        val subIndex = subscriptionStore.index(AllSubIndex)
        val retrievedSubscribeRequests = if (subscribeRequests.isNotEmpty()) {
            subscribeRequests
                .map {
                    PersistableSubscribe(it.asDynamic().brokerId as Int, it.asDynamic().packetId as Int)
                }
                .map { persistableSubscribe ->
                    val subscriptions =
                        request { subIndex.getAll(arrayOf(broker.identifier, persistableSubscribe.packetId)) }
                            .map { toSubscription(it.unsafeCast<PersistableSubscription>()) }
                    SubscribeRequest(persistableSubscribe.packetId, subscriptions.toSet())
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
                UnsubscribeRequest(packetId, topics)
            }
        commitTransaction(tx)
        val pubs = pub.map { toPub(it.unsafeCast<PersistablePublishMessage>()) }
        val qos2 = qos2Persistable.map {
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
        return (pubs + retrievedSubscribeRequests + unsubs + qos2).sortedBy { it.packetIdentifier }
    }

    override suspend fun onPubCompWritten(broker: MqttBroker, outPubComp: IPublishComplete) {
        val tx = db.transaction(QoS2Msg, IDBTransactionMode.readwrite)
        val queuedMsgStore = tx.objectStore(QoS2Msg)
        queuedMsgStore.delete(arrayOf(broker.identifier, outPubComp.packetIdentifier, 0))
        commitTransaction(tx)
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

        val key = IDBKeyRange.only(identifier)
        packetIdStore.delete(key)
        queuedMsgStore.delete(key)
        subscriptionStore.delete(key)
        brokerStore.delete(key)
        qos2Store.delete(key)
        subStore.delete(key)
        unsubStore.delete(key)
        commitTransaction(tx)
    }

    override suspend fun writePubGetPacketId(broker: MqttBroker, pub: IPublishMessage): Int {
        val tx = db.transaction(arrayOf(PacketId, PubMsg), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val queuedMsgStore = tx.objectStore(PubMsg)
        val packetIdPub = pub.maybeCopyWithNewPacketIdentifier(newPacketId) as PublishMessage
        val newPub = packetIdPub.copy(packetIdPub.fixed.copy(dup = true))
        val persistablePub = PersistablePublishMessage(broker.identifier, newPub)
        queuedMsgStore.put(persistablePub)
        commitTransaction(tx)
        return newPacketId
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
        val tx = db.transaction(arrayOf(PacketId, SubMsg, Subscription), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val subMsgStore = tx.objectStore(SubMsg)
        val newSub = sub.copyWithNewPacketIdentifier(newPacketId)
        val persistableSubscribe = PersistableSubscribe(broker.identifier, newSub.packetIdentifier)
        subMsgStore.add(persistableSubscribe)
        val subStore = tx.objectStore(Subscription)
        for (subscription in sub.subscriptions) {
            subStore.add(PersistableSubscription(broker.identifier, newPacketId, subscription))
        }
        commitTransaction(tx)
        return newSub
    }

    override suspend fun writeUnsubGetPacketId(broker: MqttBroker, unsub: IUnsubscribeRequest): Int {
        val tx = db.transaction(arrayOf(PacketId, UnsubMsg, Subscription), IDBTransactionMode.readwrite)
        val newPacketId = getAndIncrementPacketId(tx, broker)
        val newUnsub = unsub.copyWithNewPacketIdentifier(newPacketId)

        val persistableUnsub = PersistableUnsubscribe(broker.identifier, newUnsub as UnsubscribeRequest)
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
                persistableSubscription.asDynamic().qos as Byte
            )
        }
        persistableSubscriptions.forEach {
            subscriptions.put(it)
        }
        commitTransaction(tx)
        return newPacketId
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
        private const val PacketId = "PacketId"
        private const val PubMsg = "PubMsg"
        private const val Subscription = "Subscription"
        private const val QoS2Msg = "QoS2Msg"
        private const val SubMsg = "SubMsg"
        private const val SubIndex = "sub"
        private const val AllSubIndex = "allSub"
        private const val UnsubMsg = "UnsubMsg"
        private const val UnsubIndex = "unsub"
        private const val AllUnsubIndex = "allUnsub"

        suspend fun idbPersistence(indexedDb: IDBFactory, name: String): IDBPersistence {
            val database = suspendCoroutine<IDBDatabase> { cont ->
                val openRequest = indexedDb.open(name, 1)
                openRequest.onsuccess = {
                    cont.resume(openRequest.result)
                }
                openRequest.onupgradeneeded = {
                    val db = openRequest.result
                    db.createObjectStore(Broker)
                    db.createObjectStore(PacketId)
                    val pubStore = db.createObjectStore(PubMsg, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                    val subscriptionStore =
                        db.createObjectStore(Subscription, js("{ keyPath: [\"brokerId\", \"topicFilter\"] }"))
                    val qos2Store =
                        db.createObjectStore(QoS2Msg, js("{ keyPath: [\"brokerId\", \"packetId\", \"incoming\"] }"))
                    val subStore = db.createObjectStore(SubMsg, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                    val unsubStore = db.createObjectStore(UnsubMsg, js("{ keyPath: [\"brokerId\", \"packetId\"] }"))
                    pubStore.createIndex(BrokerIndex, "brokerId")
                    qos2Store.createIndex(BrokerIndex, "brokerId")
                    subStore.createIndex(BrokerIndex, "brokerId")
                    unsubStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(BrokerIndex, "brokerId")
                    subscriptionStore.createIndex(SubIndex, arrayOf("brokerId", "topicFilter", "subscribeId"))
                    subscriptionStore.createIndex(AllSubIndex, arrayOf("brokerId", "subscribeId"))
                    subscriptionStore.createIndex(UnsubIndex, arrayOf("brokerId", "unsubscribeId"))
                }
                openRequest.onerror = {
                    cont.resumeWithException(openRequest.error as Throwable)
                }
            }
            return IDBPersistence(database)
        }
    }
}
