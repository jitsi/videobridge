/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.cc

import com.google.common.collect.ImmutableMap
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.videobridge.VideoConstraints
import java.time.Instant

class BitrateControllerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val logger = createLogger()

    init {

        context("Effective constraints") {
            val conferenceEndpoints = List(5) { i -> Endpoint("endpoint-${i + 1}") }

            context("When nothing is specified (expect 180p)") {
                val lastN = -1
                val videoConstraints = mapOf("endpoint-1" to VideoConstraints(720))

                BitrateController.makeEndpointMultiRankList(conferenceEndpoints, videoConstraints, lastN).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints(720),
                        "endpoint-2" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-3" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-4" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-5" to VideoConstraints.thumbnailVideoConstraints
                    )
                )
            }

            context("With LastN") {
                val lastN = 3
                val videoConstraints = mapOf<String, VideoConstraints>()

                BitrateController.makeEndpointMultiRankList(conferenceEndpoints, videoConstraints, lastN).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-2" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-3" to VideoConstraints.thumbnailVideoConstraints,
                        "endpoint-4" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-5" to VideoConstraints.disabledVideoConstraints
                    )
                )
            }

            context("With explicitly selected ep outside LastN") {
                // This replicates what the client's low-bandwidth mode does when there is a screenshare -
                // it explicitly selects only the share, ignoring the last-N list.
                val lastN = 1
                val videoConstraints = mapOf("endpoint-2" to VideoConstraints(1080))
                BitrateController.makeEndpointMultiRankList(conferenceEndpoints, videoConstraints, lastN).map {
                    it.endpoint.id to it.effectiveVideoConstraints
                }.toMap().shouldContainExactly(
                    mapOf(
                        "endpoint-1" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-2" to VideoConstraints(1080),
                        "endpoint-3" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-4" to VideoConstraints.disabledVideoConstraints,
                        "endpoint-5" to VideoConstraints.disabledVideoConstraints
                    )
                )
            }
        }

        context("Allocation") {
            val clock = FakeClock()
            val bc = BitrateControllerWrapper("A", "B", "C", "D", clock = clock)
            val vcc = VideoConstraintsCompatibility()

            context("Stage view") {
                bc.setEndpointOrdering("A", "B", "C", "D")
                bc.setVideoConstraints(vcc.stageView("A"))

                for (bwe in 0..5_000_000 step 10_000) {
                    bc.bwe = bwe.bps
                    clock.elapse(100.ms)
                }
                logger.info("Forwarded endpoints history: ${bc.forwardedEndpointsHistory}")
                logger.info("Effective constraints history: ${bc.effectiveConstraintsHistory}")
                logger.info("Allocation history: ${bc.allocationHistory}")

                // At this stage the purpose of this is just to document current behavior.
                // The change from [A] to [] looks like a bug
                bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
                    listOf("A"),
                    emptyList(),
                    listOf("A"),
                    listOf("A", "B"),
                    listOf("A", "B", "C"),
                    listOf("A", "B", "C", "D")
                )

                // At this stage the purpose of this is just to document current behavior.
                // Skip the allocations for bwe=-1, bwe=0. They seem like a bug.
                bc.allocationHistory.removeIf { it.bwe <= 0.bps }

                bc.allocationHistory.shouldMatchInOrder(
                    Event(
                        10.kbps,
                        listOf(
                            AllocationInfo("A", ld7_5, oversending = true),
                            AllocationInfo("B", noVideo),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        100.kbps,
                        listOf(
                            AllocationInfo("A", ld15),
                            AllocationInfo("B", noVideo),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        150.kbps,
                        listOf(
                            AllocationInfo("A", ld30),
                            AllocationInfo("B", noVideo),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        550.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        600.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        650.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        700.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        750.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        800.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        850.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        900.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        960.kbps,
                        listOf(
                            AllocationInfo("A", sd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld30)
                        )
                    ),
                    Event(
                        2150.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        2200.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        2250.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        2300.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        2350.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        2400.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        2460.kbps,
                        listOf(
                            AllocationInfo("A", hd30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld30)
                        )
                    )
                )
            }
            context("Tile view") {
                bc.setEndpointOrdering("A", "B", "C", "D")
                bc.setVideoConstraints(vcc.tileView("A", "B", "C", "D"))

                for (bwe in 0..5_000_000 step 10_000) {
                    bc.bwe = bwe.bps
                    clock.elapse(100.ms)
                }
                logger.info("Forwarded endpoints history: ${bc.forwardedEndpointsHistory}")
                logger.info("Effective constraints history: ${bc.effectiveConstraintsHistory}")
                logger.info("Allocation history: ${bc.allocationHistory}")

                // At this stage the purpose of this is just to document current behavior.
                // The change from [A] to [] looks like a bug
                bc.forwardedEndpointsHistory.map { it.event }.shouldContainInOrder(
                    listOf("A"),
                    emptyList(),
                    listOf("A"),
                    listOf("A", "B"),
                    listOf("A", "B", "C"),
                    listOf("A", "B", "C", "D")
                )

                // At this stage the purpose of this is just to document current behavior.
                // Skip the allocations for bwe=-1, bwe=0. They seem like a bug.
                bc.allocationHistory.removeIf { it.bwe <= 0.bps }

                bc.allocationHistory.shouldMatchInOrder(
                    Event(
                        10.kbps,
                        listOf(
                            AllocationInfo("A", ld7_5, oversending = true),
                            AllocationInfo("B", noVideo),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        100.kbps,
                        listOf(
                            AllocationInfo("A", ld7_5),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", noVideo),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        150.kbps,
                        listOf(
                            AllocationInfo("A", ld7_5),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", noVideo)
                        )
                    ),
                    Event(
                        200.kbps,
                        listOf(
                            AllocationInfo("A", ld7_5),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        250.kbps,
                        listOf(
                            AllocationInfo("A", ld15),
                            AllocationInfo("B", ld7_5),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        300.kbps,
                        listOf(
                            AllocationInfo("A", ld15),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld7_5),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        350.kbps,
                        listOf(
                            AllocationInfo("A", ld15),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld7_5)
                        )
                    ),
                    Event(
                        400.kbps,
                        listOf(
                            AllocationInfo("A", ld15),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        450.kbps,
                        listOf(
                            AllocationInfo("A", ld30),
                            AllocationInfo("B", ld15),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        500.kbps,
                        listOf(
                            AllocationInfo("A", ld30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld15),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        550.kbps,
                        listOf(
                            AllocationInfo("A", ld30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld15)
                        )
                    ),
                    Event(
                        610.kbps,
                        listOf(
                            AllocationInfo("A", ld30),
                            AllocationInfo("B", ld30),
                            AllocationInfo("C", ld30),
                            AllocationInfo("D", ld30)
                        )
                    )
                )
            }
        }
    }
}

fun List<Event<List<AllocationInfo>>>.shouldMatchInOrder(vararg events: Event<List<AllocationInfo>>) {
    events.size shouldBe size
    events.forEachIndexed { i, it ->
        this[i].bwe shouldBe it.bwe
        this[i].event.shouldContainExactly(it.event)
        // Ignore this.time
    }
}

private class BitrateControllerWrapper(vararg endpointIds: String, val clock: FakeClock = FakeClock()) {
    val endpoints: List<Endpoint> = createEndpoints(*endpointIds)
    val logger = createLogger()

    var bwe = (-1).bps
        set(value) {
            logger.debug("Setting bwe=$bwe")
            bc.bandwidthChanged(bwe.bps.toLong())
            field = value
        }

    // Save the output.
    val effectiveConstraintsHistory: History<ImmutableMap<String, VideoConstraints>> = mutableListOf()
    val forwardedEndpointsHistory: History<Collection<String>> = mutableListOf()
    val allocationHistory: History<List<AllocationInfo>> = mutableListOf()

    val bc = BitrateController<Endpoint>(
        "destinationEndpoint",
        object : BitrateController.EventHandler {
            override fun forwardedEndpointsChanged(forwardedEndpoints: Collection<String>) {
                Event(bwe, forwardedEndpoints, clock.instant()).apply {
                    logger.info("Forwarded endpoints changed: $this")
                    forwardedEndpointsHistory.add(this)
                }
            }

            override fun effectiveVideoConstraintsChanged(
                oldVideoConstraints: ImmutableMap<String, VideoConstraints>,
                newVideoConstraints: ImmutableMap<String, VideoConstraints>
            ) {
                Event(bwe, newVideoConstraints, clock.instant()).apply {
                    logger.info("Effective constraints changed: $this")
                    effectiveConstraintsHistory.add(this)
                }
            }

            override fun keyframeNeeded(endpointId: String?, ssrc: Long) { }

            override fun allocationChanged(allocation: List<BitrateController.SourceBitrateAllocation>) {
                Event(bwe, allocation.map { it.toEndpointAllocationInfo() }, clock.instant()).apply {
                    logger.info("Allocation changed: $this")
                    allocationHistory.add(this)
                }
            }
        },
        { endpoints },
        DiagnosticContext(),
        logger,
        clock
    )

    fun setEndpointOrdering(vararg endpoints: String) {
        bc.endpointOrderingChanged(mutableListOf(*endpoints))
    }

    fun setVideoConstraints(videoConstraints: ImmutableMap<String, VideoConstraints>) =
        bc.setVideoConstraints(videoConstraints)

    init {
        // The BC only starts working 10 seconds after it first received media, so fake that.
        bc.transformRtp(PacketInfo(VideoRtpPacket(ByteArray(100), 0, 100)))
        clock.elapse(15.secs)

        // Adaptivity is disabled when RTX support is not signalled.
        bc.addPayloadType(RtxPayloadType(123, mapOf("apt" to "124")))
    }
}

typealias History<T> = MutableList<Event<T>>
data class Event<T>(
    val bwe: Bandwidth,
    val event: T,
    val time: Instant = Instant.MIN
) {
    override fun toString(): String = "\n[time=${time.toEpochMilli()} bwe=$bwe] $event"
}

/**
 * Describe the layer that is currently forwarded to an endpoint in a human-readable way.
 */
data class AllocationInfo(
    val id: String,
    val height: Int,
    val fps: Double,
    val bitrate: Bandwidth,
    val oversending: Boolean = false
) {
    constructor(id: String, layer: RtpLayerDesc, oversending: Boolean = false) :
        this(id, layer.height, layer.frameRate, layer.getBitrate(0), oversending)

    override fun toString(): String =
        "\n\t[id=$id, height=$height, fps=$fps, bitrate=$bitrate oversending=$oversending]"
}

fun BitrateController.SourceBitrateAllocation.toEndpointAllocationInfo() =
    AllocationInfo(
        endpointID,
        targetLayer?.height ?: 0,
        targetLayer?.frameRate ?: 0.0,
        targetLayer?.getBitrate(0) ?: 0.bps, // 0 is fine with our Mck RtpLayerDesc
        oversending
    )

/**
 * List the normal List<T>.shouldContainInOrder, but compare elements' contents.
 */
fun <T> List<Collection<T>>.shouldContainInOrder(vararg ts: Collection<T>) {
    this.size shouldBe ts.size
    ts.forEachIndexed { i, it -> this[i].shouldContainExactly(it) }
}

private fun VideoConstraintsCompatibility.stageView(endpoint: String): ImmutableMap<String, VideoConstraints> {
    setMaxFrameHeight(720)
    setSelectedEndpoints(setOf(endpoint))
    return ImmutableMap.copyOf(computeVideoConstraints())
}

private fun VideoConstraintsCompatibility.tileView(vararg endpoints: String): ImmutableMap<String, VideoConstraints> {
    setMaxFrameHeight(180)
    setSelectedEndpoints(setOf(*endpoints))
    return ImmutableMap.copyOf(computeVideoConstraints())
}

class Endpoint(
    val id: String,
    private val mediaSource: MediaSourceDesc? = null
) : BitrateController.MediaSourceContainer {
    override fun getID() = id
    override fun getMediaSources() = mediaSource?.let { arrayOf(mediaSource) } ?: emptyArray()
}

fun createEndpoints(vararg ids: String): List<Endpoint> {
    return List(ids.size) { i ->
        Endpoint(
            ids[i],
            createSource(
                3 * i + 1,
                3 * i + 2,
                3 * i + 3
            )
        )
    }
}

fun createSource(ssrc1: Int, ssrc2: Int, ssrc3: Int): MediaSourceDesc = MediaSourceDesc(
    arrayOf(
        RtpEncodingDesc(ssrc1.toLong(), arrayOf(ld7_5, ld15, ld30)),
        RtpEncodingDesc(ssrc2.toLong(), arrayOf(sd7_5, sd15, sd30)),
        RtpEncodingDesc(ssrc3.toLong(), arrayOf(hd7_5, hd15, hd30))
    )
)

fun createEncoding(ssrc: Int, height: Int, bitrate: Bandwidth): RtpEncodingDesc {
    // Give each temporal layer a third of the bitrate. The per-layer bitrates include dependencies
    val l0 = createLayer(tid = 0, height = height, frameRate = 7.5, bitrate = bitrate * 0.33)
    val l1 = createLayer(tid = 1, height = height, frameRate = 15.0, bitrate = bitrate * 0.66)
    val l2 = createLayer(tid = 2, height = height, frameRate = 30.0, bitrate = bitrate)

    return RtpEncodingDesc(ssrc.toLong(), arrayOf(l0, l1, l2))
}

val bitrateLd = 150.kbps
val bitrateSd = 500.kbps
val bitrateHd = 2000.kbps

val ld7_5
    get() = createLayer(tid = 0, height = 180, frameRate = 7.5, bitrate = bitrateLd * 0.33)
val ld15
    get() = createLayer(tid = 1, height = 180, frameRate = 15.0, bitrate = bitrateLd * 0.66)
val ld30
    get() = createLayer(tid = 2, height = 180, frameRate = 30.0, bitrate = bitrateLd)

val sd7_5
    get() = createLayer(tid = 0, height = 360, frameRate = 7.5, bitrate = bitrateSd * 0.33)
val sd15
    get() = createLayer(tid = 1, height = 360, frameRate = 15.0, bitrate = bitrateSd * 0.66)
val sd30
    get() = createLayer(tid = 2, height = 360, frameRate = 30.0, bitrate = bitrateSd)

val hd7_5
    get() = createLayer(tid = 0, height = 720, frameRate = 7.5, bitrate = bitrateHd * 0.33)
val hd15
    get() = createLayer(tid = 1, height = 720, frameRate = 15.0, bitrate = bitrateHd * 0.66)
val hd30
    get() = createLayer(tid = 2, height = 720, frameRate = 30.0, bitrate = bitrateHd)

val noVideo
    get() = createLayer(tid = -1, height = 0, frameRate = 0.0, bitrate = 0.bps)

fun createLayer(
    tid: Int,
    height: Int,
    frameRate: Double,
    /**
     * Note: this mock impl does not model the dependency layers, so the cumulative bitrate should be provided.
     */
    bitrate: Bandwidth
): RtpLayerDesc {
    val eid = 0
    val sid = -1

    val rtpLayerDesc = mockk<RtpLayerDesc>()
    every { rtpLayerDesc.eid } returns eid
    every { rtpLayerDesc.tid } returns tid
    every { rtpLayerDesc.sid } returns sid
    every { rtpLayerDesc.height } returns height
    every { rtpLayerDesc.frameRate } returns frameRate
    every { rtpLayerDesc.getBitrate(any()) } returns bitrate

    // This is copied from the real implementation.
    every { rtpLayerDesc.layerId } returns RtpLayerDesc.getIndex(0, sid, tid)
    every { rtpLayerDesc.index } returns RtpLayerDesc.getIndex(eid, sid, tid)

    return rtpLayerDesc
}
