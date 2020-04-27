/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.octo;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.util.*;
import org.jitsi.osgi.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.transport.octo.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;
import org.osgi.framework.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * The single class in the octo package which serves as a link between a
 * {@link Conference} and its Octo-related functionality.
 *
 * @author Boris Grozev
 */
public class OctoTentacle extends PropertyChangeNotifier
    implements PotentialPacketHandler, BridgeOctoTransport.IncomingOctoPacketHandler
{
    /**
     * The {@link Logger} used by the {@link OctoTentacle} class and its
     * instances to print debug information.
     */
    private final Logger logger;

    /**
     * The conference for this {@link OctoTentacle}.
     */
    private final Conference conference;

    /**
     * The {@link OctoEndpoints} instance which maintains the list of Octo
     * endpoints in the conference.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * The {@link BridgeOctoTransport} used to actually send and receive Octo packets.
     */
    private final BridgeOctoTransport bridgeOctoTransport;

    /**
     * The list of remote Octo targets.
     */
    private Set<SocketAddress> targets
            = Collections.unmodifiableSet(new HashSet<>());

    private final Map<String, IncomingOctoEpPacketHandler> incomingPacketHandlers =
        new ConcurrentHashMap<>();

    /**
     * Count the number of dropped packets and exceptions.
     */
    public static final CountingErrorHandler queueErrorCounter
        = new CountingErrorHandler();

    /**
     * The queues which pass packets to be sent.
     */
    private final Map<String, PacketInfoQueue> outgoingPacketQueues =
        new ConcurrentHashMap<>();

    /**
     * An {@link OctoTransceiver} to handle packets which originate from
     * a remote bridge (and have a special 'source endpoint ID').
     */
    private final OctoTransceiver octoTransceiver;

    /**
     * Initializes a new {@link OctoTentacle} instance.
     * @param conference the conference.
     */
    public OctoTentacle(Conference conference)
    {
        this.conference = conference;
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());
        BundleContext bundleContext = conference.getBundleContext();
        OctoRelayService octoRelayService
            = bundleContext == null ? null :
            ServiceUtils2.getService(bundleContext, OctoRelayService.class);

        if (octoRelayService == null)
        {
            throw new IllegalStateException("Couldn't get OctoRelayService");
        }

        bridgeOctoTransport = octoRelayService.getBridgeOctoTransport();
        if (bridgeOctoTransport == null)
        {
            throw new IllegalStateException("Couldn't get OctoTransport");
        }

        octoEndpoints = new OctoEndpoints(conference);
        octoTransceiver = new OctoTransceiver("tentacle-" + conference.getGid(), logger);
        octoTransceiver.setIncomingPacketHandler(conference::handleIncomingPacket);
        octoTransceiver.setOutgoingPacketHandler(new PacketHandler()
        {
            @Override
            public void processPacket(@NotNull PacketInfo packetInfo)
            {
                throw new RuntimeException("This should not be used for sending");
            }
        });

        // Some remote packets didn't originate from an endpoint, but from
        // the bridge (like keyframe requests).
        addHandler("ffffffff", new IncomingOctoEpPacketHandler()
        {
            @Override
            public void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo)
            {
                octoTransceiver.handleIncomingPacket(packetInfo);
            }
        });
    }

    /**
     * Adds a {@link PayloadType}
     */
    public void addPayloadType(PayloadType payloadType)
    {
        octoEndpoints.addPayloadType(payloadType);
    }

    /**
     * Sets the list of remote relays to send packets to.
     * @param relays the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoUtils}.
     */
    public void setRelays(Collection<String> relays)
    {
        Objects.requireNonNull(
            bridgeOctoTransport,
                "Octo requested but not configured");

        Set<SocketAddress> socketAddresses = new HashSet<>();
        for (String relay : relays)
        {
            SocketAddress socketAddress = OctoUtils.Companion.relayIdToSocketAddress(relay);
            if (socketAddress != null)
            {
                socketAddresses.add(socketAddress);
            }
        }

        setTargets(socketAddresses);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wants(PacketInfo packetInfo)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return !(packetInfo instanceof OctoPacketInfo) && !targets.isEmpty();
    }

    @Override
    public void send(PacketInfo packet)
    {
        if (packet.getEndpointId() != null)
        {
            /* We queue packets separately by their *source* endpoint.
             * This achieves parallelization while guaranteeing that we don't
             * reorder things that shouldn't be reordered.
             */
            PacketInfoQueue queue =
                outgoingPacketQueues.computeIfAbsent(packet.getEndpointId(),
                    this::createQueue);

            queue.add(packet);
        }
        else
        {
            // Packets without an endpoint ID originalted from within the bridge
            // itself and, in practice, are things like keyframe requests.  We
            // send them out directly (without queueing).
            doSend(packet);
        }
    }

    private boolean doSend(PacketInfo packetInfo)
    {
        packetInfo.sent();
        bridgeOctoTransport.sendMediaData(
            packetInfo.getPacket().getBuffer(),
            packetInfo.getPacket().getOffset(),
            packetInfo.getPacket().getLength(),
            targets,
            conference.getGid(),
            packetInfo.getEndpointId()
        );

        return true;
    }

    @Override
    public void handleMediaPacket(@NotNull OctoPacketInfo packetInfo)
    {
        IncomingOctoEpPacketHandler handler = incomingPacketHandlers.get(packetInfo.getEndpointId());
        if (handler != null)
        {
            handler.handleIncomingPacket(packetInfo);
        }
        else
        {
            // TODO: stats
            logger.info("TEMP: NO EP HANDLER FOR ID " + packetInfo.getEndpointId());
        }
    }

    @Override
    public void handleMessagePacket(@NotNull String message, @NotNull String sourceEpId)
    {
        octoEndpoints.messageTransport.onMessage(null /* source */ , message);
    }

    /**
     * Sets the list of sources and source groups which describe the RTP streams
     * we expect to receive from remote Octo relays.
     *
     * @param audioSources the list of audio sources.
     * @param videoSources the list of video sources.
     * @param videoSourceGroups the list of source groups for video.
     */
    public void setSources(
            List<SourcePacketExtension> audioSources,
            List<SourcePacketExtension> videoSources,
            List<SourceGroupPacketExtension> videoSourceGroups)
    {
        List<SourcePacketExtension> allSources = new LinkedList<>(audioSources);
        allSources.addAll(videoSources);

        // Jicofo sends an empty "source" when it wants to clear the sources.
        // This manifests as a failure to find an 'owner', hence we clear the
        // nulls here.
        Set<String> endpointIds
                = allSources.stream()
                    .map(MediaStreamTrackFactory::getOwner)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

        octoEndpoints.setEndpoints(endpointIds);

        // Create the tracks after creating the endpoints
        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                videoSources, videoSourceGroups);
        octoEndpoints.setMediaStreamTracks(tracks);

        // We only need to call this if the tracks of any endpoint actually
        // changed, but that's not easy to detect. It's safe to call it more
        // often.
        conference.endpointTracksChanged(null);

        endpointIds.forEach(endpointId ->
        {
            Map<MediaType, Set<Long>> endpointSsrcsByMediaType = new HashMap<>();
            Set<Long> epAudioSsrcs = audioSources.stream()
                    .filter(source -> endpointId.equals(MediaStreamTrackFactory.getOwner(source)))
                    .filter(Objects::nonNull)
                    .map(SourcePacketExtension::getSSRC)
                    .collect(Collectors.toSet());
            endpointSsrcsByMediaType.put(MediaType.AUDIO, epAudioSsrcs);

            Set<Long> epVideoSsrcs = videoSources.stream()
                    .filter(source -> endpointId.equals(MediaStreamTrackFactory.getOwner(source)))
                    .filter(Objects::nonNull)
                    .map(SourcePacketExtension::getSSRC)
                    .collect(Collectors.toSet());
            endpointSsrcsByMediaType.put(MediaType.VIDEO, epVideoSsrcs);

            AbstractEndpoint endpoint = conference.getEndpoint(endpointId);
            if (endpoint instanceof OctoEndpoint)
            {
                ((OctoEndpoint) endpoint).setReceiveSsrcs(endpointSsrcsByMediaType);
            }
            else
            {
                logger.warn("No OctoEndpoint for SSRCs");
            }
        });
    }

    /**
     * Called when a local endpoint is expired.
     */
    public void endpointExpired(String endpointId)
    {
        PacketInfoQueue removed = outgoingPacketQueues.remove(endpointId);
        if (removed != null)
        {
            removed.close();
        }
    }

    /**
     * Sets the list of remote addresses to send Octo packets to.
     * @param targets the list of addresses.
     */
    private void setTargets(Set<SocketAddress> targets)
    {
        if (!targets.equals(this.targets))
        {
            this.targets = Collections.unmodifiableSet(targets);

            if (targets.isEmpty())
            {
                bridgeOctoTransport.removeHandler(conference.getGid(), this);
            }
            else
            {
                bridgeOctoTransport.addHandler(conference.getGid(), this);
            }
        }
    }

    /**
     * Adds an RTP header extension.
     * @param rtpExtension the {@link RtpExtension} to add
     */
    public void addRtpExtension(RtpExtension rtpExtension)
    {
        octoEndpoints.addRtpExtension(rtpExtension);
    }

    /**
     * Expires the Octo-related parts of a conference.
     */
    public void expire()
    {
        logger.info("Expiring");
        setRelays(new LinkedList<>());
        octoEndpoints.setEndpoints(Collections.emptySet());
        outgoingPacketQueues.values().forEach(PacketInfoQueue::close);
        outgoingPacketQueues.clear();
    }

    /**
     * Sends a data message through the Octo relay.
     * @param message the message to send
     */
    public void sendMessage(String message)
    {
        bridgeOctoTransport.sendString(
            message,
            targets,
            conference.getGid()
        );
    }

    public void addHandler(String epId, IncomingOctoEpPacketHandler handler)
    {
        logger.info("Adding handler for ep ID " + epId);
        incomingPacketHandlers.put(epId, handler);
    }

    public void removeHandler(String epId, IncomingOctoEpPacketHandler handler)
    {
        if (incomingPacketHandlers.remove(epId, handler))
        {
            logger.info("Removing handler for ep ID " + epId);
        }
    }

    /**
     * Creates a PacketInfoQueue for an endpoint.
     */
    private PacketInfoQueue createQueue(String epId)
    {
        PacketInfoQueue q = new PacketInfoQueue(
            "octo-tentacle-outgoing-packet-queue",
            TaskPools.IO_POOL,
            this::doSend,
            OctoConfig.Config.sendQueueSize());
        q.setErrorHandler(queueErrorCounter);
        return q;
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("octoEndpoints", octoEndpoints.getDebugState());
        debugState.put("bridgeOctoTransport", bridgeOctoTransport.getStatsJson());
        debugState.put("targets", targets.toString());

        return debugState;
    }

    static class Stats
    {
        void packetReceived()
        {

        }

    }

    interface IncomingOctoEpPacketHandler {
        void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo);
    }
}
