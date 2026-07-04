package com.ditchoom.mqtt5.controlpacket.fuzz

import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectProperties
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.mqtt5.controlpacket.PublishProperties
import com.ditchoom.mqtt5.controlpacket.SubAckReasonCodeV5
import com.ditchoom.mqtt5.controlpacket.SubscriptionV5Entry
import com.ditchoom.mqtt5.controlpacket.TopicFilterV5Entry
import com.ditchoom.mqtt5.controlpacket.UnsubAckReasonCodeV5
import com.ditchoom.mqtt5.controlpacket.asUtf8String
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
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

// At most ONE short pair: the buffer-codec processor's fixed 64-byte property scratch
// buffer silently truncates larger property lists on encode — see
// UpstreamCodecBugRegressionTest. Keep every generated property list under 64 encoded
// bytes until the buffer fix lands, then lift this cap.
private fun randomUserProperties(rng: Random): List<Pair<String, String>> =
    List(rng.nextInt(2)) { randomAlnum(rng, 1, 8) to randomAlnum(rng, 0, 12) }

private fun <T> Random.pick(vararg options: T): T = options[nextInt(options.size)]

private fun randomSubscribeProps(rng: Random): List<MqttProperty> =
    randomUserProperties(rng).map { (k, v) -> UserProperty(key = k, value = v) }

// Valid CONNACK reason codes per §3.2.2.2 (subset; enough to exercise the reason-code path).
private fun randomConnAckReason(rng: Random): ReasonCode =
    rng.pick(
        ReasonCode.SUCCESS,
        ReasonCode.UNSPECIFIED_ERROR,
        ReasonCode.MALFORMED_PACKET,
        ReasonCode.PROTOCOL_ERROR,
        ReasonCode.NOT_AUTHORIZED,
        ReasonCode.SERVER_BUSY,
    )

private fun randomPubAckReason(rng: Random): ReasonCode =
    rng.pick(
        ReasonCode.SUCCESS,
        ReasonCode.NO_MATCHING_SUBSCRIBERS,
        ReasonCode.UNSPECIFIED_ERROR,
        ReasonCode.NOT_AUTHORIZED,
        ReasonCode.TOPIC_NAME_INVALID,
        ReasonCode.QUOTA_EXCEEDED,
        ReasonCode.PAYLOAD_FORMAT_INVALID,
    )

private fun randomPubRelReason(rng: Random): ReasonCode = rng.pick(ReasonCode.SUCCESS, ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)

/**
 * Generates a random spec-valid v5 control packet, uniformly across wire types 1–15.
 */
