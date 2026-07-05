package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Codec
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Codec

/**
 * Test decode helpers replacing the retired `ControlPacketV{4,5}.from(buffer)` companions.
 * PUBLISH application bytes are carried in an [OpaquePublishPayload] (Pattern #2 — consumer-
 * owned `PlatformBuffer`, byte-exact), matching what `from()` did.
 *
 * The codec instances are cached at top level so the throughput / memory-pressure benchmarks
 * that call these in hot loops don't allocate a codec per decode (mirroring `from()`'s cached
 * `ControlPacketV{4,5}OpaqueWireCodec`). Unlike the models-module `decodeV4` / `decodeV5`
 * helpers, these do NOT remap `DecodeException → MalformedPacketException` — the benchmark
 * callers only ever feed valid packets, so the raw codec path is the faithful (and fastest)
 * substitute.
 */
private val opaqueV4Codec = ControlPacketV4Codec(OpaquePublishPayloadCodec)
private val opaqueV5Codec = ControlPacketV5Codec(OpaquePublishPayloadCodec)

internal fun decodeV4Opaque(buffer: ReadBuffer): ControlPacketV4<OpaquePublishPayload> = opaqueV4Codec.decode(buffer, DecodeContext.Empty)

internal fun decodeV5Opaque(buffer: ReadBuffer): ControlPacketV5<OpaquePublishPayload> = opaqueV5Codec.decode(buffer, DecodeContext.Empty)
