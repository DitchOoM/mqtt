package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.IPingRequest
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    internal val publishDispatcher = PublishDispatcher()
    var pingCount = 0L
        private set
    var pingResponseCount = 0L
        private set

    /** Active outbound QoS 1 publish state flows, keyed by packet ID. */
    internal val qos1States = mutableMapOf<Int, MutableStateFlow<QoS1State>>()

    /** Active outbound QoS 2 publish state flows, keyed by packet ID. */
    internal val qos2States = mutableMapOf<Int, MutableStateFlow<QoS2State>>()

    // IPC observer hook: RemoteMqttClientWorker fans readChannel + sentPackets out to
    // registered per-process observers so remote clients see packets that cross this
    // in-process MQTT session. DROP_OLDEST keeps a slow observer from blocking the
    // processor's hot path.
    private val _sentPackets =
        MutableSharedFlow<ControlPacket>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val sentPackets: SharedFlow<ControlPacket> = _sentPackets

    /** Called by writeLoop when a packet is actually written to wire. */
    fun onPacketSent(packet: ControlPacket) {
        val packetId = packet.packetIdentifier
        qos1States[packetId]?.let { if (it.value == QoS1State.Queued) it.value = QoS1State.Sent }
        qos2States[packetId]?.let { if (it.value == QoS2State.Queued) it.value = QoS2State.Sent }
        _sentPackets.tryEmit(packet)
    }

    @kotlin.concurrent.Volatile
    private var lastActivityMark = TimeSource.Monotonic.markNow()

    fun noteActivity() {
        lastActivityMark = TimeSource.Monotonic.markNow()
    }

    suspend fun publish(
        pub: PublishMessage,
        persist: Boolean = true,
    ): PublishMessage {
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
        pub: PublishMessage,
        persist: Boolean = true,
    ): PublishMessage =
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
            (it as? PublishMessage)?.setDupFlagNewPubMessage() ?: it
        }

    /**
     * Replays incoming QoS 1/2 publishes that were persisted but not yet fully acknowledged.
     * Call once after the MQTT session is established.
     *
     * - RECEIVED_PENDING_HANDLER rows: redispatch to the subscriber handler; on success
     *   mark handler complete and write PUBACK (QoS 1) or PUBREC (QoS 2).
     * - QOS2_HANDLER_COMPLETE_PUBREC_SENT rows: do NOT redispatch (handler already ran);
     *   resend PUBREC to nudge the broker to send PUBREL.
     */
    suspend fun replayIncomingMessagesOnReconnect() {
        val records = persistence.incomingMessagesToRedispatch(broker)
        for (record in records) {
            when (record.state) {
                Persistence.INCOMING_STATE_RECEIVED_PENDING_HANDLER -> {
                    try {
                        if (!publishDispatcher.isEmpty()) {
                            publishDispatcher.dispatch(record.packet)
                        }
                        persistence.incomingHandlerComplete(broker, record.packet.packetIdentifier)
                        record.packet.expectedResponse()?.let { write(it) }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Throwable,
                    ) {
                        // Handler threw — leave row for the next reconnect.
                    }
                }
                Persistence.INCOMING_STATE_QOS2_HANDLER_COMPLETE_PUBREC_SENT -> {
                    // Handler already ran — resend PUBREC to nudge broker.
                    record.packet.expectedResponse()?.let { write(it) }
                }
            }
        }
    }

    suspend fun processIncomingMessages() {
        readChannel.collect { packet ->
            when (packet) {
                is IDisconnectNotification -> {}
                is IPingRequest -> write(packet.controlPacketFactory.pingResponse())
                is IPingResponse -> pingResponseCount++
                is IPublishAcknowledgment -> {
                    persistence.ackPub(broker, packet)
                    qos1States.remove(packet.packetIdentifier)?.value = QoS1State.Acknowledged(packet)
                }
                is PublishMessage -> {
                    val replyMessage = packet.expectedResponse()
                    if (replyMessage == null) {
                        // QoS 0: no persistence, no ack — just dispatch.
                        if (!publishDispatcher.isEmpty()) {
                            publishDispatcher.dispatch(packet)
                        }
                    } else {
                        // QoS 1 / QoS 2: persist payload BEFORE dispatch; ack only on handler success.
                        persistence.persistIncomingPublish(broker, packet)
                        try {
                            if (!publishDispatcher.isEmpty()) {
                                publishDispatcher.dispatch(packet)
                            }
                            persistence.incomingHandlerComplete(broker, packet.packetIdentifier)
                            write(replyMessage)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") _: Throwable,
                        ) {
                            // Handler threw — row stays on disk for redispatch on next reconnect.
                            // Do NOT write PUBACK/PUBREC.
                        }
                    }
                }
                is IPublishReceived -> {
                    persistence.updatePublishState(broker, packet.packetIdentifier, Persistence.STATE_PUBREC_RECEIVED)
                    qos2States[packet.packetIdentifier]?.value = QoS2State.Received
                    val pubRel = packet.expectedResponse()
                    persistence.ackPubReceivedQueuePubRelease(broker, packet, pubRel)
                    write(pubRel)
                    persistence.updatePublishState(broker, packet.packetIdentifier, Persistence.STATE_PUBREL_SENT)
                    qos2States[packet.packetIdentifier]?.value = QoS2State.Released
                }
                is IPublishRelease -> {
                    val pubComp = packet.expectedResponse()
                    persistence.ackPubRelease(broker, packet, pubComp)
                    write(pubComp)
                    persistence.onPubCompWritten(broker, pubComp)
                }
                is IPublishComplete -> {
                    persistence.ackPubComplete(broker, packet)
                    qos2States.remove(packet.packetIdentifier)?.value = QoS2State.Complete(packet)
                }
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
        val checkInterval = interval / 2
        while (currentCoroutineContext().isActive) {
            delay(checkInterval)
            if ((TimeSource.Monotonic.markNow() - lastActivityMark) >= checkInterval) {
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