internal fun randomValidV5Packet(rng: Random): ControlPacketV5<OpaquePublishPayload> =
    when (rng.nextInt(1, 16)) {
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
                cleanStart = rng.nextBoolean(),
                userName = if (rng.nextBoolean()) randomAlnum(rng, 1, 12) else null,
                password = if (rng.nextBoolean()) randomAlnum(rng, 1, 12) else null,
                will = will,
                props =
                    ConnectProperties(
                        sessionExpiryIntervalSeconds =
                            if (rng.nextBoolean()) rng.nextLong(0, UInt.MAX_VALUE.toLong()).toULong() else null,
                        receiveMaximum = if (rng.nextBoolean()) rng.nextInt(1, 65_536) else null,
                        topicAliasMaximum = if (rng.nextBoolean()) rng.nextInt(0, 65_536) else null,
                        userProperty = randomUserProperties(rng),
                    ),
            )
        }
        2 -> {
            val reason = randomConnAckReason(rng)
            ControlPacketV5.ConnAck(
                sessionPresent = reason == ReasonCode.SUCCESS && rng.nextBoolean(),
                connectReason = reason,
            )
        }
        3 -> {
            val qos = randomQos(rng)
            ControlPacketV5.Publish.ofRaw(
                topic = TopicName.fromOrThrow(randomTopicName(rng)),
                qos = qos,
                payload = bytesToReadBuffer(randomAlnum(rng, 0, 32).encodeToByteArray()),
                dup = qos != QualityOfService.AT_MOST_ONCE && rng.nextBoolean(),
                retain = rng.nextBoolean(),
                packetIdentifier =
                    if (qos == QualityOfService.AT_MOST_ONCE) NO_PACKET_ID else randomPacketId(rng).toInt(),
                properties =
                    PublishProperties(
                        messageExpiryInterval = if (rng.nextBoolean()) rng.nextLong(0, 100_000) else null,
                        topicAlias = if (rng.nextBoolean()) rng.nextInt(1, 65_536) else null,
                        userProperty = randomUserProperties(rng),
                        subscriptionIdentifier =
                            if (rng.nextBoolean()) setOf(rng.nextLong(1, 268_435_455)) else emptySet(),
                        contentType = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                    ),
            )
        }
        4 ->
            ControlPacketV5.PubAck(
                randomPacketId(rng).toInt(),
                randomPubAckReason(rng),
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
        5 ->
            ControlPacketV5.PubRec(
                randomPacketId(rng).toInt(),
                randomPubAckReason(rng),
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
        6 ->
            ControlPacketV5.PubRel(
                randomPacketId(rng).toInt(),
                randomPubRelReason(rng),
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
        7 ->
            ControlPacketV5.PubComp(
                randomPacketId(rng).toInt(),
                randomPubRelReason(rng),
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
        8 ->
            ControlPacketV5.Subscribe(
                packetId = randomPacketId(rng),
                properties = randomSubscribeProps(rng),
                subscriptionEntries =
                    List(1 + rng.nextInt(8)) {
                        val qos = rng.nextInt(3)
                        val noLocal = if (rng.nextBoolean()) 1 shl 2 else 0
                        val retainAsPublished = if (rng.nextBoolean()) 1 shl 3 else 0
                        val retainHandling = rng.nextInt(3) shl 4
                        SubscriptionV5Entry(
                            randomTopicFilter(rng),
                            (qos or noLocal or retainAsPublished or retainHandling).toUByte(),
                        )
                    },
            )
        9 ->
            ControlPacketV5.SubAck(
                packetId = randomPacketId(rng),
                properties = randomSubscribeProps(rng),
                reasonCodeEntries =
                    List(1 + rng.nextInt(8)) {
                        SubAckReasonCodeV5(
                            rng
                                .pick(
                                    ReasonCode.GRANTED_QOS_0,
                                    ReasonCode.GRANTED_QOS_1,
                                    ReasonCode.GRANTED_QOS_2,
                                    ReasonCode.UNSPECIFIED_ERROR,
                                    ReasonCode.NOT_AUTHORIZED,
                                    ReasonCode.TOPIC_FILTER_INVALID,
                                ).byte,
                        )
                    },
            )
        10 ->
            ControlPacketV5.Unsubscribe(
                packetId = randomPacketId(rng),
                properties = randomSubscribeProps(rng),
                topicEntries = List(1 + rng.nextInt(8)) { TopicFilterV5Entry(randomTopicFilter(rng)) },
            )
        11 ->
            ControlPacketV5.UnsubAck(
                packetId = randomPacketId(rng),
                properties = randomSubscribeProps(rng),
                reasonCodeEntries =
                    List(1 + rng.nextInt(8)) {
                        UnsubAckReasonCodeV5(
                            rng
                                .pick(
                                    ReasonCode.SUCCESS,
                                    ReasonCode.NO_SUBSCRIPTIONS_EXISTED,
                                    ReasonCode.UNSPECIFIED_ERROR,
                                    ReasonCode.NOT_AUTHORIZED,
                                    ReasonCode.TOPIC_FILTER_INVALID,
                                ).byte,
                        )
                    },
            )
        12 -> ControlPacketV5.PingReq()
        13 -> ControlPacketV5.PingResp()
        14 ->
            ControlPacketV5.Disconnect(
                reasonCode =
                    rng.pick(
                        ReasonCode.NORMAL_DISCONNECTION,
                        ReasonCode.DISCONNECT_WITH_WILL_MESSAGE,
                        ReasonCode.UNSPECIFIED_ERROR,
                        ReasonCode.SERVER_SHUTTING_DOWN,
                        ReasonCode.KEEP_ALIVE_TIMEOUT,
                    ),
                sessionExpiryIntervalSeconds =
                    if (rng.nextBoolean()) rng.nextLong(0, UInt.MAX_VALUE.toLong()).toULong() else null,
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
        else ->
            ControlPacketV5.Auth(
                reasonCode =
                    rng.pick(
                        ReasonCode.SUCCESS,
                        ReasonCode.CONTINUE_AUTHENTICATION,
                        ReasonCode.REAUTHENTICATE,
                    ),
                reasonString = if (rng.nextBoolean()) randomAlnum(rng, 1, 16) else null,
                userProperty = randomUserProperties(rng),
            )
    }

/**
 * Round-trip equality. Data-class equality suffices for every variant except the two that
 * carry owned-bytes handles (PUBLISH payload; CONNECT will payload + password), whose
 * handle equality is not structural — those compare byte content instead. Generated
 * payloads are UTF-8-safe by construction, so string comparison is byte-exact.
 */
internal fun assertV5PacketsEqual(
    expected: ControlPacketV5<OpaquePublishPayload>,
    actual: ControlPacketV5<OpaquePublishPayload>,
) {
    when {
        expected is ControlPacketV5.Publish<*> && actual is ControlPacketV5.Publish<*> -> {
            assertEquals(expected.header, actual.header)
            assertEquals(expected.topicName, actual.topicName)
            assertEquals(expected.packetId, actual.packetId)
            assertEquals(expected.properties, actual.properties)
            assertEquals(
                (expected.payload as OpaquePublishPayload).asUtf8String(),
                (actual.payload as OpaquePublishPayload).asUtf8String(),
            )
        }
        expected is ControlPacketV5.Connect && actual is ControlPacketV5.Connect -> {
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
