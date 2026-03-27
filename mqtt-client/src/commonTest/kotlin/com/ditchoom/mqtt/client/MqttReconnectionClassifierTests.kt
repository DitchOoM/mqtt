package com.ditchoom.mqtt.client

import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.SocketIOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MqttReconnectionClassifierTests {
    @Test
    fun connackRejected_givesUp() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result =
                classifier.classify(
                    MqttConnectionException.ConnackRejected("Bad credentials", 0x86.toUByte()),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun protocolError_givesUp() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result = classifier.classify(MqttConnectionException.ProtocolError("Malformed CONNECT"))
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun allEndpointsFailed_nonRecoverable_givesUp() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result =
                classifier.classify(
                    MqttConnectionException.AllEndpointsFailed("all failed", allNonRecoverable = true),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun allEndpointsFailed_recoverable_retries() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result =
                classifier.classify(
                    MqttConnectionException.AllEndpointsFailed("some failed", allNonRecoverable = false),
                )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun transportFailed_sslHandshake_givesUp() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result =
                classifier.classify(
                    MqttConnectionException.TransportFailed("TLS error", SSLHandshakeFailedException("cert")),
                )
            assertIs<ReconnectDecision.GiveUp>(result)
        }

    @Test
    fun transportFailed_connectionRefused_retries() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result =
                classifier.classify(
                    MqttConnectionException.TransportFailed(
                        "Connection failed",
                        SocketConnectionException.Refused("127.0.0.1", 1883),
                    ),
                )
            assertIs<ReconnectDecision.RetryAfter>(result)
        }

    @Test
    fun backoffProgression_matchesDefaultClassifier() =
        runTest {
            val classifier =
                MqttReconnectionClassifier(
                    delegate =
                        DefaultReconnectionClassifier(
                            initialDelay = 100.milliseconds,
                            maxDelay = 15.seconds,
                            factor = 2.0,
                        ),
                )
            val error = MqttConnectionException.TransportFailed("IO error", SocketIOException("transient"))

            val delays = mutableListOf<kotlin.time.Duration>()
            repeat(5) {
                val result = classifier.classify(error)
                assertIs<ReconnectDecision.RetryAfter>(result)
                delays.add(result.delay)
            }

            assertTrue(delays[0] == 100.milliseconds)
            assertTrue(delays[1] == 200.milliseconds)
            assertTrue(delays[2] == 400.milliseconds)
            assertTrue(delays[3] == 800.milliseconds)
            assertTrue(delays[4] == 1600.milliseconds)
        }

    @Test
    fun reset_restoresInitialDelay() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val error = MqttConnectionException.TransportFailed("IO error", SocketIOException("transient"))

            // Advance backoff
            repeat(5) { classifier.classify(error) }

            // Reset and verify
            classifier.reset()
            val result = classifier.classify(error)
            assertIs<ReconnectDecision.RetryAfter>(result)
            assertTrue(result.delay == 100.milliseconds)
        }

    @Test
    fun unknownException_delegatesToSocket() =
        runTest {
            val classifier = MqttReconnectionClassifier()
            val result = classifier.classify(RuntimeException("unexpected"))
            assertIs<ReconnectDecision.RetryAfter>(result)
        }
}
