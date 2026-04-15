package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.Test
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4 as PublishV4

/**
 * Trace where direct memory allocations come from.
 */
class DirectMemoryTraceTest {
    private fun directBufferUsage(): Pair<Long, Long> {
        // JMX direct buffer pool: count and total capacity
        return try {
            val mbs = ManagementFactory.getPlatformMBeanServer()
            val name = ObjectName("java.nio:type=BufferPool,name=direct")
            val count = mbs.getAttribute(name, "Count") as Long
            val memUsed = mbs.getAttribute(name, "MemoryUsed") as Long
            Pair(count, memUsed)
        } catch (_: Exception) {
            Pair(-1L, -1L)
        }
    }

    private fun printDirect(label: String) {
        val (count, bytes) = directBufferUsage()
        println("[$label] direct buffers: count=$count  used=%.2fMB".format(bytes / (1024.0 * 1024.0)))
    }

    @Test
    fun traceDirectAllocations() {
        printDirect("startup")

        // 1. Single small allocate
        val buf1 = BufferFactory.Default.allocate(64)
        printDirect("after 1x64B allocate")

        // 2. Single serialize of a small packet
        val connect = ConnectV4(payload = ConnectV4.Payload(clientId = "trace-client"))
        println("  connect packetSize = ${connect.packetSize()} bytes")
        val serialized = connect.serialize()
        printDirect("after 1 CONNECT serialize (${connect.packetSize()}B)")

        // 3. 100 serializes
        repeat(100) {
            val s = connect.serialize()
        }
        printDirect("after 100 CONNECT serializes")
        System.gc()
        Thread.sleep(100)
        printDirect("after 100 CONNECT serializes + GC")

        // 4. Publish with 128B payload
        val payload = BufferFactory.Default.allocate(128)
        repeat(128) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        val pub =
            PublishV4.ofRaw(
                topic = TopicName.fromOrThrow("test/trace"),
                qos = QualityOfService.AT_LEAST_ONCE,
                payload = payload,
                packetIdentifier = 1,
            )
        println("  publish packetSize = ${pub.packetSize()} bytes")
        val pubSerialized = pub.serialize()
        printDirect("after 1 PUBLISH serialize (${pub.packetSize()}B)")

        // 5. 1000 serialize/deserialize cycles
        repeat(1000) {
            val s = pub.serialize()
            com.ditchoom.mqtt3.controlpacket.ControlPacketV4
                .from(s)
        }
        printDirect("after 1000 PUBLISH round-trips")
        System.gc()
        Thread.sleep(200)
        printDirect("after 1000 PUBLISH round-trips + GC")

        // 6. 10000 round-trips
        repeat(10_000) {
            val s = pub.serialize()
            com.ditchoom.mqtt3.controlpacket.ControlPacketV4
                .from(s)
        }
        printDirect("after 10000 PUBLISH round-trips")
        System.gc()
        Thread.sleep(200)
        printDirect("after 10000 PUBLISH round-trips + GC")

        // 7. 50000 round-trips
        repeat(50_000) {
            val s = pub.serialize()
            com.ditchoom.mqtt3.controlpacket.ControlPacketV4
                .from(s)
        }
        printDirect("after 50000 PUBLISH round-trips")
        System.gc()
        Thread.sleep(200)
        printDirect("after 50000 PUBLISH round-trips + GC")
    }
}
