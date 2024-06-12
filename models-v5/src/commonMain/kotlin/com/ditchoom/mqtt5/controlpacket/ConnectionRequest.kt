package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import com.ditchoom.mqtt.controlpacket.utf8Length
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MaximumPacketSize
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ReceiveMaximum
import com.ditchoom.mqtt5.controlpacket.properties.RequestProblemInformation
import com.ditchoom.mqtt5.controlpacket.properties.RequestResponseInformation
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.TopicAliasMaximum
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

/**
 * 3.1 CONNECT – Connection Request
 * After a Network Connection is established by a Client to a Server, the first packet sent from the Client to the
 * Server MUST be a CONNECT packet [MQTT-3.1.0-1].
 *
 *
 * A Client can only send the CONNECT packet once over a Network Connection. The Server MUST process a second CONNECT
 * packet sent from a Client as a Protocol Error and close the Network Connection [MQTT-3.1.0-2]. Refer to section
 * 4.13 for information about handling errors.
 *
 *
 * The Payload contains one or more encoded fields. They specify a unique Client identifier for the Client, a
 * Will Topic, Will Payload, User Name and Password. All but the Client identifier can be omitted and their presence is
 * determined based on flags in the Variable Header.
 * <table border="1"><tr><td> cell 11 </td> <td> cell 21</td></tr><tr><td> cell 12 </td> <td> cell 22</td></tr></table>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#_Toc514847906">3.1
 * CONNECT – Connection Request</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#S4_13_Errors>
 *     Section 4.13 - Handling Errors</a>
 */

