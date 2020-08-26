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

package org.jitsi.videobridge

import org.jitsi.nlj.util.NEVER
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

class JvbLoadManager<T : JvbLoadMeasurement> @JvmOverloads constructor(
    private val jvbLoadThreshold: T,
    private val jvbRecoveryThreshold: T,
    private val loadReducer: JvbLoadReducer,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = createLogger(minLogLevel = Level.ALL)

    private var lastReducerTime: Instant = NEVER

    fun loadUpdate(loadMeasurement: T) {
        logger.cdebug { "Got a load measurement of $loadMeasurement" }
        if (loadMeasurement.getLoad() >= jvbLoadThreshold.getLoad()) {
            if (Duration.between(lastReducerTime, clock.instant()) >= loadReducer.impactTime()) {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                        "running load reducer")
                loadReducer.reduceLoad()
                lastReducerTime = clock.instant()
            } else {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                        "but load reducer started running ${Duration.between(lastReducerTime, clock.instant())} " +
                        "ago, and we wait ${loadReducer.impactTime()} between runs")
            }
        } else if (loadMeasurement.getLoad() < jvbRecoveryThreshold.getLoad()) {
            if (Duration.between(lastReducerTime, clock.instant()) >= loadReducer.impactTime()) {
                logger.info("Load measurement $loadMeasurement is above threshold of $jvbLoadThreshold, " +
                        "running recovery")
                loadReducer.recover()
                lastReducerTime = clock.instant()
            }
        }
    }
}

interface JvbLoadMeasurement {
    fun getLoad(): Double
}

class PacketRateMeasurement(private val packetRate: Long) : JvbLoadMeasurement {
    override fun getLoad(): Double = packetRate.toDouble()

    override fun toString(): String = "RTP packet rate (up + down) of $packetRate pps"
}

val PacketRateLoadedThreshold = PacketRateMeasurement(50000)
val PacketRateRecoveryThreshold = PacketRateMeasurement(40000)

interface JvbLoadReducer {
    fun reduceLoad()
    fun recover()
    fun impactTime(): Duration
}

class LastNReducer @JvmOverloads constructor(
    private val videobridge: Videobridge,
    private val jvbLastN: JvbLastN,
    private val reductionScale: Double,
    private val recoverScale: Double = 1 / reductionScale
) : JvbLoadReducer {
    private val logger = createLogger()

    private fun getMaxForwardedEps(): Int? {
        return videobridge.conferences
            .flatMap { it.endpoints }
            .asSequence()
            .filterIsInstance<Endpoint>()
            .map {
                it.numForwardedEndpoints()
            }
            .max()
    }

    override fun reduceLoad() {
        // Find the highest amount of endpoints any endpoint on this bridge is forwarding video for
        // so we can set a new last-n number to something lower
        val maxForwardedEps = getMaxForwardedEps() ?: run {
            logger.info("No endpoints with video being forwarded, can't reduce load by reducing last n")
            return
        }

        val newLastN = (maxForwardedEps * reductionScale).toInt()
        logger.info("Largest number of forwarded videos was $maxForwardedEps, A last-n value of $newLastN is " +
                "being enforced to reduce bridge load")

        jvbLastN.jvbLastN = newLastN
    }

    override fun recover() {
        val currLastN = jvbLastN.jvbLastN
        if (currLastN == -1) {
            logger.cdebug { "No recovery necessary, no JVB last-n is set" }
            return
        }
        val newLastN = (currLastN * recoverScale).toInt()
        logger.info("JVB last-n was $currLastN, increasing to $newLastN as part of load recovery")
        jvbLastN.jvbLastN = newLastN
    }

    override fun impactTime(): Duration = Duration.ofMinutes(1)

    override fun toString(): String = "LastNReducer with scale $reductionScale"
}

class PacketRateLoadSampler(
    private val videobridge: Videobridge,
    private val jvbLoadManager: JvbLoadManager<PacketRateMeasurement>
) : Runnable {

    override fun run() {
        var totalPacketRate: Long = 0
        videobridge.conferences.forEach { conf ->
            conf.localEndpoints.forEach { ep ->
                with(ep.transceiver.getTransceiverStats()) {
                    totalPacketRate += incomingPacketStreamStats.packetRate
                    totalPacketRate += outgoingPacketStreamStats.packetRate
                }
            }
        }
        jvbLoadManager.loadUpdate(PacketRateMeasurement(totalPacketRate))
    }
}
