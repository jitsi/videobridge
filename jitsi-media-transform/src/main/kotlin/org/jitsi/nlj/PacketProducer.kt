/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj

import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PacketGenerator(val ssrc: Long) {
    private var currSequenceNumber = 1
    private var packetsSinceRtp = 0

    fun generatePacket(): Packet {
        if (Random().nextInt(100) > 90) {
            // Simulate loss
            currSequenceNumber++
        }
        return if (packetsSinceRtp < 9) {
            packetsSinceRtp++
            RtpPacket.fromValues {
                header = RtpHeader.fromValues {
                    version = 2
                    hasPadding = false
                    hasExtension = false
                    csrcCount = 3
                    marker = true
                    payloadType = 96
                    sequenceNumber = currSequenceNumber
                    timestamp = 98765
                    ssrc = this@PacketGenerator.ssrc
                    csrcs = listOf(1, 2, 3)
                    extensions = mapOf()
                }
                payload = ByteBuffer.allocate(50)
            }
        } else {
            packetsSinceRtp = 0
            RtcpSrPacket.fromValues {
                header = RtcpHeader.fromValues {
                    version = 2
                    hasPadding = false
                    reportCount = 2
                    payloadType = 200
                    length = 42
                    senderSsrc = this@PacketGenerator.ssrc
                }
            }
        }
    }
}

// Packet producer simulates the producer which takes the packets from wherever
// single place they came from (e.g. the socket) and demuxes them to the different
// incoming tracks/receivers (i.e. demuxes based on source port or ssrc or whatever)
class PacketProducer {
    val sources = mutableMapOf<Long, PacketGenerator>()
    val destinations = mutableMapOf<PacketPredicate, ((Packet) -> Unit)>()
    var packetsWritten = 0
    var running = false

    fun addSource(ssrc: Long) {
        sources[ssrc] = PacketGenerator(ssrc)
    }

    fun addDestination(predicate: PacketPredicate, handler: (Packet) -> Unit) {
        destinations[predicate] = handler
    }

    fun run() {
        running = true
        thread {
            while (running) {
                sources.forEach { ssrc, generator ->
                    val packet = generator.generatePacket()
                    destinations.forEach { pred, dest ->
                        if (pred(packet)) {
                            dest.invoke(packet)
                            packetsWritten++
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
    }
}
