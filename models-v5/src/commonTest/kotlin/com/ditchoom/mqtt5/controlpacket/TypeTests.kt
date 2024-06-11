package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.BIDIRECTIONAL
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.CLIENT_TO_SERVER
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow.SERVER_TO_CLIENT
import com.ditchoom.mqtt5.controlpacket.PublishMessage.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
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
        val variable = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name))
        assertEquals(
            3,
            PublishMessage(variable = variable).controlPacketValue,
            controlPacketSpectMatchError,
        )
    }

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBACK() =
        assertEquals(
            4,
            PublishAcknowledgment(PublishAcknowledgment.VariableHeader(packetIdentifier)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBREC() =
        assertEquals(
            5,
            PublishReceived(PublishReceived.VariableHeader(packetIdentifier)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBREL() =
        assertEquals(
            6,
            PublishRelease(PublishRelease.VariableHeader(packetIdentifier)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPUBCOMP() =
        assertEquals(
            7,
            PublishComplete(PublishComplete.VariableHeader(packetIdentifier)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForSUBSCRIBE() =
        assertEquals(
            8,
            SubscribeRequest(
                SubscribeRequest.VariableHeader(packetIdentifier),
                setOf(Subscription(Topic.fromOrThrow("yolo", Topic.Type.Filter))),
            ).controlPacketValue,
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
            UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(packetIdentifier),
                setOf(Topic.fromOrThrow("yolo", Topic.Type.Filter)),
            ).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForUNSUBACK() =
        assertEquals(
            11,
            UnsubscribeAcknowledgment(
                UnsubscribeAcknowledgment.VariableHeader(packetIdentifier),
                listOf(GRANTED_QOS_1),
            ).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForPINGREQ() = assertEquals(12, PingRequest.controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForPINGRESP() = assertEquals(13, PingResponse.controlPacketValue, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeValueMatchesSpecForDISCONNECT() =
        assertEquals(
            14,
            DisconnectNotification(DisconnectNotification.VariableHeader(NORMAL_DISCONNECTION)).controlPacketValue,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeValueMatchesSpecForAUTH() =
        assertEquals(
            15,
            AuthenticationExchange(
                AuthenticationExchange.VariableHeader(
                    SUCCESS,
                    AuthenticationExchange.VariableHeader.Properties(
                        Authentication(
                            "yolo",
                            PlatformBuffer.allocate(0),
                        ),
                    ),
                ),
            ).controlPacketValue,
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
        val variable = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name))
        assertEquals(
            BIDIRECTIONAL,
            PublishMessage(variable = variable).direction,
            controlPacketSpectMatchError,
        )
    }

    @Test
    fun controlPacketTypeDirectionOfFlowPUBACK() =
        assertEquals(
            BIDIRECTIONAL,
            PublishAcknowledgment(PublishAcknowledgment.VariableHeader(packetIdentifier)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBREC() =
        assertEquals(
            BIDIRECTIONAL,
            PublishReceived(PublishReceived.VariableHeader(packetIdentifier)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBREL() =
        assertEquals(
            BIDIRECTIONAL,
            PublishRelease(PublishRelease.VariableHeader(packetIdentifier)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPUBCOMP() =
        assertEquals(
            BIDIRECTIONAL,
            PublishComplete(PublishComplete.VariableHeader(packetIdentifier)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowSUBSCRIBE() =
        assertEquals(
            CLIENT_TO_SERVER,
            SubscribeRequest(
                SubscribeRequest.VariableHeader(packetIdentifier),
                setOf(Subscription(Topic.fromOrThrow("yolo", Topic.Type.Filter))),
            ).direction,
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
            UnsubscribeRequest(
                UnsubscribeRequest.VariableHeader(packetIdentifier),
                setOf(Topic.fromOrThrow("yolo", Topic.Type.Filter)),
            ).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowUNSUBACK() =
        assertEquals(
            SERVER_TO_CLIENT,
            UnsubscribeAcknowledgment(
                UnsubscribeAcknowledgment.VariableHeader(packetIdentifier),
                listOf(GRANTED_QOS_1),
            ).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowPINGREQ() = assertEquals(CLIENT_TO_SERVER, PingRequest.direction, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeDirectionOfFlowPINGRESP() = assertEquals(SERVER_TO_CLIENT, PingResponse.direction, controlPacketSpectMatchError)

    @Test
    fun controlPacketTypeDirectionOfFlowDISCONNECT() =
        assertEquals(
            BIDIRECTIONAL,
            DisconnectNotification(DisconnectNotification.VariableHeader(NORMAL_DISCONNECTION)).direction,
            controlPacketSpectMatchError,
        )

    @Test
    fun controlPacketTypeDirectionOfFlowAUTH() =
        assertEquals(
            BIDIRECTIONAL,
            AuthenticationExchange(
                AuthenticationExchange.VariableHeader(
                    SUCCESS,
                    AuthenticationExchange.VariableHeader.Properties(
                        Authentication(
                            "yolo",
                            PlatformBuffer.allocate(0),
                        ),
                    ),
                ),
            ).direction,
            controlPacketSpectMatchError,
        )
}
