package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.IPingRequest
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.QualityOfService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ControlPacketProcessor(
    private val broker: MqttBroker,
    internal val readChannel: SharedFlow<ControlPacket>,
    private val writeChannel: Channel<Collection<ControlPacket>>,
    internal val persistence: Persistence,
) {
    var observer: Observer? = null
    internal val publishDispatcher = PublishDispatcher()
    var pingCount = 0L
        private set
    var pingResponseCount = 0L
        private set

    @kotlin.concurrent.Volatile
    private var lastActivityMark = TimeSource.Monotonic.markNow()

    fun noteActivity() {
        lastActivityMark = TimeSource.Monotonic.markNow()
    }

    suspend fun publish(
        pub: IPublishMessage,
        persist: Boolean = true,
    ): IPublishMessage {
        val publishMessageOnWire =
            if (persist && pub.qualityOfService.isGreaterThan(QualityOfService.AT_MOST_ONCE)) {
                val packetId = persistence.writePubGetPacketId(broker, pub)
                pub.maybeCopyWithNewPacketIdentifier(packetId)
            } else {
                pub
            }
        write(publishMessageOnWire)
        return publishMessageOnWire
    }

    suspend fun preparePublish(
        pub: IPublishMessage,
        persist: Boolean = true,
    ): IPublishMessage =
        if (persist && pub.qualityOfService.isGreaterThan(QualityOfService.AT_MOST_ONCE)) {
            val packetId = persistence.writePubGetPacketId(broker, pub)
            pub.maybeCopyWithNewPacketIdentifier(packetId)
        } else {
            pub
        }

    internal suspend fun sendPacket(packet: ControlPacket) {
        write(packet)
    }

    suspend fun subscribe(
        sub: ISubscribeRequest,
        persist: Boolean = true,
    ): ISubscribeRequest {
        val persistedPacket =
            if (persist) {
                persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
            } else {
                sub
            }
        write(persistedPacket)
        return persistedPacket
    }

    suspend fun unsubscribe(
        unsub: IUnsubscribeRequest,
        persist: Boolean = true,
    ): IUnsubscribeRequest {
        val persistedPacket =
            if (persist) {
                val packetId = persistence.writeUnsubGetPacketId(broker, unsub)
                unsub.copyWithNewPacketIdentifier(packetId)
            } else {
                unsub
            }
        write(persistedPacket)
        return persistedPacket
    }

    internal suspend inline fun <reified R : ControlPacket> awaitIncomingPacketId(
        packetIdentifier: Int,
        controlPacketValue: Byte,
    ): R =
        readChannel
            .transformWhile {
                if (it.controlPacketValue == controlPacketValue && it.packetIdentifier.toString() == packetIdentifier.toString()) {
                    emit(it as R)
                    false
                } else {
                    true
                }
            }.last()

    suspend fun queueMessagesOnReconnect() =
        persistence.messagesToSendOnReconnect(broker).map {
            (it as? IPublishMessage)?.setDupFlagNewPubMessage() ?: it
        }

    suspend fun processIncomingMessages() {
        readChannel.collect { packet ->
            when (packet) {
                is IDisconnectNotification -> {}
                is IPingRequest -> write(packet.controlPacketFactory.pingResponse())
                is IPingResponse -> pingResponseCount++
                is IPublishAcknowledgment -> persistence.ackPub(broker, packet)
                is IPublishMessage -> {
                    val replyMessage = packet.expectedResponse()
                    if (replyMessage != null) {
                        persistence.incomingPublish(broker, packet, replyMessage)
                        write(replyMessage)
                    }
                    if (!publishDispatcher.isEmpty()) {
                        publishDispatcher.dispatch(packet.toIncomingPublish())
                    }
                }
                is IPublishReceived -> {
                    val pubRel = packet.expectedResponse()
                    persistence.ackPubReceivedQueuePubRelease(broker, packet, pubRel)
                    write(pubRel)
                }
                is IPublishRelease -> {
                    val pubComp = packet.expectedResponse()
                    persistence.ackPubRelease(broker, packet, pubComp)
                    write(pubComp)
                    persistence.onPubCompWritten(broker, pubComp)
                }
                is IPublishComplete -> persistence.ackPubComplete(broker, packet)
                is ISubscribeAcknowledgement -> persistence.ackSub(broker, packet)
                is IUnsubscribeAcknowledgment -> persistence.ackUnsub(broker, packet)
            }
        }
    }

    /**
     * Sends PINGREQ at the keep-alive interval when no other activity has occurred.
     * Runs as a child coroutine — cancelled automatically when the parent scope ends.
     */
    suspend fun runPingTimer() {
        val interval =
            broker.connectionRequest.keepAliveTimeoutSeconds
                .toInt()
                .seconds
        if (interval == 0.seconds) return
        observer?.resetPingTimer(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
        while (currentCoroutineContext().isActive) {
            observer?.delayPing(broker.identifier, broker.connectionRequest.protocolVersion.toByte(), interval)
            delay(interval)
            if ((TimeSource.Monotonic.markNow() - lastActivityMark) >= interval) {
                observer?.sendingPing(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
                writeChannel.send(listOf(broker.connectionRequest.controlPacketFactory.pingRequest()))
                pingCount++
                noteActivity()
            }
        }
    }

    private suspend fun write(packet: ControlPacket) = write(listOf(packet))

    private suspend fun write(packets: Collection<ControlPacket>) {
        writeChannel.send(packets)
        noteActivity()
    }
}
