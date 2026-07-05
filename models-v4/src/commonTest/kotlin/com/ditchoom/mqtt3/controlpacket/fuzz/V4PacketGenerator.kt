package com.ditchoom.mqtt3.controlpacket.fuzz

import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt3.controlpacket.DisconnectNotification
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PingResponse
import com.ditchoom.mqtt3.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt3.controlpacket.PublishComplete
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt3.controlpacket.PublishReceived
import com.ditchoom.mqtt3.controlpacket.PublishRelease
import com.ditchoom.mqtt3.controlpacket.SubAckReturnCode
import com.ditchoom.mqtt3.controlpacket.SubscribeAcknowledgement
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.SubscriptionEntry
import com.ditchoom.mqtt3.controlpacket.TopicFilterEntry
import com.ditchoom.mqtt3.controlpacket.UnsubscribeAcknowledgment
import com.ditchoom.mqtt3.controlpacket.UnsubscribeRequest
import com.ditchoom.mqtt3.controlpacket.asUtf8String
import kotlin.random.Random
import kotlin.test.assertEquals

private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

internal fun randomAlnum(
    rng: Random,
    minLength: Int,
    maxLength: Int,
): String {
    val length = rng.nextInt(minLength, maxLength + 1)
    return buildString(length) { repeat(length) { append(ALNUM[rng.nextInt(ALNUM.length)]) } }
}

internal fun randomTopicName(rng: Random): String = List(1 + rng.nextInt(3)) { randomAlnum(rng, 1, 8) }.joinToString("/")

internal fun randomTopicFilter(rng: Random): String =
    when (rng.nextInt(4)) {
        0 -> randomTopicName(rng) + "/#"
        1 -> randomAlnum(rng, 1, 8) + "/+/" + randomAlnum(rng, 1, 8)
        else -> randomTopicName(rng)
    }

internal fun randomQos(rng: Random): QualityOfService =
    when (rng.nextInt(3)) {
        0 -> QualityOfService.AT_MOST_ONCE
        1 -> QualityOfService.AT_LEAST_ONCE
        else -> QualityOfService.EXACTLY_ONCE
    }

private fun randomPacketId(rng: Random): UShort = rng.nextInt(1, 65_536).toUShort()

/**
 * Generates a random spec-valid v4 control packet, uniformly across wire types 1–14
 * (type 0 is the reserved non-packet; the generator only produces packets a peer could
 * legitimately put on the wire).
 */
internal fun randomValidV4Packet(rng: Random): ControlPacketV4<OpaquePublishPayload> =
    when (rng.nextInt(1, 15)) {
        1 -> {
            val will =
                if (rng.nextBoolean()) {
                    WillConfig.Enabled(
                        TopicName.fromOrThrow(randomTopicName(rng)),
                        bytesToReadBuffer(randomAlnum(rng, 0, 16).encodeToByteArray()),
                        randomQos(rng),
                        rng.nextBoolean(),
                    )
                } else {
                    WillConfig.Disabled
                }
            ConnectionRequest(
                clientId = randomAlnum(rng, 0, 23),
                keepAliveSeconds = rng.nextInt(0, 65_536),
                cleanSession = rng.nextBoolean(),
                userName = if (rng.nextBoolean()) randomAlnum(rng, 1, 12) else null,
                password = if (rng.nextBoolean()) randomAlnum(rng, 1, 12) else null,
                will = will,
            )
        }
        2 ->
            ConnectionAcknowledgment(
                rng.nextBoolean(),
                ConnectionAcknowledgment.VariableHeader.ReturnCode.entries[rng.nextInt(6)],
            )
        3 -> {
            val qos = randomQos(rng)
            PublishMessageV4.ofRaw(
                topic = TopicName.fromOrThrow(randomTopicName(rng)),
                qos = qos,
                payload = bytesToReadBuffer(randomAlnum(rng, 0, 32).encodeToByteArray()),
                dup = qos != QualityOfService.AT_MOST_ONCE && rng.nextBoolean(),
                retain = rng.nextBoolean(),
                packetIdentifier =
                    if (qos == QualityOfService.AT_MOST_ONCE) NO_PACKET_ID else randomPacketId(rng).toInt(),
            )
        }
        4 -> PublishAcknowledgment(randomPacketId(rng))
        5 -> PublishReceived(randomPacketId(rng))
        6 -> PublishRelease(randomPacketId(rng))
        7 -> PublishComplete(randomPacketId(rng))
        8 ->
            SubscribeRequest(
                randomPacketId(rng),
                List(1 + rng.nextInt(8)) {
                    SubscriptionEntry(randomTopicFilter(rng), rng.nextInt(3).toUByte())
                },
            )
        9 ->
            SubscribeAcknowledgement(
                randomPacketId(rng),
                List(1 + rng.nextInt(8)) {
                    when (rng.nextInt(4)) {
                        0 -> SubAckReturnCode.SuccessMaximumQoS0
                        1 -> SubAckReturnCode.SuccessMaximumQoS1
                        2 -> SubAckReturnCode.SuccessMaximumQoS2
                        else -> SubAckReturnCode.Failure
                    }
                },
            )
        10 ->
            UnsubscribeRequest(
                randomPacketId(rng),
                List(1 + rng.nextInt(8)) { TopicFilterEntry(randomTopicFilter(rng)) },
            )
        11 -> UnsubscribeAcknowledgment(randomPacketId(rng))
        12 -> PingRequest()
        13 -> PingResponse()
        else -> DisconnectNotification()
    }

/**
 * Round-trip equality. Data-class equality suffices for every variant except the two that
 * carry owned-bytes handles (PUBLISH payload; CONNECT will payload + password), whose
 * handle equality is not structural — those compare byte content instead. Generated
 * payloads are UTF-8-safe by construction, so string comparison is byte-exact.
 */
internal fun assertV4PacketsEqual(
    expected: ControlPacketV4<OpaquePublishPayload>,
    actual: ControlPacketV4<OpaquePublishPayload>,
) {
    when {
        expected is PublishMessageV4<*> && actual is PublishMessageV4<*> -> {
            assertEquals(expected.header, actual.header)
            assertEquals(expected.topicName, actual.topicName)
            assertEquals(expected.packetId, actual.packetId)
            assertEquals(
                (expected.payload as OpaquePublishPayload).asUtf8String(),
                (actual.payload as OpaquePublishPayload).asUtf8String(),
            )
        }
        expected is ConnectionRequest && actual is ConnectionRequest -> {
            assertEquals(
                expected.copy(willPayloadValue = null, passwordValue = null),
                actual.copy(willPayloadValue = null, passwordValue = null),
            )
            assertEquals(expected.willPayloadValue?.asUtf8String(), actual.willPayloadValue?.asUtf8String())
            assertEquals(expected.password, actual.password)
        }
        else -> assertEquals(expected, actual)
    }
}
