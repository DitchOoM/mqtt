package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.mqtt.controlpacket.format.ReasonCode.ADMINISTRATIVE_ACTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BAD_AUTHENTICATION_METHOD
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BAD_USER_NAME_OR_PASSWORD
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BANNED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CLIENT_IDENTIFIER_NOT_VALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONNECTION_RATE_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONTINUE_AUTHENTICATION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.DISCONNECT_WITH_WILL_MESSAGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.KEEP_ALIVE_TIMEOUT
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MALFORMED_PACKET
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MAXIMUM_CONNECTION_TIME
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MESSAGE_RATE_TOO_HIGH
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_MATCHING_SUBSCRIBERS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_SUBSCRIPTIONS_EXISTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_TOO_LARGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PROTOCOL_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QOS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.REAUTHENTICATE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RETAIN_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_BUSY
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_MOVED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_SHUTTING_DOWN
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_UNAVAILABLE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SESSION_TAKE_OVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_ALIAS_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSUPPORTED_PROTOCOL_VERSION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.USE_ANOTHER_SERVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
import kotlin.test.Test
import kotlin.test.assertEquals

class ReasonCodeTests {
    @Test
    fun Success() = assertEquals(SUCCESS.byte, 0x00.toUByte())

    @Test
    fun NormalDisconnection() = assertEquals(NORMAL_DISCONNECTION.byte, 0x00.toUByte())

    @Test
    fun GrantedQos0() = assertEquals(GRANTED_QOS_0.byte, 0x00.toUByte())

    @Test
    fun GrantedQos1() = assertEquals(GRANTED_QOS_1.byte, 0x01.toUByte())

    @Test
    fun GrantedQos2() = assertEquals(GRANTED_QOS_2.byte, 0x02.toUByte())

    @Test
    fun DisconnectwithWillMessage() =
        assertEquals(DISCONNECT_WITH_WILL_MESSAGE.byte, 0x04.toUByte())

    @Test
    fun NoMatchingSubscribers() = assertEquals(NO_MATCHING_SUBSCRIBERS.byte, 0x10.toUByte())

    @Test
    fun NoSubscriptionExisted() = assertEquals(NO_SUBSCRIPTIONS_EXISTED.byte, 0x11.toUByte())

    @Test
    fun ContinueAuthentication() = assertEquals(CONTINUE_AUTHENTICATION.byte, 0x18.toUByte())

    @Test
    fun Reauthenticate() = assertEquals(REAUTHENTICATE.byte, 0x19.toUByte())

    @Test
    fun UnspecifiedError() = assertEquals(UNSPECIFIED_ERROR.byte, 0x80.toUByte())

    @Test
    fun MalformedPacket() = assertEquals(MALFORMED_PACKET.byte, 0x81.toUByte())

    @Test
    fun ProtocolError() = assertEquals(PROTOCOL_ERROR.byte, 0x82.toUByte())

    @Test
    fun ImplementationSpecificError() =
        assertEquals(IMPLEMENTATION_SPECIFIC_ERROR.byte, 0x83.toUByte())

    @Test
    fun UnsupportedProtocolVersion() =
        assertEquals(UNSUPPORTED_PROTOCOL_VERSION.byte, 0x84.toUByte())

    @Test
    fun ClientIdentifierNotValid() = assertEquals(CLIENT_IDENTIFIER_NOT_VALID.byte, 0x85.toUByte())

    @Test
    fun BadUserNameOrPassword() = assertEquals(BAD_USER_NAME_OR_PASSWORD.byte, 0x86.toUByte())

    @Test
    fun NotAuthorized() = assertEquals(NOT_AUTHORIZED.byte, 0x87.toUByte())

    @Test
    fun ServerUnavailable() = assertEquals(SERVER_UNAVAILABLE.byte, 0x88.toUByte())

    @Test
    fun ServerBusy() = assertEquals(SERVER_BUSY.byte, 0x89.toUByte())

    @Test
    fun Banned() = assertEquals(BANNED.byte, 0x8A.toUByte())

    @Test
    fun ServerShuttingDown() = assertEquals(SERVER_SHUTTING_DOWN.byte, 0x8B.toUByte())

    @Test
    fun BadAuthenticationMethod() = assertEquals(BAD_AUTHENTICATION_METHOD.byte, 0x8C.toUByte())

    @Test
    fun KeepAliveTimeout() = assertEquals(KEEP_ALIVE_TIMEOUT.byte, 0x8D.toUByte())

    @Test
    fun SessionTakenOver() = assertEquals(SESSION_TAKE_OVER.byte, 0x8E.toUByte())

    @Test
    fun TopicFilterInvalid() = assertEquals(TOPIC_FILTER_INVALID.byte, 0x8F.toUByte())

    @Test
    fun TopicNameInvalid() = assertEquals(TOPIC_NAME_INVALID.byte, 0x90.toUByte())

    @Test
    fun PacketIdentifierInUse() = assertEquals(PACKET_IDENTIFIER_IN_USE.byte, 0x91.toUByte())

    @Test
    fun PacketIdentifierNotFound() = assertEquals(PACKET_IDENTIFIER_NOT_FOUND.byte, 0x92.toUByte())

    @Test
    fun ReceiveMaximumExceeded() = assertEquals(RECEIVE_MAXIMUM_EXCEEDED.byte, 0x93.toUByte())

    @Test
    fun TopicAliasInvalid() = assertEquals(TOPIC_ALIAS_INVALID.byte, 0x94.toUByte())

    @Test
    fun PacketTooLarge() = assertEquals(PACKET_TOO_LARGE.byte, 0x95.toUByte())

    @Test
    fun MessageRateTooHigh() = assertEquals(MESSAGE_RATE_TOO_HIGH.byte, 0x96.toUByte())

    @Test
    fun QuotaExceeded() = assertEquals(QUOTA_EXCEEDED.byte, 0x97.toUByte())

    @Test
    fun AdministrativeAction() = assertEquals(ADMINISTRATIVE_ACTION.byte, 0x98.toUByte())

    @Test
    fun PayloadFormatInvalid() = assertEquals(PAYLOAD_FORMAT_INVALID.byte, 0x99.toUByte())

    @Test
    fun RetainNotSupported() = assertEquals(RETAIN_NOT_SUPPORTED.byte, 0x9A.toUByte())

    @Test
    fun QoSNotSupported() = assertEquals(QOS_NOT_SUPPORTED.byte, 0x9B.toUByte())

    @Test
    fun UseAnotherServer() = assertEquals(USE_ANOTHER_SERVER.byte, 0x9C.toUByte())

    @Test
    fun ServerMoved() = assertEquals(SERVER_MOVED.byte, 0x9D.toUByte())

    @Test
    fun SharedSubscriptionsNotSupported() =
        assertEquals(SHARED_SUBSCRIPTIONS_NOT_SUPPORTED.byte, 0x9E.toUByte())

    @Test
    fun ConnectionRateExceeded() = assertEquals(CONNECTION_RATE_EXCEEDED.byte, 0x9F.toUByte())

    @Test
    fun MaximumConnectTime() = assertEquals(MAXIMUM_CONNECTION_TIME.byte, 0xA0.toUByte())

    @Test
    fun SubscriptionIdentifierNotSupported() =
        assertEquals(SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED.byte, 0xA1.toUByte())

    @Test
    fun WildcardSubscriptionsNotSupported() =
        assertEquals(WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.byte, 0xA2.toUByte())
}
