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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * This API will delegate writing to the persistence as well as queuing up messages to the correct
 * socket controller. As network fluctuations can occur, this will ensure messages will be queued
 * to the correct socket
 */
class ControlPacketProcessor(
    private val scope: CoroutineScope,
    private val broker: MqttBroker,
    internal val readChannel: SharedFlow<ControlPacket>,
    private val writeChannel: Channel<Collection<ControlPacket>>,
    internal val persistence: Persistence
) {

    var observer: Observer? = null
    private var currentPingJob: Job? = null
    var pingCount = 0L
        private set
    var pingResponseCount = 0L
        private set

    suspend fun publish(pub: IPublishMessage, persist: Boolean = true): IPublishMessage {
        val publishMessageOnWire = if (persist && pub.qualityOfService.isGreaterThan(QualityOfService.AT_MOST_ONCE)) {
            val packetId = persistence.writePubGetPacketId(broker, pub)
            pub.maybeCopyWithNewPacketIdentifier(packetId)
        } else {
            pub
        }
        write(publishMessageOnWire)
        return publishMessageOnWire
    }

    suspend fun subscribe(sub: ISubscribeRequest, persist: Boolean = true): ISubscribeRequest {
        val persistedPacket = if (persist) {
            persistence.writeSubUpdatePacketIdAndSimplifySubscriptions(broker, sub)
        } else {
            sub
        }
        write(persistedPacket)
        return persistedPacket
    }

    suspend fun unsubscribe(unsub: IUnsubscribeRequest, persist: Boolean = true): IUnsubscribeRequest {
        val persistedPacket = if (persist) {
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
        controlPacketValue: Byte
    ): R {
        return readChannel
            .transformWhile {
                if (it.controlPacketValue == controlPacketValue && it.packetIdentifier.toString() == packetIdentifier.toString()) {
                    emit(it as R)
                    false
                } else {
                    true
                }
            }.last()
    }

    suspend fun queueMessagesOnReconnect() = persistence.messagesToSendOnReconnect(broker).map {
        (it as? IPublishMessage)?.setDupFlagNewPubMessage() ?: it
    }

    suspend fun processIncomingMessages() {
        readChannel.collect { packet ->
            when (packet) {
                is IDisconnectNotification -> {
                }

                is IPingRequest -> {
                    write(packet.controlPacketFactory.pingResponse())
                }

                is IPingResponse -> pingResponseCount++
                is IPublishAcknowledgment -> persistence.ackPub(broker, packet)
                is IPublishMessage -> {
                    val replyMessage = packet.expectedResponse()
                    if (replyMessage != null) {
                        persistence.incomingPublish(broker, packet, replyMessage)
                        write(replyMessage)
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

    fun resetPingTimer() {
        observer?.resetPingTimer(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
        val delayDuration = broker.connectionRequest.keepAliveTimeoutSeconds.toInt().seconds
        cancelPingTimer()
        currentPingJob = scope.launch {
            observer?.delayPing(broker.identifier, broker.connectionRequest.protocolVersion.toByte(), delayDuration)
            delay(delayDuration)
            while (isActive) {
                observer?.sendingPing(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
                writeChannel.send(listOf(broker.connectionRequest.controlPacketFactory.pingRequest()))
                pingCount++
                observer?.delayPing(broker.identifier, broker.connectionRequest.protocolVersion.toByte(), delayDuration)
                delay(delayDuration)
            }
        }
    }

    fun cancelPingTimer() {
        observer?.cancelPingTimer(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
        currentPingJob?.cancel()
        currentPingJob = null
    }

    private suspend fun write(packet: ControlPacket) = write(listOf(packet))
    private suspend fun write(packets: Collection<ControlPacket>) {
        writeChannel.send(packets)
        resetPingTimer()
    }
}
