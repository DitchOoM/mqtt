package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONTINUE_AUTHENTICATION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.REAUTHENTICATE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.wire.AuthV5Wire
import com.ditchoom.mqtt5.controlpacket.wire.AuthV5WireCodec

/**
 * 3.15 AUTH – Authentication exchange
 * An AUTH packet is sent from Client to Server or Server to Client as part of an extended authentication exchange,
 * such as challenge / response authentication. It is a Protocol Error for the Client or Server to send an AUTH packet
 * if the CONNECT packet did not contain the same Authentication Method.
 *
 * Bits 3,2,1 and 0 of the Fixed Header of the AUTH packet are reserved and MUST all be set to 0. The Client or Server
 * MUST treat any other value as malformed and close the Network Connection [MQTT-3.15.1-1].
 */

data class AuthenticationExchange(
    val variable: VariableHeader,
) : ControlPacketV5,
    WireEncoded<AuthV5Wire> {
    override val controlPacketValue: Byte get() = 15
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val wireCodec: Codec<AuthV5Wire> get() = AuthV5WireCodec

    override fun toWire(): AuthV5Wire {
        val propsList = buildList<MqttProperty> {
            val auth = variable.properties.authentication
            if (auth != null) {
                add(AuthenticationMethod(auth.method))
                auth.data.position(0)
                add(AuthenticationData(auth.data.remaining().toUShort(), auth.data))
            }
            if (variable.properties.reasonString != null) {
                add(ReasonString(variable.properties.reasonString))
            }
            for (kv in variable.properties.userProperty) {
                add(UserProperty(kv.first, kv.second))
            }
        }
        return AuthV5Wire(
            variable.reasonCode.byte,
            propsList.ifEmpty { null },
        )
    }

    /**
     * 3.15.2 AUTH Variable Header
     *
     * The Variable Header of the AUTH Packet contains the following fields in the order: Authenticate Reason Code,
     * and Properties. The rules for encoding Properties are described in section 2.2.2.
     *
     * The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no
     * Properties. In this case the AUTH has a Remaining Length of 0.
     */

    data class VariableHeader(
        /**
         * 3.15.2.1 Authenticate Reason Code
         *
         * Byte 0 in the Variable Header is the Authenticate Reason Code. The values for the one byte unsigned
         * Authenticate Reason Code field are shown below. The sender of the AUTH Packet MUST use one of the
         * Authenticate Reason Codes [MQTT-3.15.2-1].
         *
         * Value |Hex|Reason Code name|Sent by|Description
         *
         * 0|0x00|Success|Server|Authentication is successful
         *
         * 24|0x18|Continue authentication|Client or Server|Continue the authentication with another step
         *
         * 25|0x19|Re-authenticate|Client
         */
        val reasonCode: ReasonCode = SUCCESS,
        val properties: Properties,
    ) {
        init {
            // throw if reason code doesnt exist
            getReasonCode(reasonCode.byte)
        }

        data class Properties(
            val authentication: Authentication?,
            val reasonString: String? = null,
            val userProperty: List<Pair<String, String>> = emptyList(),
        ) {
            companion object {
                fun from(keyValuePairs: Collection<MqttProperty>?): Properties {
                    val p = PropertyExtractor(keyValuePairs, "AUTH")
                    val method = p.single<AuthenticationMethod>()?.value
                    val data = p.single<AuthenticationData<*>>()?.data as? ReadBuffer
                    val reasonString = p.single<ReasonString>()?.value
                    val userProperty = p.list<UserProperty>().map { it.key to it.value }
                    p.rejectUnknown()
                    val auth = if (method != null && data != null) {
                        Authentication(method, data)
                    } else {
                        null
                    }
                    return Properties(auth, reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): VariableHeader {
                val wire = AuthV5WireCodec.decode(buffer)
                val reasonCode = getReasonCode(wire.reasonCode)
                val props = Properties.from(wire.properties)
                return VariableHeader(reasonCode, props)
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer) = AuthenticationExchange(VariableHeader.from(buffer))
    }
}

private fun getReasonCode(byte: UByte): ReasonCode =
    when (byte) {
        SUCCESS.byte -> SUCCESS
        CONTINUE_AUTHENTICATION.byte -> CONTINUE_AUTHENTICATION
        REAUTHENTICATE.byte -> REAUTHENTICATE
        else -> throw MalformedPacketException("Invalid disconnect reason code $byte")
    }
