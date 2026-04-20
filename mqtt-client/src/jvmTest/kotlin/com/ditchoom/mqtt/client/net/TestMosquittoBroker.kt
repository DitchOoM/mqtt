package com.ditchoom.mqtt.client.net

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile

/**
 * Shared Mosquitto container for JVM integration tests. Exposes both the MQTT-over-TCP
 * listener (1883) and the MQTT-over-WebSocket listener (8080) configured by
 * `jvmTest/resources/mosquitto.conf`.
 *
 * Tests should call [isDockerAvailable] in a `@BeforeClass` / `kotlin.test.BeforeTest`
 * and skip when Docker is absent, so developer machines without Docker are not blocked.
 */
object TestMosquittoBroker {
    val container: GenericContainer<*> =
        GenericContainer("eclipse-mosquitto:2")
            .withExposedPorts(1883, 8080)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("mosquitto.conf"),
                "/mosquitto/config/mosquitto.conf",
            ).waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1))

    fun isDockerAvailable(): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (_: Throwable) {
            false
        }

    fun tcpHost(): String = container.host

    fun tcpPort(): Int = container.getMappedPort(1883)

    fun wsHost(): String = container.host

    fun wsPort(): Int = container.getMappedPort(8080)
}
