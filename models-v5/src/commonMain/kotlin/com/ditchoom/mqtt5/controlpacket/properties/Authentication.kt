package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer

data class Authentication(
    /**
     * 3.1.2.11.9 Authentication Method
     *
     * 21 (0x15) Byte, Identifier of the Authentication Method.
     *
     * Followed by a UTF-8 Encoded String containing the name of the authentication method used for
     * extended authentication .It is a Protocol Error to include Authentication Method more than once.
     *
     * If Authentication Method is absent, extended authentication is not performed. Refer to section
     * 4.12.
     *
     * If a Client sets an Authentication Method in the CONNECT, the Client MUST NOT send any packets
     * other than AUTH or DISCONNECT packets until it has received a CONNACK packet [MQTT-3.1.2-30].
     *
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477355">
     *     3.1.2.11.9 Authentication Method</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Enhanced_authentication">
     *     Section 4.12 Enhanced Authentication</a>
     */
    val method: String,
    /**
     * 3.1.2.11.10 Authentication Data
     *
     * 22 (0x16) Byte, Identifier of the Authentication Data.
     *
     * Followed by Binary Data containing authentication data. It is a Protocol Error to include
     * Authentication Data if there is no Authentication Method. It is a Protocol Error to include
     * Authentication Data more than once.
     *
     * The contents of this data are defined by the authentication method. Refer to section 4.12 for
     * more information about extended authentication.
     */
    val data: ReadBuffer
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Authentication

        if (method != other.method) return false
        if (data != other.data) return false

        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + data.hashCode()
        return result
    }
}
