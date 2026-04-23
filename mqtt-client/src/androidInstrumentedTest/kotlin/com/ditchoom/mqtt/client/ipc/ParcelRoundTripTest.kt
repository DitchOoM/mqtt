package com.ditchoom.mqtt.client.ipc

import android.os.Parcel
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.ParcelableSharedMemoryBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.shared
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolates the JvmBuffer/ParcelableSharedMemoryBuffer AIDL roundtrip so the
 * IPC layer's `MqttMessageCallback.onMessage(buffer)` can be diagnosed
 * independent of the MqttManagerService, the broker, and the test runner's
 * sync machinery.
 *
 * Trigger bug: mqtt-client `testIpcAllTypesOverTcp` hangs with
 * `java.nio.BufferUnderflowException` on the first `readByte()` call of a
 * CONNACK that was parceled across Binder. The service writes a valid
 * 4-byte CONNACK via `BufferFactory.shared()` (a ParcelableSharedMemoryBuffer).
 * On the receiving side the buffer appears to have position >= limit, so the
 * decoder underflows.
 *
 * These tests exercise `Parcel.obtain()` → `writeParcelable()` → reset → `readParcelable()`
 * by hand, which is exactly what Binder does under AIDL but with no RPC.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class ParcelRoundTripTest {
    /**
     * Baseline: a managed-memory buffer (no SharedMemory) round-trips intact.
     * If this fails, the non-SharedMemory JvmBuffer parcel path is broken.
     */
    @Test
    fun managedJvmBufferRoundTripPreservesBytes() {
        val original = BufferFactory.managed().allocate(4) as JvmBuffer
        original.writeByte(0x20.toByte()) // CONNACK byte1
        original.writeByte(0x02.toByte()) // remaining length = 2
        original.writeByte(0x00.toByte()) // flags
        original.writeByte(0x00.toByte()) // return code = accepted
        original.resetForRead()

        val restored = roundTripThroughParcel(original)

        assertEquals("position", 0, restored.position())
        assertEquals("limit", 4, restored.limit())
        assertEquals("remaining", 4, restored.remaining())
        assertEquals(0x20.toByte(), restored.readByte())
        assertEquals(0x02.toByte(), restored.readByte())
        assertEquals(0x00.toByte(), restored.readByte())
        assertEquals(0x00.toByte(), restored.readByte())
    }

    /**
     * The smoking gun test: a SharedMemory buffer (what the service actually
     * sends via `BufferFactory.shared()`) round-trips intact.
     */
    @Test
    fun sharedMemoryBufferRoundTripPreservesBytes() {
        val original = BufferFactory.shared().allocate(4) as JvmBuffer
        assertTrue(
            "test precondition: BufferFactory.shared() on this device should return " +
                "ParcelableSharedMemoryBuffer",
            original is ParcelableSharedMemoryBuffer,
        )
        original.writeByte(0x20.toByte())
        original.writeByte(0x02.toByte())
        original.writeByte(0x00.toByte())
        original.writeByte(0x00.toByte())
        original.resetForRead()

        val restored = roundTripThroughParcel(original)

        assertEquals("position after roundtrip", 0, restored.position())
        assertEquals("limit after roundtrip", 4, restored.limit())
        assertEquals("remaining after roundtrip", 4, restored.remaining())
        assertEquals("byte 0 (CONNACK header)", 0x20.toByte(), restored.readByte())
        assertEquals("byte 1 (remaining length)", 0x02.toByte(), restored.readByte())
        assertEquals("byte 2 (flags)", 0x00.toByte(), restored.readByte())
        assertEquals("byte 3 (return code)", 0x00.toByte(), restored.readByte())
    }

    /**
     * End-to-end: serialize a real CONNACK via BufferFactory.shared(), parcel
     * it, unparcel it, and parse back. This is exactly the pipeline
     * `MqttMessageCallback.onMessage` drives — minus the Binder RPC itself.
     */
    @Test
    fun connAckSerializedSharedThenParceledThenParsedMatches() {
        val original = ConnectionAcknowledgment()
        val serialized = original.serialize(BufferFactory.shared()) as JvmBuffer
        assertTrue(
            serialized is ParcelableSharedMemoryBuffer,
        )

        val restored = roundTripThroughParcel(serialized)
        // NO resetForRead() here — .serialize(factory) already did it before parcel.
        // Double-flipping post-parcel collapses limit to 0 and readByte() underflows.
        // This was the AndroidRemoteMqttClient.awaitConnectivity bug.

        val parsed = ControlPacketV4Factory.from(restored)
        assertNotNull(parsed)
        assertEquals(
            "parsed back to an equivalent CONNACK",
            original,
            parsed,
        )
    }

    /**
     * Covers the publish path — the other site where the double-reset bug
     * silently dropped messages inside RemoteMqttClientWorker.onPublishQueued.
     * Mirrors how AndroidRemoteMqttClient.sendPublish ships a buffer across
     * IPC: client serializes PUBLISH → AIDL parcel → server parses.
     */
    @Test
    fun publishSerializedSharedThenParceledThenParsedMatches() {
        val payload = BufferFactory.managed().allocate(5)
        payload.writeByte('h'.code.toByte())
        payload.writeByte('e'.code.toByte())
        payload.writeByte('l'.code.toByte())
        payload.writeByte('l'.code.toByte())
        payload.writeByte('o'.code.toByte())
        payload.resetForRead()

        val original =
            PublishMessageV4.ofRaw(
                topic = TopicName.fromOrThrow("t/regress"),
                qos = QualityOfService.AT_MOST_ONCE,
                payload = payload,
            )
        val serialized = original.serialize(BufferFactory.shared()) as JvmBuffer
        val restored = roundTripThroughParcel(serialized)
        // NO resetForRead() — see connAckSerializedSharedThenParceledThenParsedMatches

        val parsed = ControlPacketV4Factory.from(restored)
        assertNotNull(parsed)
        assertEquals(
            "parsed back to an equivalent PUBLISH",
            original,
            parsed,
        )
    }

    /**
     * Regression guard for the double-reset bug: if a caller forgets the
     * above invariant and calls `resetForRead()` after parcel, the buffer
     * becomes unreadable. Anchors the invariant in test form so a future
     * refactor that reintroduces the bug fails here with a clear diagnostic,
     * not by hanging across Binder.
     */
    @Test
    fun doubleResetForReadAfterParcelEmptiesBuffer() {
        val original = ConnectionAcknowledgment()
        val serialized = original.serialize(BufferFactory.shared()) as JvmBuffer
        val restored = roundTripThroughParcel(serialized)
        assertEquals("before the buggy reset: 4 bytes readable", 4, restored.remaining())

        restored.resetForRead() // the bug: flip on an already-ready buffer

        assertEquals(
            "after double-reset: 0 bytes remaining — this is the bug",
            0,
            restored.remaining(),
        )
    }

    /**
     * `onMessage` on the receiving side would see a fresh parcel roundtrip,
     * not the original buffer reference. The restored buffer must be a
     * distinct instance — confirms `JvmBuffer.CREATOR` actually ran.
     */
    @Test
    fun roundTripProducesDistinctInstance() {
        val original = BufferFactory.shared().allocate(4) as JvmBuffer
        original.writeByte(1); original.writeByte(2); original.writeByte(3); original.writeByte(4)
        original.resetForRead()

        val restored = roundTripThroughParcel(original)

        assertNotSame("CREATOR must produce a new instance, not alias", original, restored)
    }

    private fun roundTripThroughParcel(buffer: JvmBuffer): JvmBuffer {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(buffer, 0)
            parcel.setDataPosition(0)
            val restored =
                parcel.readParcelable(JvmBuffer::class.java.classLoader, JvmBuffer::class.java)
            return requireNotNull(restored) { "readParcelable returned null for a buffer we just wrote" }
        } finally {
            parcel.recycle()
        }
    }
}