data class ConnectionRequest(
    /**
     * Some types of MQTT Control Packet contain a Variable Header component. It resides between the Fixed Header
     * and the Payload. The content of the Variable Header varies depending on the packet type. The Packet
     * Identifier field of Variable Header is common in several packet types.
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html#_Properties">MQTT
     * Properties section 2.2.2.</a>
     */
    val variableHeader: VariableHeader = VariableHeader(),
    val payload: Payload = Payload(),
) : ControlPacketV5(1, DirectionOfFlow.CLIENT_TO_SERVER), IConnectionRequest {
    constructor(
        clientId: String,
        keepAliveSeconds: Int = 3600,
        cleanStart: Boolean = false,
        userName: String? = null,
        password: String? = null,
        willTopic: String? = null,
        willPayload: PlatformBuffer? = null,
        willRetain: Boolean = false,
        willQos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        protocolName: String = "MQTT",
        protocolVersion: UByte = 5u,
        props: VariableHeader.Properties = VariableHeader.Properties(),
        willProperties: Payload.WillProperties? = null,
    ) : this(
        VariableHeader(
            protocolName = protocolName,
            protocolVersion = protocolVersion,
            cleanStart = cleanStart,
            keepAliveSeconds = keepAliveSeconds,
            hasUserName = userName != null,
            hasPassword = password != null,
            willRetain = willRetain,
            willFlag = willPayload != null && willTopic != null,
            willQos = willQos,
            properties = props,
        ),
        Payload(
            clientId,
            willProperties,
            if (willTopic == null) {
                null
            } else {
                Topic.fromOrThrow(willTopic, Topic.Type.Name)
            },
            willPayload,
            userName,
            password,
        ),
    )

    constructor(
        clientId: String,
        userName: String? = null,
        password: String? = null,
        willTopic: Topic? = null,
        willPayload: PlatformBuffer? = null,
        willRetain: Boolean = false,
        willQos: QualityOfService = QualityOfService.AT_MOST_ONCE,
    ) : this(
        VariableHeader(
            hasUserName = userName != null,
            hasPassword = password != null,
            willRetain = willRetain,
            willFlag = willPayload != null && willTopic != null,
            willQos = willQos,
        ),
        Payload(
            clientId,
            willTopic = willTopic,
            willPayload = willPayload,
            userName = userName,
            password = password,
        ),
    )

    override val clientIdentifier = payload.clientId
    override val keepAliveTimeoutSeconds: UShort = variableHeader.keepAliveSeconds.toUShort()

    override fun variableHeader(writeBuffer: WriteBuffer) = variableHeader.serialize(writeBuffer)

    override val cleanStart: Boolean = variableHeader.cleanStart
    override val userName = payload.userName
    override val protocolName = variableHeader.protocolName
    override val protocolVersion = variableHeader.protocolVersion.toInt()
    override val contentType: String? = payload.willProperties?.contentType
    override val correlationData: ReadBuffer? = payload.willProperties?.correlationData
    override val hasPassword: Boolean = variableHeader.hasPassword
    override val hasUserName: Boolean = variableHeader.hasUserName
    override val maxPacketSize: ULong = variableHeader.properties.maximumPacketSize ?: ULong.MAX_VALUE
    override val messageExpiryIntervalSeconds: Long? = payload.willProperties?.messageExpiryIntervalSeconds
    override val password: String? = payload.password
    override val payloadFormatIndicator: Boolean = payload.willProperties?.payloadFormatIndicator ?: false
    override val receiveMaximum: UShort = variableHeader.properties.receiveMaximum?.toUShort() ?: UShort.MAX_VALUE
    override val responseTopic: Topic? = payload.willProperties?.responseTopic
    override val sessionExpiryIntervalSeconds: ULong? = variableHeader.properties.sessionExpiryIntervalSeconds
    override val topicAliasMax: UShort? = variableHeader.properties.topicAliasMaximum?.toUShort()
    override val userProperty: List<Pair<String, String>> = variableHeader.properties.userProperty
    override val willDelayIntervalSeconds: Long = payload.willProperties?.willDelayIntervalSeconds ?: 0
    override val willFlag: Boolean = variableHeader.willFlag
    override val willPayload: ReadBuffer? = payload.willPayload
    override val willQos: QualityOfService = variableHeader.willQos
    override val willRetain: Boolean = variableHeader.willRetain
    override val willTopic: Topic? = payload.willTopic

    override fun payload(writeBuffer: WriteBuffer) = payload.serialize(writeBuffer)

    override fun validate(): MqttWarning? {
        if (variableHeader.willFlag &&
            (payload.willPayload == null || payload.willTopic == null || payload.willProperties == null)
        ) {
            return MqttWarning(
                "[MQTT-3.1.2-9]",
                "If the Will Flag is set to " +
                    "1, the Will QoS and Will Retain fields in the Connect Flags will be used by the Server, " +
                    "and the Will Properties, Will Topic and Will Message fields MUST be present in the Payload.",
            )
        }
        if (variableHeader.hasUserName && payload.userName == null) {
            return MqttWarning(
                "[MQTT-3.1.2-17]",
                "If the User Name Flag is set" +
                    " to 1, a User Name MUST be present in the Payload",
            )
        }
        if (!variableHeader.hasUserName && payload.userName != null) {
            return MqttWarning(
                "[MQTT-3.1.2-16]",
                "If the User Name Flag is set " +
                    "to 0, a User Name MUST NOT be present in the Payload",
            )
        }
        if (variableHeader.hasPassword && payload.password == null) {
            return MqttWarning(
                "[MQTT-3.1.2-19]",
                "If the Password Flag is set" +
                    " to 1, a Password MUST be present in the Payload",
            )
        }
        if (!variableHeader.hasPassword && payload.password != null) {
            return MqttWarning(
                "[MMQTT-3.1.2-18]",
                "If the Password Flag is set " +
                    "to 0, a Password MUST NOT be present in the Payload",
            )
        }
        return variableHeader.validateOrGetWarning()
    }

    override fun remainingLength() = (variableHeader.size() + payload.size())

    data class VariableHeader(
        /**
         * 3.1.2.1 Protocol Name
         *
         * The Protocol Name is a UTF-8 Encoded String that represents the protocol name “MQTT”, capitalized as
         * shown. The string, its offset and length will not be changed by future versions of the MQTT
         * specification.
         *
         * A Server which support multiple protocols uses the Protocol Name to determine whether the data is MQTT.
         * The protocol name MUST be the UTF-8 String "MQTT". If the Server does not want to accept the CONNECT, and
         * wishes to reveal that it is an MQTT Server it MAY send a CONNACK packet with Reason Code of 0x84
         * (Unsupported Protocol Version), and then it MUST close the Network Connection [MQTT-3.1.2-1].
         *
         * Non-normative comment
         *
         * Packet inspectors, such as firewalls, could use the Protocol Name to identify MQTT traffic.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477336">
         *     3.1.2.1 Protocol Name</a>
         */
        val protocolName: String = "MQTT",
        /**
         * 3.1.2.2 Protocol Version
         * The one byte unsigned value that represents the revision level of the protocol used by the Client.
         * The value of the Protocol Version field for version 5.0 of the protocol is 5 (0x05).
         *
         * A Server which supports multiple versions of the MQTT protocol uses the Protocol Version to determine
         * which version of MQTT the Client is using. If the Protocol Version is not 5 and the Server does not want
         * to accept the CONNECT packet, the Server MAY send a CONNACK packet with Reason Code 0x84 (Unsupported
         * Protocol Version) and then MUST close the Network Connection [MQTT-3.1.2-2].
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477337">
         *     3.1.2.2 Protocol Version</a>
         */
        val protocolVersion: UByte = 5u,
        /**
         * 3.1.2.8 User Name Flag
         * Position: bit 7 of the Connect Flags.
         *
         * If the User Name Flag is set to 0, a User Name MUST NOT be present in the Payload [MQTT-3.1.2-16]. If
         * the User Name Flag is set to 1, a User Name MUST be present in the Payload [MQTT-3.1.2-17].
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477343">
         *     3.1.2.8 User Name Flag</a>
         */
        val hasUserName: Boolean = false,
        /**
         * 3.1.2.9 Password Flag
         *
         * Position: bit 6 of the Connect Flags.
         *
         * If the Password Flag is set to 0, a Password MUST NOT be present in the Payload [MQTT-3.1.2-18]. If the
         * Password Flag is set to 1, a Password MUST be present in the Payload [MQTT-3.1.2-19].
         *
         * Non-normative comment
         *
         * This version of the protocol allows the sending of a Password with no User Name, where MQTT v3.1.1 did
         * not. This reflects the common use of Password for credentials other than a password.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477344">
         *     3.1.2.9 Password Flag</a>
         */
        val hasPassword: Boolean = false,
        /**
         * 3.1.2.7 Will Retain
         *
         * Position: bit 5 of the Connect Flags.
         *
         * This bit specifies if the Will Message is to be retained when it is published.
         *
         * If the Will Flag is set to 0, then Will Retain MUST be set to 0 [MQTT-3.1.2-13]. If the Will Flag is
         * set to 1 and Will Retain is set to 0, the Server MUST publish the Will Message as a non-retained
         * message [MQTT-3.1.2-14]. If the Will Flag is set to 1 and Will Retain is set to 1, the Server MUST
         * publish the Will Message as a retained message [MQTT-3.1.2-15].
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477342">3.1.2.7 Will Retain</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477338">
         *     3.1.2.3 Connect Flags</a>
         */
        val willRetain: Boolean = false,
        /**
         * 3.1.2.6 Will QoS
         *
         * Position: bits 4 and 3 of the Connect Flags.
         *
         * These two bits specify the QoS level to be used when publishing the Will Message.
         *
         * If the Will Flag is set to 0, then the Will QoS MUST be set to 0 (0x00) [MQTT-3.1.2-11].
         *
         * If the Will Flag is set to 1, the value of Will QoS can be 0 (0x00), 1 (0x01), or 2 (0x02)
         * [MQTT-3.1.2-12]. A value of 3 (0x03) is a Malformed Packet. Refer to section 4.13 for information about
         * handling errors.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477341">3.1.2.6 Will QoS</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#S4_13_Errors">Section 4.13</a>
         */
        val willQos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        /**
         * 3.1.2.5 Will Flag
         *
         * Position: bit 2 of the Connect Flags.
         *
         * If the Will Flag is set to 1 this indicates that a Will Message MUST be stored on the Server and
         * associated with the Session [MQTT-3.1.2-7]. The Will Message consists of the Will Properties,
         * Will Topic, and Will Payload fields in the CONNECT Payload. The Will Message MUST be published after the
         * Network Connection is subsequently closed and either the Will Delay Interval has elapsed or the Session
         * ends, unless the Will Message has been deleted by the Server on receipt of a DISCONNECT packet with
         * Reason Code 0x00 (Normal  disconnection) or a new Network Connection for the ClientID is opened before
         * the Will Delay Interval has elapsed [MQTT-3.1.2-8].
         *
         * Situations in which the Will Message is published include, but are not limited to:
         *
         *    * An I/O error or network failure detected by the Server.
         *    * The Client fails to communicate within the Keep Alive time.
         *    * The Client closes the Network Connection without first sending a DISCONNECT packet with a Reason Code 0x00 (Normal disconnection).
         *    * The Server closes the Network Connection without first receiving a DISCONNECT packet with a Reason Code 0x00 (Normal disconnection).
         *
         * If the Will Flag is set to 1, the Will Properties, Will Topic, and Will Payload fields MUST be present
         * in the Payload [MQTT-3.1.2-9]. The Will Message MUST be removed from the stored Session State in the
         * Server once it has been published or the Server has received a DISCONNECT packet with a Reason Code
         * of 0x00 (Normal disconnection) from the Client [MQTT-3.1.2-10].
         *
         * The Server SHOULD publish Will Messages promptly after the Network Connection is closed and the Will
         * Delay Interval has passed, or when the Session ends, whichever occurs first. In the case of a Server
         * shutdown or failure, the Server MAY defer publication of Will Messages until a subsequent restart.
         * If this happens, there might be a delay between the time the Server experienced failure and when the
         * Will Message is published.
         *
         * Refer to section 3.1.3.2 for information about the Will Delay Interval.
         *
         * Non-normative comment
         *
         * The Client can arrange for the Will Message to notify that Session Expiry has occurred by setting the
         * Will Delay Interval to be longer than the Session Expiry Interval and sending DISCONNECT with Reason
         * Code 0x04 (Disconnect with Will Message).
         *
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477340">
         *     3.1.2.5 Will Flag</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Will_Delay_Interval_1">
         *     Section 3.1.3.2</a>
         */
        val willFlag: Boolean = false,
        /**
         * 3.1.2.4 Clean Start
         * Position: bit 1 of the Connect Flags byte.
         *
         * This bit specifies whether the Connection starts a new Session or is a continuation of an existing
         * Session. Refer to section 4.1 for a definition of the Session State.
         *
         * If a CONNECT packet is received with Clean Start is set to 1, the Client and Server MUST discard any
         * existing Session and start a new Session [MQTT-3.1.2-4]. Consequently, the Session Present flag in
         * CONNACK is always set to 0 if Clean Start is set to 1.
         *
         * If a CONNECT packet is received with Clean Start set to 0 and there is a Session associated with the
         * Client Identifier, the Server MUST resume communications with the Client based on state from the existing
         * Session [MQTT-3.1.2-5]. If a CONNECT packet is received with Clean Start set to 0 and there is no
         * Session associated with the Client Identifier, the Server MUST create a new Session [MQTT-3.1.2-6].
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477339">
         *     3.1.2.4 Clean Start</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Session_State">
         *     Session State</a>
         */
        val cleanStart: Boolean = false,
        /**
         * 3.1.2.10 Keep Alive
         *
         * The Keep Alive is a Two Byte Integer which is a time interval measured in seconds. It is the maximum
         * time interval that is permitted to elapse between the point at which the Client finishes transmitting
         * one MQTT Control Packet and the point it starts sending the next. It is the responsibility of the
         * Client to ensure that the interval between MQTT Control Packets being sent does not exceed the Keep
         * Alive value. If Keep Alive is non-zero and in the absence of sending any other MQTT Control Packets,
         * the Client MUST send a PINGREQ packet [MQTT-3.1.2-20].
         *
         * If the Server returns a Server Keep Alive on the CONNACK packet, the Client MUST use that value instead
         * of the value it sent as the Keep Alive [MQTT-3.1.2-21].
         *
         * The Client can send PINGREQ at any time, irrespective of the Keep Alive value, and check for a
         * corresponding PINGRESP to determine that the network and the Server are available.
         *
         * If the Keep Alive value is non-zero and the Server does not receive an MQTT Control Packet from the
         * Client within one and a half times the Keep Alive time period, it MUST close the Network Connection to
         * the Client as if the network had failed [MQTT-3.1.2-22].
         *
         * If a Client does not receive a PINGRESP packet within a reasonable amount of time after it has sent a
         * PINGREQ, it SHOULD close the Network Connection to the Server.
         *
         * A Keep Alive value of 0 has the effect of turning off the Keep Alive mechanism. If Keep Alive is 0 the
         * Client is not obliged to send MQTT Control Packets on any particular schedule.
         *
         * Non-normative comment
         *
         * The Server may have other reasons to disconnect the Client, for instance because it is shutting down.
         * Setting Keep Alive does not guarantee that the Client will remain connected.
         *
         * Non-normative comment
         *
         * The actual value of the Keep Alive is application specific; typically, this is a few minutes. The
         * maximum value of 65,535 is 18 hours 12 minutes and 15 seconds.
         *
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477345">
         *     3.1.2.10 Keep Alive</a>
         */
        val keepAliveSeconds: Int = UShort.MAX_VALUE.toInt(),
        /**
         * 3.1.2.11 CONNECT Properties
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477346">
         *     3.1.2.11 CONNECT Properties</a>
         */
        val properties: Properties = Properties(),
    ) {
        fun validateOrGetWarning(): MqttWarning? {
            if (!willFlag && willRetain) {
                return MqttWarning(
                    "[MQTT-3.1.2-13]",
                    "If the Will Flag is set" +
                        " to 0, then Will Retain MUST be set to 0",
                )
            }
            return null
        }

        data class Properties(
            /**
             * 3.1.2.11.2 Session Expiry Interval
             *
             * 17 (0x11) Byte, Identifier of the Session Expiry Interval.
             *
             * Followed by the Four Byte Integer representing the Session Expiry Interval in seconds. It is a
             * Protocol Error to include the Session Expiry Interval more than once.
             *
             * If the Session Expiry Interval is absent the value 0 is used. If it is set to 0, or is absent, the
             * Session ends when the Network Connection is closed.
             *
             * If the Session Expiry Interval is 0xFFFFFFFF (UINT_MAX), the Session does not expire.
             *
             * The Client and Server MUST store the Session State after the Network Connection is closed if the
             * Session Expiry Interval is greater than 0 [MQTT-3.1.2-23].
             *
             * Non-normative comment
             *
             * The clock in the Client or Server may not be running for part of the time interval, for instance
             * because the Client or Server are not running. This might cause the deletion of the state to be
             * delayed.
             *
             * Refer to section 4.1 for more information about Sessions. Refer to section 4.1.1 for details and
             * limitations of stored state.
             *
             * When the Session expires the Client and Server need not process the deletion of state atomically.
             *
             * Non-normative comment
             *
             * Setting Clean Start to 1 and a Session Expiry Interval of 0, is equivalent to setting CleanSession
             * to 1 in the MQTT Specification Version 3.1.1. Setting Clean Start to 0 and no Session Expiry
             * Interval, is equivalent to setting CleanSession to 0 in the MQTT Specification Version 3.1.1.
             *
             * Non-normative comment
             *
             * A Client that only wants to process messages while connected will set the Clean Start to 1 and set
             * the Session Expiry Interval to 0. It will not receive Application Messages published before it
             * connected and has to subscribe afresh to any topics that it is interested in each time it connects.
             *
             * Non-normative comment
             *
             * A Client might be connecting to a Server using a network that provides intermittent connectivity.
             * This Client can use a short Session Expiry Interval so that it can reconnect when the network is
             * available again and continue reliable message delivery. If the Client does not reconnect, allowing
             * the Session to expire, then Application Messages will be lost.
             *
             * Non-normative comment
             *
             * When a Client connects with a long Session Expiry Interval, it is requesting that the Server
             * maintain its MQTT session state after it disconnects for an extended period. Clients should only
             * connect with a long Session Expiry Interval if they intend to reconnect to the Server at some later
             * point in time. When a Client has determined that it has no further use for the Session it should
             * disconnect with a Session Expiry Interval set to 0.
             *
             * Non-normative comment
             *
             * The Client should always use the Session Present flag in the CONNACK to determine whether the Server
             * has a Session State for this Client.
             *
             * Non-normative comment
             *
             * The Client can avoid implementing its own Session expiry and instead rely on the Session Present
             * flag returned from the Server to determine if the Session had expired. If the Client does implement
             * its own Session expiry, it needs to store the time at which the Session State will be deleted as part
             * of its Session State.
             *
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477348">
             *     3.1.2.11.2 Session Expiry Interval</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Session_State">
             *     Section 4.1 Sessions</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Storing_Session_State">
             * Section 4.1.1 Storing Session State</a>
             * */
            val sessionExpiryIntervalSeconds: ULong? = null,
            /**
             * 3.1.2.11.3 Receive Maximum
             *
             * 33 (0x21) Byte, Identifier of the Receive Maximum.
             *
             * Followed by the Two Byte Integer representing the Receive Maximum value. It is a Protocol Error to
             * include the Receive Maximum value more than once or for it to have the value 0.
             *
             * The Client uses this value to limit the number of QoS 1 and QoS 2 publications that it is willing to
             * process concurrently. There is no mechanism to limit the QoS 0 publications that the Server might
             * try to send.
             *
             * The value of Receive Maximum applies only to the current Network Connection. If the Receive Maximum
             * value is absent then its value defaults to 65,535.
             *
             * Refer to section 4.9 Flow Control for details of how the Receive Maximum is used.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477349">
             *     3.1.2.11.3 Receive Maximum</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Flow_Control">
             *     Flow Control</a>
             */
            val receiveMaximum: Int? = null,
            /**
             * 3.1.2.11.4 Maximum Packet Size
             *
             * 39 (0x27) Byte, Identifier of the Maximum Packet Size.
             * Followed by a Four Byte Integer representing the Maximum Packet Size the Client is willing to accept.
             * If the Maximum Packet Size is not present, no limit on the packet size is imposed beyond the
             * limitations in the protocol as a result of the remaining length encoding and the protocol header
             * sizes.
             *
             * It is a Protocol Error to include the Maximum Packet Size more than once, or for the value to be set
             * to zero.
             *
             * Non-normative comment
             *
             * It is the responsibility of the application to select a suitable Maximum Packet Size value if it
             * chooses to restrict the Maximum Packet Size.
             *
             * The packet size is the total number of bytes in an MQTT Control Packet, as defined in section
             * 2.1.4. The Client uses the Maximum Packet Size to inform the Server that it will not process
             * packets exceeding this limit.
             *
             * The Server MUST NOT send packets exceeding Maximum Packet Size to the Client [MQTT-3.1.2-24].
             * If a Client receives a packet whose size exceeds this limit, this is a Protocol Error, the Client
             * uses DISCONNECT with Reason Code 0x95 (Packet too large), as described in section 4.13.
             *
             * Where a Packet is too large to send, the Server MUST discard it without sending it and then behave
             * as if it had completed sending that Application Message [MQTT-3.1.2-25].
             *
             * In the case of a Shared Subscription where the message is too large to send to one or more of the
             * Clients but other Clients can receive it, the Server can choose either discard the message without
             * sending the message to any of the Clients, or to send the message to one of the Clients that can
             * receive it.
             *
             * Non-normative comment
             *
             * Where a packet is discarded without being sent, the Server could place the discarded packet on a
             * ‘dead letter queue’ or perform other diagnostic action. Such actions are outside the scope of
             * this specification.
             *
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477350">
             *     3.1.2.11.4 Maximum Packet Size</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Remaining_Length">
             *     Section 2.1.4 Remaining Length</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#S4_13_Errors">
             *     Section 4.13 Protocol Error</a>
             */
            val maximumPacketSize: ULong? = null,
            /**
             * 3.1.2.11.5 Topic Alias Maximum
             *
             * 34 (0x22) Byte, Identifier of the Topic Alias Maximum.
             *
             * Followed by the Two Byte Integer representing the Topic Alias Maximum value. It is a Protocol Error
             * to include the Topic Alias Maximum value more than once. If the Topic Alias Maximum property is
             * absent, the default value is 0.
             *
             * This value indicates the highest value that the Client will accept as a Topic Alias sent by the
             * Server. The Client uses this value to limit the number of Topic Aliases that it is willing to hold
             * on this Connection. The Server MUST NOT send a Topic Alias in a PUBLISH packet to the Client greater
             * than Topic Alias Maximum [MQTT-3.1.2-26]. A value of 0 indicates that the Client does not accept any
             * Topic Aliases on this connection. If Topic Alias Maximum is absent or zero, the Server MUST NOT send
             * any Topic Aliases to the Client [MQTT-3.1.2-27].
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477351">
             *     3.1.2.11.5 Topic Alias Maximum</a>
             */
            val topicAliasMaximum: Int? = null,
            /**
             * 3.1.2.11.6 Request Response Information
             *
             * 25 (0x19) Byte, Identifier of the Request Response Information.
             *
             * Followed by a Byte with a value of either 0 or 1. It is Protocol Error to include the Request
             * Response Information more than once, or to have a value other than 0 or 1. If the Request Response
             * Information is absent, the value of 0 is used.
             *
             * The Client uses this value to request the Server to return Response Information in the CONNACK. A
             * value of 0 indicates that the Server MUST NOT return Response Information [MQTT-3.1.2-28]. If the
             * value is 1 the Server MAY return Response Information in the CONNACK packet.
             *
             * Non-normative comment
             *
             * The Server can choose not to include Response Information in the CONNACK, even if the Client
             * requested it.
             *
             * Refer to section 4.10 for more information about Request / Response.
             *
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477352">
             *     3.1.2.11.6 Request Response Information</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Request_/_Response">
             *     Section 4.10 Request Response</a>
             */
            val requestResponseInformation: Boolean? = null,
            /**
             * 3.1.2.11.7 Request Problem Information
             *
             * 23 (0x17) Byte, Identifier of the Request Problem Information.
             *
             * Followed by a Byte with a value of either 0 or 1. It is a Protocol Error to include Request Problem
             * Information more than once, or to have a value other than 0 or 1. If the Request Problem
             * Information is absent, the value of 1 is used.
             *
             * The Client uses this value to indicate whether the Reason String or User Properties are sent in
             * the case of failures.
             *
             * If the value of Request Problem Information is 0, the Server MAY return a Reason String or User
             * Properties on a CONNACK or DISCONNECT packet, but MUST NOT send a Reason String or User Properties
             * on any packet other than PUBLISH, CONNACK, or DISCONNECT [MQTT-3.1.2-29]. If the value is 0 and
             * the Client receives a Reason String or User Properties in a packet other than PUBLISH, CONNACK,
             * or DISCONNECT, it uses a DISCONNECT packet with Reason Code 0x82 (Protocol Error) as described
             * in section 4.13 Handling errors.
             *
             * If this value is 1, the Server MAY return a Reason String or User Properties on any packet
             * where it is allowed.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477353">
             *     3.1.2.11.7 Request Problem Information</a>
             */
            val requestProblemInformation: Boolean? = null,
            /**
             * 3.1.2.11.8 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by a UTF-8 String Pair.
             *
             * The User Property is allowed to appear multiple times to represent multiple name, value pairs. The
             * same name is allowed to appear more than once.
             *
             * Non-normative comment
             *
             * User Properties on the CONNECT packet can be used to send connection related properties from the
             * Client to the Server. The meaning of these properties is not defined by this specification.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477354">
             *     3.1.2.11.8 User Property</a>
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
            /**
             * Wrapper class for the authentication data
             * @see Authentication.method
             * @see Authentication.data
             */
            val authentication: Authentication? = null,
        ) {
            init {
                if (maximumPacketSize == 0uL) {
                    throw ProtocolError("Maximum Packet Size cannot be set to 0")
                }
            }

            val props by lazy(LazyThreadSafetyMode.NONE) {
                val userPropertyCount = userProperty.count()
                val list = ArrayList<Property>(8 + userPropertyCount)
                if (sessionExpiryIntervalSeconds != null) {
                    list += SessionExpiryInterval(sessionExpiryIntervalSeconds)
                }
                if (receiveMaximum != null) {
                    list += ReceiveMaximum(receiveMaximum)
                }
                if (maximumPacketSize != null) {
                    list += MaximumPacketSize(maximumPacketSize)
                }
                if (topicAliasMaximum != null) {
                    list += TopicAliasMaximum(topicAliasMaximum)
                }
                if (requestResponseInformation != null) {
                    list += RequestResponseInformation(requestResponseInformation)
                }
                if (requestProblemInformation != null) {
                    list += RequestProblemInformation(requestProblemInformation)
                }
                if (userProperty.isNotEmpty()) {
                    for (keyValueProperty in userProperty) {
                        val key = keyValueProperty.first
                        val value = keyValueProperty.second
                        list += UserProperty(key, value)
                    }
                }
                if (authentication != null) {
                    list += AuthenticationMethod(authentication.method)
                    list += AuthenticationData(authentication.data)
                }
                list
            }

            fun size(): Int {
                var size = 0
                props.forEach { size += it.size() }
                return size
            }

            fun serialize(writeBuffer: WriteBuffer) {
                writeBuffer.writeVariableByteInteger(size())
                props.forEach { it.write(writeBuffer) }
            }

            companion object {
                fun from(keyValuePairs: Collection<Property>?): Properties {
                    var sessionExpiryIntervalSeconds: ULong? = null
                    var receiveMaximum: Int? = null
                    var maximumPacketSize: ULong? = null
                    var topicAliasMaximum: Int? = null
                    var requestResponseInformation: Boolean? = null
                    var requestProblemInformation: Boolean? = null
                    val userProperty = mutableListOf<Pair<String, String>>()
                    var authenticationMethod: String? = null
                    var authenticationData: ReadBuffer? = null
                    keyValuePairs?.forEach {
                        when (it) {
                            is SessionExpiryInterval -> {
                                if (sessionExpiryIntervalSeconds != null) {
                                    throw ProtocolError(
                                        "Session Expiry Interval added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477348",
                                    )
                                }
                                sessionExpiryIntervalSeconds = it.seconds
                            }

                            is ReceiveMaximum -> {
                                if (receiveMaximum != null) {
                                    throw ProtocolError(
                                        "Receive Maximum added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477349",
                                    )
                                }
                                if (it.maxQos1Or2ConcurrentMessages == 0) {
                                    throw ProtocolError(
                                        "Receive Maximum cannot be set to 0 see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477349",
                                    )
                                }
                                receiveMaximum = it.maxQos1Or2ConcurrentMessages
                            }

                            is MaximumPacketSize -> {
                                if (maximumPacketSize != null) {
                                    throw ProtocolError(
                                        "Maximum Packet Size added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477350",
                                    )
                                }
                                if (it.packetSizeLimitationBytes == 0uL) {
                                    throw ProtocolError(
                                        "Maximum Packet Size cannot be set to 0 see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477350",
                                    )
                                }
                                maximumPacketSize = it.packetSizeLimitationBytes
                            }

                            is TopicAliasMaximum -> {
                                if (topicAliasMaximum != null) {
                                    throw ProtocolError(
                                        "Topic Alias Maximum added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477351",
                                    )
                                }
                                topicAliasMaximum = it.highestValueSupported
                            }

                            is RequestResponseInformation -> {
                                if (requestResponseInformation != null) {
                                    throw ProtocolError(
                                        "Request Response Information added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477352",
                                    )
                                }
                                requestResponseInformation = it.requestServerToReturnInfoInConnack
                            }

                            is RequestProblemInformation -> {
                                if (requestProblemInformation != null) {
                                    throw ProtocolError(
                                        "Request Problem Information added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477353",
                                    )
                                }
                                requestProblemInformation =
                                    it.reasonStringOrUserPropertiesAreSentInFailures
                            }

                            is UserProperty -> userProperty.add(Pair(it.key, it.value))
                            is AuthenticationMethod -> {
                                if (authenticationMethod != null) {
                                    throw ProtocolError(
                                        "Authentication Method added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477355",
                                    )
                                }
                                authenticationMethod = it.value
                            }

                            is AuthenticationData -> {
                                if (authenticationData != null) {
                                    throw ProtocolError(
                                        "Authentication Data added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477356",
                                    )
                                }
                                authenticationData = it.data
                            }

                            else -> throw MalformedPacketException("Invalid CONNECT property type found in MQTT payload $it")
                        }
                    }
                    val authMethod = authenticationMethod
                    val authData = authenticationData
                    val auth =
                        if (authMethod != null && authData != null) {
                            Authentication(authMethod, authData)
                        } else {
                            null
                        }
                    val finalUserProperty = if (userProperty.isEmpty()) emptyList() else userProperty
                    return Properties(
                        sessionExpiryIntervalSeconds,
                        receiveMaximum,
                        maximumPacketSize,
                        topicAliasMaximum,
                        requestResponseInformation,
                        requestProblemInformation,
                        finalUserProperty,
                        auth,
                    )
                }
            }
        }

        /**
         * The Variable Header for the CONNECT Packet contains the following fields in this order: Protocol Name,
         * Protocol Level, Connect Flags, Keep Alive, and Properties
         */
        fun serialize(writeBuffer: WriteBuffer) {
            val usernameFlag = if (hasUserName) 0b10000000 else 0
            val passwordFlag = if (hasPassword) 0b1000000 else 0
            val wRetain = if (willRetain) 0b100000 else 0
            val qos = willQos.integerValue.toInt().shl(3)
            val wFlag = if (willFlag) 0b100 else 0
            val cleanStart = if (cleanStart) 0b10 else 0
            val flags =
                (usernameFlag or passwordFlag or wRetain or qos or wFlag or cleanStart).toByte()
            writeBuffer.writeMqttUtf8String(protocolName)
            writeBuffer.writeUByte(protocolVersion.toUByte())
            writeBuffer.writeByte(flags)
            writeBuffer.writeUShort(keepAliveSeconds.toUShort())
            properties.serialize(writeBuffer)
        }

        fun size(): Int {
            var size = UShort.SIZE_BYTES + protocolName.utf8Length()
            size += (2 * UByte.SIZE_BYTES) + UShort.SIZE_BYTES
            val propsSize = properties.size()
            size += propsSize + variableByteSize(propsSize)
            return size
        }

        companion object {
            fun from(buffer: ReadBuffer): VariableHeader {
                val protocolName = buffer.readMqttUtf8StringNotValidatedSized().second
                val protocolVersion = buffer.readUnsignedByte()
                val connectFlags = buffer.readUnsignedByte()
                val reserved = connectFlags.get(0)
                val cleanStart = connectFlags.get(1)
                val willFlag = connectFlags.get(2)
                val willQosBit1 = connectFlags.get(3)
                val willQosBit2 = connectFlags.get(4)
                val willQos = QualityOfService.fromBooleans(willQosBit2, willQosBit1)
                val willRetain = connectFlags.get(5)
                val hasPassword = connectFlags.get(6)
                val hasUsername = connectFlags.get(7)
                if (reserved) {
                    throw MalformedPacketException(
                        "Reserved flag in Connect Variable Header packet is set incorrectly to 1",
                    )
                }
                val keepAliveSeconds = buffer.readUnsignedShort().toInt()
                val propertiesRaw = buffer.readProperties()
                val properties = Properties.from(propertiesRaw)
                return VariableHeader(
                    protocolName, protocolVersion, hasUsername, hasPassword, willRetain, willQos,
                    willFlag, cleanStart, keepAliveSeconds, properties,
                )
            }
        }
    }

    /**
     * 3.1.3 CONNECT Payload
     *
     * The Payload of the CONNECT packet contains one or more length-prefixed fields, whose presence is determined
     * by the flags in the Variable Header. These fields, if present, MUST appear in the order Client Identifier
     * Will Properties, Will Topic, Will Payload, User Name, Password [MQTT-3.1.3-1].
     *
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477358">
     *     3.1.3 CONNECT Payload</a>
     */

    data class Payload(
        /**
         * 3.1.3.1 Client Identifier (ClientID)
         * The Client Identifier (ClientID) identifies the Client to the Server. Each Client connecting to the
         * Server has a unique ClientID. The ClientID MUST be used by Clients and by Servers to identify state
         * that they hold relating to this MQTT Session between the Client and the Server [MQTT-3.1.3-2]. Refer
         * to section 4.1 for more information about Session State.
         *
         * The ClientID MUST be present and is the first field in the CONNECT packet Payload [MQTT-3.1.3-3].
         *
         * The ClientID MUST be a UTF-8 Encoded String as defined in section 1.5.4 [MQTT-3.1.3-4].
         *
         * The Server MUST allow ClientID’s which are between 1 and 23 UTF-8 encoded bytes in length, and that
         * contain only the characters
         *
         * "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" [MQTT-3.1.3-5].
         *
         * The Server MAY allow ClientID’s that contain more than 23 encoded bytes. The Server MAY allow
         * ClientID’s that contain characters not included in the list given above.
         *
         * A Server MAY allow a Client to supply a ClientID that has a length of zero bytes, however if it does
         * so the Server MUST treat this as a special case and assign a unique ClientID to that Client
         * [MQTT-3.1.3-6]. It MUST then process the CONNECT packet as if the Client had provided that unique
         * ClientID, and MUST return the Assigned Client Identifier in the CONNACK packet [MQTT-3.1.3-7].
         *
         * If the Server rejects the ClientID it MAY respond to the CONNECT packet with a CONNACK using Reason
         * Code 0x85 (Client Identifier not valid) as described in section 4.13 Handling errors, and then it
         * MUST close the Network Connection [MQTT-3.1.3-8].
         *
         * Non-normative comment
         *
         * A Client implementation could provide a convenience method to generate a random ClientID. Clients
         * using this method should take care to avoid creating long-lived orphaned Sessions.
         *
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477359">
         *     3.1.3.1 Client Identifier (ClientID)</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Session_State">
         *     Section 4.1 Session State</a>
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_UTF-8_Encoded_String">
         *     Section 1.54 UTF-8 Encoded String</a>
         */
        val clientId: String = "",
        /**
         * 3.1.3.2 Will Properties
         *
         * If the Will Flag is set to 1, the Will Properties is the next field in the Payload. The Will Properties
         * field defines the Application Message properties to be sent with the Will Message when it is published,
         * and properties which define when to publish the Will Message. The Will Properties consists of a Property
         * Length and the Properties.
         *
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477359">
         *     3.1.3.2 Will Properties</a>
         */
        val willProperties: WillProperties? = null,
        /**
         * 3.1.3.3 Will Topic
         *
         * If the Will Flag is set to 1, the Will Topic is the next field in the Payload. The Will Topic MUST
         * be a UTF-8 Encoded String as defined in section 1.5.4 [MQTT-3.1.3-11].
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477369">
         *     3.1.3.3 Will Topic</a>
         */
        val willTopic: Topic? = null,
        /**
         * 3.1.3.4 Will Payload
         * If the Will Flag is set to 1 the Will Payload is the next field in the Payload. The Will Payload
         * defines the Application Message Payload that is to be published to the Will Topic as described in
         * section 3.1.2.5. This field consists of Binary Data.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477359">
         *     3.1.3.4 Will Payload</a>
         */
        val willPayload: ReadBuffer? = null,
        /**
         * 3.1.3.5 User Name
         *
         * If the User Name Flag is set to 1, the User Name is the next field in the Payload. The User Name MUST
         * be a UTF-8 Encoded String as defined in section 1.5.4 [MQTT-3.1.3-12]. It can be used by the Server
         * for authentication and authorization.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477371">
         *     3.1.3.5 User Name</a>
         */
        val userName: String? = null,
        /**
         * 3.1.3.6 Password
         * If the Password Flag is set to 1, the Password is the next field in the Payload. The Password field
         * is Binary Data. Although this field is called Password, it can be used to carry any credential
         * information.
         * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477372">
         *     3.1.3.6 Password</a>
         */
        val password: String? = null,
    ) {
        data class WillProperties(
            /**
             * 3.1.3.2.2 Will Delay Interval
             *
             * 24 (0x18) Byte, Identifier of the Will Delay Interval.
             *
             * Followed by the Four Byte Integer representing the Will Delay Interval in seconds. It is a Protocol
             * Error to include the Will Delay Interval more than once. If the Will Delay Interval is absent, the
             * default value is 0 and there is no delay before the Will Message is published.
             *
             * The Server delays publishing the Client’s Will Message until the Will Delay Interval has passed or
             * the Session ends, whichever happens first. If a new Network Connection to this Session is made
             * before the Will Delay Interval has passed, the Server MUST NOT send the Will Message [MQTT-3.1.3-9].
             *
             * Non-normative comment
             *
             * One use of this is to avoid publishing Will Messages if there is a temporary network disconnection
             * and the Client succeeds in reconnecting and continuing its Session before the Will Message is
             * published.
             *
             * Non-normative comment
             *
             * If a Network Connection uses a Client Identifier of an existing Network Connection to the Server,
             * the Will Message for the exiting connection is sent unless the new connection specifies Clean Start
             * of 0 and the Will Delay is greater than zero. If the Will Delay is 0 the Will Message is sent at
             * the close of the existing Network Connection, and if Clean Start is 1 the Will Message is sent
             * because the Session ends.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477362">
             *     3.1.3.2.2 Will Delay Interval</a>
             */
            val willDelayIntervalSeconds: Long = 0L,
            /**
             * 3.1.3.2.3 Payload Format Indicator
             *
             * 1 (0x01) Byte, Identifier of the Payload Format Indicator.
             *
             * Followed by the value of the Payload Format Indicator, either of:
             *
             * ·         0 (0x00) Byte Indicates that the Will Message is unspecified bytes, which is equivalent to not sending a Payload Format Indicator.
             *
             * ·         1 (0x01) Byte Indicates that the Will Message is UTF-8 Encoded Character Data. The UTF-8 data in the Payload MUST be well-formed UTF-8 as defined by the Unicode specification [Unicode] and restated in RFC 3629 [RFC3629].
             *
             * It is a Protocol Error to include the Payload Format Indicator more than once. The Server MAY
             * validate that the Will Message is of the format indicated, and if it is not send a CONNACK with
             * the Reason Code of 0x99 (Payload format invalid) as described in section 4.13.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477363">
             *     3.1.3.2.3 Payload Format Indicator</a>
             */
            val payloadFormatIndicator: Boolean = false,
            /**
             * 3.1.3.2.4 Message Expiry Interval
             *
             * 2 (0x02) Byte, Identifier of the Message Expiry Interval.
             *
             * Followed by the Four Byte Integer representing the Message Expiry Interval. It is a Protocol Error
             * to include the Message Expiry Interval more than once.
             *
             * If present, the Four Byte value is the lifetime of the Will Message in seconds and is sent as the
             * Publication Expiry Interval when the Server publishes the Will Message.
             *
             * If absent, no Message Expiry Interval is sent when the Server publishes the Will Message.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477364">
             *     3.1.3.2.4 Message Expiry Interval</a>
             */
            val messageExpiryIntervalSeconds: Long? = null,
            /**
             * 3.1.3.2.5 Content Type
             * 3 (0x03) Identifier of the Content Type.
             *
             * Followed by a UTF-8 Encoded String describing the content of the Will Message. It is a Protocol
             * Error to include the Content Type more than once. The value of the Content Type is defined by the
             * sending and receiving application.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477365">
             *     3.1.3.2.5 Content Type</a>
             */
            val contentType: String? = null,
            /**
             * 3.1.3.2.6 Response Topic
             *
             * 8 (0x08) Byte, Identifier of the Response Topic.
             *
             * Followed by a UTF-8 Encoded String which is used as the Topic Name for a response message. It is a
             * Protocol Error to include the Response Topic more than once. The presence of a Response Topic
             * identifies the Will Message as a Request.
             *
             * Refer to section 4.10 for more information about Request / Response.
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477366">
             *     3.1.3.2.6 Response Topic</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Request_/_Response">
             *     Section 4.10 Request Response</a>
             */
            val responseTopic: Topic? = null,
            /**
             * 3.1.3.2.7 Correlation Data
             *
             * 9 (0x09) Byte, Identifier of the Correlation Data.
             *
             * Followed by Binary Data. The Correlation Data is used by the sender of the Request Message to
             * identify which request the Response Message is for when it is received. It is a Protocol Error to
             * include Correlation Data more than once. If the Correlation Data is not present, the Requester
             * does not require any correlation data.
             *
             * The value of the Correlation Data only has meaning to the sender of the Request Message and
             * receiver of the Response Message.
             *
             * Refer to section 4.10 for more information about Request / Response
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477367">
             *     3.1.3.2.7 Correlation Data</a>
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Request_/_Response">
             *     Section 4.10 Request Response</a>
             */
            val correlationData: ReadBuffer? = null,
            /**
             * 3.1.3.2.8 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by a UTF-8 String Pair. The User Property is allowed to appear multiple times to represent
             * multiple name, value pairs. The same name is allowed to appear more than once.
             *
             * The Server MUST maintain the order of User Properties when publishing the Will Message
             * [MQTT-3.1.3-10].
             *
             * Non-normative comment
             *
             * This property is intended to provide a means of transferring application layer name-value tags
             * whose meaning and interpretation are known only by the application programs responsible for
             * sending and receiving them.
             *
             * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477368">
             *     3.1.3.2.8 User Property</a>
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
        ) {
            val props by lazy(LazyThreadSafetyMode.NONE) {
                val properties = ArrayList<Property>(6 + userProperty.count())
                if (willDelayIntervalSeconds != 0L) {
                    properties += WillDelayInterval(willDelayIntervalSeconds)
                }
                if (payloadFormatIndicator) {
                    properties += PayloadFormatIndicator(payloadFormatIndicator)
                }
                if (messageExpiryIntervalSeconds != null) {
                    properties += MessageExpiryInterval(messageExpiryIntervalSeconds)
                }
                if (contentType != null) {
                    properties += ContentType(contentType)
                }
                if (responseTopic != null) {
                    properties += ResponseTopic(responseTopic)
                }
                if (correlationData != null) {
                    properties += CorrelationData(correlationData)
                }
                if (userProperty.isNotEmpty()) {
                    for (keyValueProperty in userProperty) {
                        val key = keyValueProperty.first
                        val value = keyValueProperty.second
                        properties += UserProperty(key, value)
                    }
                }
                properties
            }

            fun size(): Int {
                var size = 0
                props.forEach { size += it.size() }
                return size
            }

            fun serialize(buffer: WriteBuffer) {
                buffer.writeVariableByteInteger(size())
                props.forEach { it.write(buffer) }
            }

            companion object {
                fun from(buffer: ReadBuffer): WillProperties {
                    var willDelayIntervalSeconds: Long? = null
                    var payloadFormatIndicator: Boolean? = null
                    var messageExpiryIntervalSeconds: Long? = null
                    var contentType: String? = null
                    var responseTopic: Topic? = null
                    var correlationData: ReadBuffer? = null
                    val userProperty = mutableListOf<Pair<String, String>>()
                    val properties = buffer.readProperties() ?: return WillProperties()
                    properties.forEach {
                        when (it) {
                            is WillDelayInterval -> {
                                if (willDelayIntervalSeconds != null) {
                                    throw ProtocolError(
                                        "Will Delay Interval added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477362",
                                    )
                                }
                                willDelayIntervalSeconds = it.seconds
                            }

                            is PayloadFormatIndicator -> {
                                if (payloadFormatIndicator != null) {
                                    throw ProtocolError(
                                        "Payload Format Indicator added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477363",
                                    )
                                }
                                payloadFormatIndicator = it.willMessageIsUtf8
                            }

                            is MessageExpiryInterval -> {
                                if (messageExpiryIntervalSeconds != null) {
                                    throw ProtocolError(
                                        "Message Expiry Interval added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477363",
                                    )
                                }
                                messageExpiryIntervalSeconds = it.seconds
                            }

                            is ContentType -> {
                                if (contentType != null) {
                                    throw ProtocolError(
                                        "Content Type added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477365",
                                    )
                                }
                                contentType = it.value
                            }

                            is ResponseTopic -> {
                                if (responseTopic != null) {
                                    throw ProtocolError(
                                        "Response Topic added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477366",
                                    )
                                }
                                responseTopic = it.value
                            }

                            is CorrelationData -> {
                                if (correlationData != null) {
                                    throw ProtocolError(
                                        "Coorelation data added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477367",
                                    )
                                }
                                correlationData = it.data
                            }

                            is UserProperty -> {
                                val key = it.key
                                val value = it.value
                                userProperty.add(Pair(key, value))
                            }

                            else -> throw MalformedPacketException("Invalid property type found in MQTT payload $it")
                        }
                    }
                    return WillProperties(
                        willDelayIntervalSeconds ?: 0L,
                        payloadFormatIndicator ?: false,
                        messageExpiryIntervalSeconds,
                        contentType,
                        responseTopic,
                        correlationData,
                        userProperty,
                    )
                }
            }
        }

        fun serialize(writeBuffer: WriteBuffer) {
            writeBuffer.writeMqttUtf8String(clientId)
            willProperties?.serialize(writeBuffer)
            if (willTopic != null) {
                writeBuffer.writeMqttUtf8String(willTopic.toString())
            }
            if (willPayload != null) {
                writeBuffer.writeUShort(willPayload.remaining().toUShort())
                writeBuffer.write(willPayload)
            }
            if (userName != null) {
                writeBuffer.writeMqttUtf8String(userName)
            }
            if (password != null) {
                writeBuffer.writeMqttUtf8String(password)
            }
        }

        fun size(): Int {
            var size = UShort.SIZE_BYTES + clientId.utf8Length()
            if (willTopic != null) {
                size += UShort.SIZE_BYTES + willTopic.toString().utf8Length()
            }
            if (willProperties != null) {
                val willPropertiesSize = willProperties.size()
                size += variableByteSize(willPropertiesSize) + willPropertiesSize
            }
            if (willPayload != null) {
                size += UShort.SIZE_BYTES + willPayload.remaining()
            }
            if (userName != null) {
                size += (UShort.SIZE_BYTES + userName.utf8Length())
            }
            if (password != null) {
                size += (UShort.SIZE_BYTES + password.utf8Length())
            }
            return size
        }

        companion object {
            fun from(
                buffer: ReadBuffer,
                variableHeader: VariableHeader,
            ): Payload {
                val clientId = buffer.readMqttUtf8StringNotValidatedSized().second
                val willProperties =
                    if (variableHeader.willFlag) {
                        WillProperties.from(buffer)
                    } else {
                        null
                    }
                val willTopic =
                    if (variableHeader.willFlag) {
                        Topic.fromOrThrow(buffer.readMqttUtf8StringNotValidatedSized().second, Topic.Type.Name)
                    } else {
                        null
                    }
                val willPayload =
                    if (variableHeader.willFlag) {
                        val size = buffer.readUnsignedShort().toInt()
                        buffer.readBytes(size)
                    } else {
                        null
                    }
                val username =
                    if (variableHeader.hasUserName) {
                        buffer.readMqttUtf8StringNotValidatedSized().second
                    } else {
                        null
                    }
                val password =
                    if (variableHeader.hasPassword) {
                        buffer.readMqttUtf8StringNotValidatedSized().second
                    } else {
                        null
                    }
                return Payload(clientId, willProperties, willTopic, willPayload, username, password)
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer): ConnectionRequest {
            val variableHeader = VariableHeader.from(buffer)
            val payload = Payload.from(buffer, variableHeader)
            return ConnectionRequest(variableHeader, payload)
        }
    }
}
