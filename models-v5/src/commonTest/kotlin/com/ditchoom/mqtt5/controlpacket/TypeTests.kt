package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.BIDIRECTIONAL
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.CLIENT_TO_SERVER
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.SERVER_TO_CLIENT
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeTests {
    private val packetIdentifier = 0

    private val controlPacketSpectMatchError =
        "doesn't match the spec from " +
            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477322"

    // Control packet types controlPacketValue matching spec
    @Test
    fun controlPacketTypeValueMatchesSpecForCONNECT() =
        assertEquals(1, ConnectionRequest().controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForCONNACK() =
        assertEquals(2, ConnectionAcknowledgment().controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBLISH() {
        assertEquals(
            3,
            ControlPacketV5.Publish.ofRaw(topic = TopicName.fromOrThrow("t")).controlPacketValue,
            controlPacketSpectMatchError,
        )
    }

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBACK() =
        assertEquals(
            4,
            PublishAcknowledgment(packetIdentifier).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBREC() =
        assertEquals(
            5,
            PublishReceived(packetIdentifier).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBREL() =
        assertEquals(
            6,
            PublishRelease(packetIdentifier).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBCOMP() =
        assertEquals(
            7,
            PublishComplete(packetIdentifier).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForSUBSCRIBE() =
        assertEquals(
            8,
SubscribeRequest(packetIdentifier.toUShort(), "yolo", QualityOfService.AT_LEAST_ONCE).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForSUBACK() =
        assertEquals(
            9,
            SubscribeAcknowledgement(packetIdentifier.toUShort(), GRANTED_QOS_0).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForUNSUBSCRIBE() =
        assertEquals(
            10,
UnsubscribeRequest("yolo").controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForUNSUBACK() =
        assertEquals(
            11,
UnsubscribeAcknowledgment(packetIdentifier, reasonCodes = listOf(SUCCESS)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPINGREQ() = assertEquals(12, PingRequest().controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForPINGRESP() = assertEquals(13, PingResponse().controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForDISCONNECT() =
        assertEquals(
            14,
            DisconnectNotification(reasonCode = NORMAL_DISCONNECTION).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForAUTH() =
        assertEquals(
            15,
            AuthenticationExchange().controlPacketValue,
            controlPacketSpectMatchError,
        )

    // Control packet types direction of flow matching spec
    @Test
    fun controlPacketTypeDirectionOfFlowCONNECT() =
        assertEquals(CLIENT_TO_SERVER, ConnectionRequest().direction, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeDirectionOfFlowCONNACK() =
        assertEquals(
            SERVER_TO_CLIENT,
            ConnectionAcknowledgment().direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBLISH() {
        assertEquals(
            BIDIRECTIONAL,
            ControlPacketV5.Publish.ofRaw(topic = TopicName.fromOrThrow("t")).direction,
            controlPacketSpectMatchError,
        )
    }

    @Test
    fun controlPacketTypeDirectionOfFlowPUBACK() =
        assertEquals(
            BIDIRECTIONAL,
            PublishAcknowledgment(packetIdentifier).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBREC() =
        assertEquals(
            BIDIRECTIONAL,
            PublishReceived(packetIdentifier).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBREL() =
        assertEquals(
            BIDIRECTIONAL,
            PublishRelease(packetIdentifier).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBCOMP() =
        assertEquals(
            BIDIRECTIONAL,
            PublishComplete(packetIdentifier).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowSUBSCRIBE() =
        assertEquals(
            CLIENT_TO_SERVER,
SubscribeRequest(packetIdentifier.toUShort(), "yolo", QualityOfService.AT_LEAST_ONCE).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowSUBACK() =
        assertEquals(
            SERVER_TO_CLIENT,
            SubscribeAcknowledgement(packetIdentifier.toUShort(), GRANTED_QOS_0).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowUNSUBSCRIBE() =
        assertEquals(
            CLIENT_TO_SERVER,
UnsubscribeRequest("yolo").direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowUNSUBACK() =
        assertEquals(
            SERVER_TO_CLIENT,
UnsubscribeAcknowledgment(packetIdentifier, reasonCodes = listOf(SUCCESS)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPINGREQ() = assertEquals(CLIENT_TO_SERVER, PingRequest().direction, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeDirectionOfFlowPINGRESP() = assertEquals(SERVER_TO_CLIENT, PingResponse().direction, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeDirectionOfFlowDISCONNECT() =
        assertEquals(
            BIDIRECTIONAL,
            DisconnectNotification(reasonCode = NORMAL_DISCONNECTION).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowAUTH() =
        assertEquals(
            BIDIRECTIONAL,
            AuthenticationExchange().direction,
            controlPacketSpectMatchError,
        )
}
