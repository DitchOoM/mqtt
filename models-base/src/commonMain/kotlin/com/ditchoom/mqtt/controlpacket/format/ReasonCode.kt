package com.ditchoom.mqtt.controlpacket.format

/**
 * A Reason Code is a one byte unsigned controlPacketValue that indicates the result of an operation. Reason Codes less than 0x80
 * indicate successful completion of an operation. The normal Reason Code for success is 0. Reason Code values of 0x80
 * or greater indicate failure.
 * The ConnectionAcknowledgment, PublishAcknowledgment, PublishReceived, PublishRelease, PublishCompleteNotification and AuthenticationExchange Control Packets have a single Reason Code as part
 * of the Variable Header. The SubscribeAcknowledgement and UnsubscribeAcknowledgment packets contain a list of one or more Reason Codes in the Payload.
 */
enum class ReasonCode(val byte: UByte) {
    SUCCESS(0x00.toUByte()),
    NORMAL_DISCONNECTION(0x00.toUByte()),
    GRANTED_QOS_0(0x00.toUByte()),
    GRANTED_QOS_1(0x01.toUByte()),
    GRANTED_QOS_2(0x02.toUByte()),
    DISCONNECT_WITH_WILL_MESSAGE(0x04.toUByte()),
    NO_MATCHING_SUBSCRIBERS(0x10.toUByte()),
    NO_SUBSCRIPTIONS_EXISTED(0x11.toUByte()),
    CONTINUE_AUTHENTICATION(0x18.toUByte()),
    REAUTHENTICATE(0x19.toUByte()),
    UNSPECIFIED_ERROR(0x80.toUByte()),
    MALFORMED_PACKET(0x81.toUByte()),
    PROTOCOL_ERROR(0x82.toUByte()),
    IMPLEMENTATION_SPECIFIC_ERROR(0x83.toUByte()),
    UNSUPPORTED_PROTOCOL_VERSION(0x84.toUByte()),
    CLIENT_IDENTIFIER_NOT_VALID(0x85.toUByte()),
    BAD_USER_NAME_OR_PASSWORD(0x86.toUByte()),
    NOT_AUTHORIZED(0x87.toUByte()),
    SERVER_UNAVAILABLE(0x88.toUByte()),
    SERVER_BUSY(0x89.toUByte()),
    BANNED(0x8A.toUByte()),
    SERVER_SHUTTING_DOWN(0x8B.toUByte()),
    BAD_AUTHENTICATION_METHOD(0x8C.toUByte()),
    KEEP_ALIVE_TIMEOUT(0x8D.toUByte()),
    SESSION_TAKE_OVER(0x8E.toUByte()),
    TOPIC_FILTER_INVALID(0x8F.toUByte()),
    TOPIC_NAME_INVALID(0x90.toUByte()),

    /**
     * the response to this is either to try to fix the state, or to reset the Session state by connecting using Clean
     * Start set to 1, or to decide if the Client or Server implementations are defective.
     */
    PACKET_IDENTIFIER_IN_USE(0x91.toUByte()),
    PACKET_IDENTIFIER_NOT_FOUND(0x92.toUByte()),
    RECEIVE_MAXIMUM_EXCEEDED(0x93.toUByte()),
    TOPIC_ALIAS_INVALID(0x94.toUByte()),
    PACKET_TOO_LARGE(0x95.toUByte()),
    MESSAGE_RATE_TOO_HIGH(0x96.toUByte()),
    QUOTA_EXCEEDED(0x97.toUByte()),
    ADMINISTRATIVE_ACTION(0x98.toUByte()),
    PAYLOAD_FORMAT_INVALID(0x99.toUByte()),
    RETAIN_NOT_SUPPORTED(0x9A.toUByte()),
    QOS_NOT_SUPPORTED(0x9B.toUByte()),
    USE_ANOTHER_SERVER(0x9C.toUByte()),
    SERVER_MOVED(0x9D.toUByte()),
    SHARED_SUBSCRIPTIONS_NOT_SUPPORTED(0x9E.toUByte()),
    CONNECTION_RATE_EXCEEDED(0x9F.toUByte()),
    MAXIMUM_CONNECTION_TIME(0xA0.toUByte()),
    SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED(0xA1.toUByte()),
    WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED(0xA2.toUByte()),
}
