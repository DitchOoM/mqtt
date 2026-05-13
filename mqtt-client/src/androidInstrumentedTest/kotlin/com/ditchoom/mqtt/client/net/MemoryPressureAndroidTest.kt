package com.ditchoom.mqtt.client.net

import android.os.Debug
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4 as PublishV4
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest as SubscribeV4

/**
 * Android-instrumented mirror of MemoryPressureTest.pooledFactoryWritePath.
 *
 * The JVM (Hotspot) version surfaced a leak regression on 2026-05-12 that
 * was a buffer-codec / pool-lifecycle bug (slice through PooledBuffer never
 * released the chunk's refcount). The fix is portable common code, but
 * production MQTT runs on Android (ART) and Hotspot's heap-commit behavior
 * doesn't translate directly. This test profiles the same hot path on ART
 * so we can decide whether the JVM-Hotspot-residual 37 MB RSS growth
 * reproduces on Android, or whether ART's aggressive GC absorbs it.
 *
 * Reads:
 *   - `Debug.MemoryInfo` — PSS, native heap allocated, dalvik heap, code.
 *     Authoritative for "what's the JVM-side & native footprint" on Android.
 *   - `/proc/self/status` VmRSS — process RSS, comparable to the JVM
 *     test's RSS metric.
 *   - `Runtime.totalMemory()/freeMemory()` — Kotlin/JVM heap snapshot.
 *   - `BufferPool.stats()` — pool hit rate and current size.
 */
class MemoryPressureAndroidTest {
    private data class MemSnapshot(
        val javaHeapMB: Double,
        val nativeHeapMB: Double,
        val pssMB: Double,
        val rssMB: Double,
    ) {
        override fun toString() =
            "javaHeap=%.1fMB nativeHeap=%.1fMB pss=%.1fMB rss=%.1fMB".format(
                javaHeapMB,
                nativeHeapMB,
                pssMB,
                rssMB,
            )
    }

    private fun snapshot(): MemSnapshot {
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        Thread.sleep(200)
        repeat(2) {
            System.gc()
            Thread.sleep(50)
        }

        val rt = Runtime.getRuntime()
        val javaHeapMB = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)

        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        val pssMB = info.totalPss / 1024.0

        val rssMB =
            try {
                val status = File("/proc/self/status").readText()
                val vmRss = status.lines().firstOrNull { it.startsWith("VmRSS:") }
                vmRss
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.div(1024.0) ?: -1.0
            } catch (_: Exception) {
                -1.0
            }

        return MemSnapshot(javaHeapMB, nativeHeapMB, pssMB, rssMB)
    }

    private fun buildV4Connect(): ControlPacket = ConnectV4(clientId = "pressure-android-client")

    private fun buildV4Publish(id: Int): ControlPacket {
        val payload = BufferFactory.Default.allocate(128)
        repeat(128) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return PublishV4.ofRaw(
            topic = TopicName.fromOrThrow("pressure/test/topic"),
            qos = QualityOfService.AT_LEAST_ONCE,
            payload = payload,
            packetIdentifier = id,
        )
    }

    private fun buildV4Subscribe(): ControlPacket =
        SubscribeV4(
            packetIdentifier = 1.toUShort(),
            topic = "pressure/+/topic",
            qos = QualityOfService.AT_LEAST_ONCE,
        )

    @Test
    fun pooledFactoryWritePathOnArt() {
        val pool = BufferPool()
        val factory = BufferFactory.Default.withPooling(pool)
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Subscribe())
        val iterations = 50_000

        // Warmup
        repeat(1000) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val before = snapshot()

        repeat(iterations) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val after = snapshot()
        val stats = pool.stats()
        val packetCount = iterations * packets.size

        println("[android-pooled-factory-write $packetCount packets] before: $before")
        println("[android-pooled-factory-write $packetCount packets] after:  $after")
        println(
            "[android-pooled-factory-write $packetCount packets] delta:  " +
                "javaHeap=%+.1fMB nativeHeap=%+.1fMB pss=%+.1fMB rss=%+.1fMB".format(
                    after.javaHeapMB - before.javaHeapMB,
                    after.nativeHeapMB - before.nativeHeapMB,
                    after.pssMB - before.pssMB,
                    after.rssMB - before.rssMB,
                ),
        )
        println(
            "[android-pooled-factory-write] hitRate=%.1f%%  poolSize=%d  peak=%d".format(
                stats.hitRate * 100,
                stats.currentPoolSize,
                stats.peakPoolSize,
            ),
        )

        // Native heap is the ART-equivalent of "off-heap memory accumulation" —
        // this is what the JVM-side test calls 'direct' memory. Pool reuse
        // should keep this flat.
        val nativeHeapGrowth = after.nativeHeapMB - before.nativeHeapMB
        assertTrue(
            nativeHeapGrowth < 20.0,
            "Native heap grew %.1fMB — pool reuse failed".format(nativeHeapGrowth),
        )
        assertTrue(
            stats.hitRate > 0.9,
            "Pool hit rate %.1f%% — buffers not reused".format(stats.hitRate * 100),
        )

        pool.clear()
    }
}
