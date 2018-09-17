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

import org.bouncycastle.crypto.tls.TlsContext
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.toHex
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.event.CsrcAudioLevelListener
import org.jitsi.service.neomedia.format.MediaFormat
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

// This is an API class, so its usages will largely be outside of this library
@Suppress("unused")
/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written to [incomingQueue].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead (then we could have all senders push to a single queue and
 * have the one thread just read from the queue and send, rather than that thread
 * having to read from a bunch of individual queues)
 */
class Transceiver(
    private val id: String,
    private val executor: ExecutorService /*= Executors.newSingleThreadExecutor()*/
) {
    protected val logger = getLogger(this.javaClass)
    private val rtpExtensions = mutableMapOf<Byte, RTPExtension>()
    private val payloadTypes = mutableMapOf<Byte, MediaFormat>()
    private val receiveSsrcs = ConcurrentHashMap.newKeySet<Long>()

    /*private*/ val rtpSender: RtpSender = RtpSenderImpl(123, executor)
    /*private*/ val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            123,
            { rtcpPacket ->
                rtpSender.sendRtcp(listOf(rtcpPacket))
            },
            executor)

    private val incomingChain: PacketHandler

    val incomingQueue = LinkedBlockingQueue<Packet>()
    val outgoingQueue = LinkedBlockingQueue<Packet>()

    var running = true

    init {
        logger.cinfo { "Transceiver ${this.hashCode()} using executor ${executor.hashCode()}" }
        incomingChain = rtpReceiver

        // rewire the sender's hacked packet handler
        rtpSender.packetSender = object : Node("RTP packet sender") {
            override fun doProcessPackets(p: List<PacketInfo>) {
                // Map the PacketInfo types to their contained Packet and add to the outgoing queue
                outgoingQueue.addAll(p.map { it.packet }) }
            }
        rtpReceiver.setNackHandler(rtpSender.getNackHandler())

        scheduleWork()
    }

    fun sendPackets(p: List<PacketInfo>) {
        rtpSender.sendPackets(p)
    }

    private fun scheduleWork() {
        executor.execute {
            val packets = mutableListOf<Packet>()
            incomingQueue.drainTo(packets, 5)
            if (packets.isNotEmpty()) incomingChain.processPackets(packets.map { PacketInfo(it) })
            if (running) {
                scheduleWork()
            }
        }
    }

    fun addReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} adding receive ssrc $ssrc" }
        receiveSsrcs.add(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcAddedEvent(ssrc))
        //TODO: fire events to rtp sender as well
    }

    fun removeReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} removing receive ssrc $ssrc" }
        receiveSsrcs.remove(ssrc)
        rtpReceiver.handleEvent(ReceiveSsrcRemovedEvent(ssrc))
    }

    fun receivesSsrc(ssrc: Long): Boolean = receiveSsrcs.contains(ssrc)

    fun setRtpEncodings(rtpEncodings: Array<RTPEncodingDesc>) {
        val event = RtpEncodingsEvent(Arrays.asList(*rtpEncodings))
        rtpReceiver.handleEvent(event)
        rtpSender.handleEvent(event)
    }

    fun addDynamicRtpPayloadType(rtpPayloadType: Byte, format: MediaFormat) {
        payloadTypes[rtpPayloadType] = format
        logger.cinfo { "Payload type added: $rtpPayloadType -> $format" }
        val rtpPayloadTypeAddedEvent = RtpPayloadTypeAddedEvent(rtpPayloadType, format)
        rtpReceiver.handleEvent(rtpPayloadTypeAddedEvent)
        rtpSender.handleEvent(rtpPayloadTypeAddedEvent)
    }

    fun clearDynamicRtpPayloadTypes() {
        logger.cinfo { "All payload types being cleared" }
        val rtpPayloadTypeClearEvent = RtpPayloadTypeClearEvent()
        rtpReceiver.handleEvent(rtpPayloadTypeClearEvent)
        rtpSender.handleEvent(rtpPayloadTypeClearEvent)
        payloadTypes.clear()
    }

    fun addDynamicRtpPayloadTypeOverride(originalPt: Byte, overloadPt: Byte) {
        //TODO
        logger.cinfo { "Overriding payload type $originalPt to $overloadPt" }
    }

    fun addRtpExtension(extensionId: Byte, rtpExtension: RTPExtension) {
        logger.cinfo { "Adding RTP extension: $extensionId -> $rtpExtension" }
        rtpExtensions[extensionId] = rtpExtension
        val rtpExtensionAddedEvent = RtpExtensionAddedEvent(extensionId, rtpExtension)
        rtpReceiver.handleEvent(rtpExtensionAddedEvent)
        rtpSender.handleEvent(rtpExtensionAddedEvent)
    }

    fun clearRtpExtensions() {
        logger.cinfo { "Clearing all RTP extensions" }
        val rtpExtensionClearEvent = RtpExtensionClearEvent()
        rtpReceiver.handleEvent(rtpExtensionClearEvent)
        rtpSender.handleEvent(rtpExtensionClearEvent)
        rtpExtensions.clear()
    }

    fun setCsrcAudioLevelListener(csrcAudioLevelListener: CsrcAudioLevelListener) {
        logger.cinfo { "BRIAN: transceiver setting csrc audio level listener on receiver" }
        rtpReceiver.setCsrcAudioLevelListener(csrcAudioLevelListener)
    }

    fun addSsrcAssociation(primarySsrc: Long, secondarySsrc: Long, type: String) {
        val ssrcAssociationEvent = SsrcAssociationEvent(primarySsrc, secondarySsrc, type)
        rtpReceiver.handleEvent(ssrcAssociationEvent)
        rtpSender.handleEvent(ssrcAssociationEvent)
    }

    fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsContext: TlsContext) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        val keyingMaterial = SrtpUtil.getKeyingMaterial(tlsContext, srtpProfileInfo)
        logger.cinfo { "Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "keyingMaterial:\n${ByteBuffer.wrap(keyingMaterial).toHex()}\n" +
                "tls role: ${TlsRole.fromTlsContext(tlsContext)}" }
        val srtpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            false
        )
        val srtcpTransformer = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            TlsRole.fromTlsContext(tlsContext),
            true
        )

        rtpReceiver.setSrtpTransformer(srtpTransformer)
        rtpReceiver.setSrtcpTransformer(srtcpTransformer)
        rtpSender.setSrtpTransformer(srtpTransformer)
        rtpSender.setSrtcpTransformer(srtcpTransformer)
    }
}
