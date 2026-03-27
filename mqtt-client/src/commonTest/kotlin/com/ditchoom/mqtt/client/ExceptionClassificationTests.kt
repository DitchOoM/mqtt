package com.ditchoom.mqtt.client

import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SSLProtocolException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.SocketUnknownHostException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExceptionClassificationTests {
    // ── Non-recoverable errors ──────────────────────────────────────────

    @Test
    fun sslHandshakeFailure_isNonRecoverable() {
        val e = SSLHandshakeFailedException("certificate rejected")
        assertTrue(isNonRecoverableError(e), "SSLHandshakeFailedException should be non-recoverable")
    }

    @Test
    fun sslProtocolException_isNonRecoverable() {
        val e = SSLProtocolException("TLS version mismatch")
        assertTrue(isNonRecoverableError(e), "SSLProtocolException should be non-recoverable")
    }

    @Test
    fun unknownHost_isNonRecoverable() {
        val e = SocketUnknownHostException("no.such.host.example.com")
        assertTrue(isNonRecoverableError(e), "SocketUnknownHostException should be non-recoverable")
    }

    // ── Recoverable errors ──────────────────────────────────────────────

    @Test
    fun connectionRefused_isRecoverable() {
        val e = SocketConnectionException.Refused(host = "127.0.0.1", port = 1883)
        assertFalse(isNonRecoverableError(e), "ConnectionRefused should be recoverable")
    }

    @Test
    fun connectionReset_isRecoverable() {
        val e = SocketClosedException.ConnectionReset("Connection reset by peer")
        assertFalse(isNonRecoverableError(e), "ConnectionReset should be recoverable")
    }

    @Test
    fun brokenPipe_isRecoverable() {
        val e = SocketClosedException.BrokenPipe("Broken pipe")
        assertFalse(isNonRecoverableError(e), "BrokenPipe should be recoverable")
    }

    @Test
    fun timeout_isRecoverable() {
        val e = SocketTimeoutException("Connect timed out", host = "example.com", port = 1883)
        assertFalse(isNonRecoverableError(e), "SocketTimeoutException should be recoverable")
    }

    @Test
    fun networkUnreachable_isRecoverable() {
        val e = SocketConnectionException.NetworkUnreachable("Network is unreachable")
        assertFalse(isNonRecoverableError(e), "NetworkUnreachable should be recoverable")
    }

    @Test
    fun genericIOException_isRecoverable() {
        val e = SocketIOException("Unexpected I/O error")
        assertFalse(isNonRecoverableError(e), "SocketIOException should be recoverable")
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun endOfStream_isRecoverable() {
        val e = SocketClosedException.EndOfStream()
        assertFalse(isNonRecoverableError(e), "EndOfStream should be recoverable")
    }

    @Test
    fun hostUnreachable_isRecoverable() {
        val e = SocketConnectionException.HostUnreachable("Host unreachable")
        assertFalse(isNonRecoverableError(e), "HostUnreachable should be recoverable")
    }

    @Test
    fun generalSocketClosed_isRecoverable() {
        val e = SocketClosedException.General("Socket closed")
        assertFalse(isNonRecoverableError(e), "General SocketClosedException should be recoverable")
    }

    @Test
    fun unknownThrowable_isRecoverable() {
        val e = RuntimeException("something unexpected")
        assertFalse(isNonRecoverableError(e), "Unknown Throwable should default to recoverable")
    }

    // ── MQTT-specific classification ─────────────────────────────────────

    @Test
    fun connackRejected_isNonRecoverable_viaMqttClassifier() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val error = MqttConnectionException.ConnackRejected("Not authorized", 0x87.toUByte())
            assertIs<ReconnectDecision.GiveUp>(classifier.classify(error))
        }

    @Test
    fun protocolError_isNonRecoverable_viaMqttClassifier() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val error = MqttConnectionException.ProtocolError("Malformed packet")
            assertIs<ReconnectDecision.GiveUp>(classifier.classify(error))
        }

    @Test
    fun allEndpointsFailed_allNonRecoverable_givesUp() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val error = MqttConnectionException.AllEndpointsFailed("all failed", allNonRecoverable = true)
            assertIs<ReconnectDecision.GiveUp>(classifier.classify(error))
        }

    @Test
    fun allEndpointsFailed_someRecoverable_retries() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val error = MqttConnectionException.AllEndpointsFailed("some failed", allNonRecoverable = false)
            assertIs<ReconnectDecision.RetryAfter>(classifier.classify(error))
        }

    @Test
    fun transportFailed_delegatesToSocket() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            // Transport with SSL handshake failure → delegate to socket classifier → GiveUp
            val sslError =
                MqttConnectionException.TransportFailed(
                    "TLS failed",
                    SSLHandshakeFailedException("cert rejected"),
                )
            assertIs<ReconnectDecision.GiveUp>(classifier.classify(sslError))

            // Transport with connection refused → delegate to socket classifier → RetryAfter
            val refusedError =
                MqttConnectionException.TransportFailed(
                    "Connection failed",
                    SocketConnectionException.Refused("127.0.0.1", 1883),
                )
            assertIs<ReconnectDecision.RetryAfter>(classifier.classify(refusedError))
        }
}
