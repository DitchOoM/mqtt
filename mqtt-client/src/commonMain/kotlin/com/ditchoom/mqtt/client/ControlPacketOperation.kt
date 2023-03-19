package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import kotlinx.coroutines.Deferred

sealed interface PublishOperation {
    object QoSAtMostOnceComplete : PublishOperation
    data class QoSAtLeastOnce(val packetId: Int, val pubAck: Deferred<IPublishAcknowledgment>) : PublishOperation {
        override suspend fun awaitAll(): QoSAtLeastOnce {
            pubAck.await()
            return this
        }
    }

    data class QoSExactlyOnce(
        val packetId: Int,
        val pubRec: Deferred<IPublishReceived>,
        val pubComp: Deferred<IPublishComplete>
    ) : PublishOperation {
        override suspend fun awaitAll(): QoSExactlyOnce {
            kotlinx.coroutines.awaitAll(pubRec, pubComp)
            return this
        }
    }

    suspend fun awaitAll(): PublishOperation = this
}

data class SubscribeOperation(
    val packetId: Int,
    val subAck: Deferred<ISubscribeAcknowledgement>
)

data class UnsubscribeOperation(val packetId: Int, val unsubAck: Deferred<IUnsubscribeAcknowledgment>)
