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

import org.jitsi.rtp.Packet
import java.time.Duration

fun Duration.toMicros(): Long {
    return this.nano / 1000L
}

class EventTimeline(
    private val timeline: MutableList<Pair<String, Long>> = mutableListOf()
) : Iterable<Pair<String, Long>> {
    /**
     * The [referenceTime] refers to the first timestamp we have
     * in the timeline.  In the timeline this is used as time "0" and
     * all other times are represented as deltas from this 0.
     */
    private var referenceTime: Long? = null

    fun addEvent(desc: String) {
        val now = System.nanoTime()
        if (referenceTime == null) {
            referenceTime = now
        }
        timeline.add(desc to (Duration.ofNanos(now - referenceTime!!).toMicros()))
    }

    fun clone(): EventTimeline = EventTimeline(timeline.toMutableList())

    override fun iterator(): Iterator<Pair<String, Long>> = timeline.iterator()

    override fun toString(): String {
        return with (StringBuffer()) {
            timeline.forEach {
                appendln(it.toString())
            }
            toString()
        }
    }
}

/**
 * [PacketInfo] is a wrapper around a [Packet] instance to be passed through
 * a pipeline.  Since the [Packet] can change as it moves through the pipeline
 * (as it is parsed into different types), the wrapping [PacketInfo] stays consistent
 * and allows for metadata to be passed along with a packet.
 */
class PacketInfo @JvmOverloads constructor(
    var packet: Packet,
    val metaData: MutableMap<Any, Any> = mutableMapOf(),
    val timeline: EventTimeline = EventTimeline()
    ) {
    /**
     * Get the contained packet cast to [ExpectedPacketType]
     */
    @Suppress("UNCHECKED_CAST")
    fun <ExpectedPacketType>packetAs(): ExpectedPacketType {
        return packet as ExpectedPacketType
    }

    /**
     * Create a deep clone of this PacketInfo (both the contained packet and the metadata map
     * will be copied for the cloned PacketInfo).
     */
    fun clone(): PacketInfo = PacketInfo(packet.clone(), metaData.toMutableMap(), timeline.clone())

    fun addEvent(desc: String) = timeline.addEvent(desc)
}

/**
 * This is a specialization of [org.jitsi.nlj.util.forEachAs] method which makes it easier
 * to operate on lists of [PacketInfo] when the caller wants to treat the contained [Packet]
 * as a specific packet type.  This method iterates over the iterable of [PacketInfo]s and calls
 * the given lambda with the [PacketInfo] instance and the contained [Packet] instance, cast
 * as [ExpectedPacketType].  This will throw if the cast attempt is unsuccessful.
 */
@Suppress("UNCHECKED_CAST")
inline fun <ExpectedPacketType> Iterable<PacketInfo>.forEachAs(action: (PacketInfo, ExpectedPacketType) -> Unit) {
    for (element in this) action (element, element.packet as ExpectedPacketType)
}
