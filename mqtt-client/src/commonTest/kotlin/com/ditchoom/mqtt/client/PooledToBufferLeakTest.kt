package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import kotlin.test.Test

/**
 * Regression test for pooled buffer leak in toBuffer().
 * Verifies that buffers allocated via a PoolingFactory are properly
 * releasable and don't leak direct memory when freed.
 */
class PooledToBufferLeakTest {
    @Test
    fun pooledToBufferDoesNotLeakWhenReleased() {
        val pool = BufferPool()
        val pooledFactory = BufferFactory.Default.withPooling(pool)
        val payload = BufferFactory.Default.allocate(64)
        repeat(64) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        val publish =
            PublishMessageV4.ofRaw(
                topic = TopicName.fromOrThrow("test/leak"),
                qos = QualityOfService.AT_LEAST_ONCE,
                packetIdentifier = 1,
                payload = payload,
            )

        // Allocate and release 10_000 times — would OOM without proper release
        repeat(10_000) {
            val buf = publish.toBuffer(pooledFactory)
            (buf as PlatformBuffer).freeNativeMemory()
        }
        pool.clear()
    }
}
