/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

import java.beans.*;
import java.io.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.CandidateType;
import net.java.sip.communicator.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.eventadmin.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.transport.*;
import org.osgi.framework.*;

/**
 * Implements the Jingle ICE-UDP transport.
 *
 * @author Lyubomir Marinov
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class IceUdpTransportManager
    extends TransportManager
{
    /**
     * The name default of the single <tt>IceStream</tt> that this
     * <tt>TransportManager</tt> will create/use.
     */
    private static final String DEFAULT_ICE_STREAM_NAME = "stream";

    /**
     * The {@link Logger} used by the {@link IceUdpTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(IceUdpTransportManager.class);

    /**
     * The name of the property that can be used to control the value of
     * {@link #iceUfragPrefix}.
     */
    public static final String ICE_UFRAG_PREFIX_PNAME
            = "org.jitsi.videobridge.ICE_UFRAG_PREFIX";
    /**
     * The optional prefix to use for generated ICE local username fragments.
     */
    private static String iceUfragPrefix;

    /**
     * Whether the "component socket" feature of ice4j should be used. If this
     * feature is used, ice4j will create a separate merging socket instance
     * for each component, which reads from the sockets of all successful
     * candidate pairs. Otherwise, this merging socket instance is not created,
     * and the sockets from the individual candidate pairs should be used
     * directly.
     */
    private static boolean useComponentSocket = true;

    /**
     * The name of the property which configures {@link #useComponentSocket}.
     */
    public static final String USE_COMPONENT_SOCKET_PNAME
        = "org.jitsi.videobridge.USE_COMPONENT_SOCKET";

    /**
     * The name of the property used to control {@link #keepAliveStrategy}.
     */
    public static final String KEEP_ALIVE_STRATEGY_PNAME
            = "org.jitsi.videobridge.KEEP_ALIVE_STRATEGY";

    /**
     * The {@link KeepAliveStrategy} to configure for ice4j {@link Component}s,
     * which will dictate which candidate pairs to keep alive.
     * Default to keeping alive the selected pair and any TCP pairs.
     */
    private static KeepAliveStrategy keepAliveStrategy
            = KeepAliveStrategy.SELECTED_AND_TCP;

    /**
     * Whether this <tt>TransportManager</tt> has been closed.
     */
    protected boolean closed = false;

    /**
     * The <tt>Conference</tt> object that this <tt>TransportManager</tt> is
     * associated with.
     */
    protected final Conference conference;

    /**
     * The {@link DiagnosticContext} of this diagnostic instance provider.
     */
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();

    /**
     * An identifier of this {@link IceUdpTransportManager}.
     */
    protected final String id;

    /**
     * The ICE {@link Agent}.
     */
    protected Agent iceAgent;

    /**
     * Whether ICE connectivity has been established.
     */
    protected boolean iceConnected = false;

    /**
     * The <tt>IceMediaStream</tt> of {@link #iceAgent} associated with the
     * <tt>Channel</tt> of this instance.
     */
    protected final IceMediaStream iceStream;

    /**
     * The single {@link Component} that we have
     * (since we use bundle and rtcp-mux).
     */
    private final Component iceComponent;

    /**
     * The <tt>PropertyChangeListener</tt> which is (to be) notified about
     * changes in the properties of the <tt>CandidatePair</tt>s of
     * {@link #iceStream}.
     */
    private final PropertyChangeListener iceStreamPairChangeListener
        = this::iceStreamPairChange;

    /**
     * The listener we register with the {@link Agent} to listen to ICE state
     * change events.
     */
    private final PropertyChangeListener iceStateChangeListener
            = this::iceStateChange;

    /**
     * Whether this <tt>IceUdpTransportManager</tt> will serve as the the
     * controlling or controlled ICE agent.
     */
    private final boolean controlling;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>IceUdpTransportManager</tt> instance.
     *
     * @param conference the <tt>Conference</tt> which created this
     * <tt>TransportManager</tt>.
     * @param controlling {@code true} if the new instance is to serve as a
     * controlling ICE agent and passive DTLS endpoint; otherwise, {@code false}
     * @param id an identifier of the {@link IceUdpTransportManager}.
     * @throws IOException
     */
    IceUdpTransportManager(
            Conference conference,
            boolean controlling,
            String id)
        throws IOException
    {
        this.conference = conference;
        this.id = id;
        this.controlling = controlling;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());

        // Setup the diagnostic context.
        conference.appendDiagnosticInformation(diagnosticContext);
        diagnosticContext.put("transport", hashCode());

        iceAgent = createIceAgent(controlling);
        iceStream = iceAgent.getStream(DEFAULT_ICE_STREAM_NAME);
        iceComponent = iceStream.getComponent(Component.RTP);
        iceStream.addPairChangeListener(iceStreamPairChangeListener);

        EventAdmin eventAdmin = conference.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.sendEvent(EventFactory.transportCreated(this));
        }

        iceAgent.addStateChangeListener(iceStateChangeListener);
    }

    /**
     * Adds to {@link #iceAgent} the
     * {@link org.ice4j.ice.harvest.CandidateHarvester} instances managed by
     * jitsi-videobridge (the TCP and SinglePort harvesters), and configures the
     * use of the dynamic host harvester.
     *
     * @param iceAgent the {@link Agent} that we'd like to append new harvesters
     * to.
     * <tt>IceUdpTransportManager</tt>.
     */
    private void configureHarvesters(Agent iceAgent)
    {
        ConfigurationService cfg
            = ServiceUtils.getService(
                    getBundleContext(),
                    ConfigurationService.class);
        boolean disableDynamicHostHarvester = false;

        useComponentSocket
                = cfg.getBoolean(USE_COMPONENT_SOCKET_PNAME, useComponentSocket);
        logger.info("Using component socket: " + useComponentSocket);

        iceUfragPrefix = cfg.getString(ICE_UFRAG_PREFIX_PNAME, null);
        String strategyName = cfg.getString(KEEP_ALIVE_STRATEGY_PNAME);
        KeepAliveStrategy strategy
                = KeepAliveStrategy.fromString(strategyName);
        if (strategyName != null && strategy == null)
        {
            logger.warn("Invalid keep alive strategy name: "
                    + strategyName);
        }
        else if (strategy != null)
        {
            keepAliveStrategy = strategy;
        }

        // TODO CandidateHarvesters may take (non-trivial) time to initialize so
        // initialize them as soon as possible, don't wa it to initialize them
        // after a Channel is requested.
        // XXX Unfortunately, TcpHarvester binds to specific local addresses
        // while Jetty binds to all/any local addresses and, consequently, the
        // order of the binding is important at the time of this writing. That's
        // why TcpHarvester is left to initialize as late as possible right now.
        Harvesters.initializeStaticConfiguration(cfg);

        if (Harvesters.tcpHarvester != null)
        {
            iceAgent.addCandidateHarvester(Harvesters.tcpHarvester);
        }
        if (Harvesters.singlePortHarvesters != null)
        {
            for (CandidateHarvester harvester : Harvesters.singlePortHarvesters)
            {
                iceAgent.addCandidateHarvester(harvester);
                disableDynamicHostHarvester = true;
            }
        }

        // Disable dynamic ports (UDP) if we're using "single port" (UPD), as
        // there's no need for a client to try a similar UDP candidate twice.
        if (disableDynamicHostHarvester)
        {
            iceAgent.setUseHostHarvester(false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close()
    {
        if (!closed)
        {
            // Set this early to prevent double closing when the last channel
            // is removed.
            closed = true;

            if (iceStream != null)
            {
                iceStream.removePairStateChangeListener(
                        iceStreamPairChangeListener);
            }
            if (iceAgent != null)
            {
                iceAgent.removeStateChangeListener(iceStateChangeListener);
                iceAgent.free();
                iceAgent = null;
            }

            super.close();
        }
    }

    /**
     * Initializes a new <tt>Agent</tt> instance which implements the ICE
     * protocol and which is to be used by this instance to implement the Jingle
     * ICE-UDP transport.
     *
     * @param controlling
     * @param iceStreamName
     * @return a new <tt>Agent</tt> instance which implements the ICE protocol
     * and which is to be used by this instance to implement the Jingle ICE-UDP
     * transport
     * @throws IOException if initializing a new <tt>Agent</tt> instance for the
     * purposes of this <tt>TransportManager</tt> fails
     */
    private Agent createIceAgent(boolean controlling)
            throws IOException
    {
        Agent iceAgent = new Agent(logger.getLevel(), iceUfragPrefix);

        //add videobridge specific harvesters such as a mapping and an Amazon
        //AWS EC2 harvester
        configureHarvesters(iceAgent);
        iceAgent.setControlling(controlling);
        iceAgent.setPerformConsentFreshness(true);

        int portBase = portTracker.getPort();

        IceMediaStream iceStream
                = iceAgent.createMediaStream(DEFAULT_ICE_STREAM_NAME);

        iceAgent.createComponent(
                iceStream, Transport.UDP,
                portBase, portBase, portBase + 100,
                keepAliveStrategy,
                useComponentSocket);

        // Attempt to minimize subsequent bind retries: see if we have allocated
        // any ports from the dynamic range, and if so update the port tracker.
        // Do NOT update the port tracker with non-dynamic ports (e.g. 4443
        // coming from TCP) because this will force it to revert back it its
        // configured min port. When maxPort is reached, allocation will begin
        // from minPort again, so we don't have to worry about wraps.
        int maxAllocatedPort
            = TransportUtils.getMaxAllocatedPort(
                    iceStream,
                    portTracker.getMinPort(),
                    portTracker.getMaxPort());
        if (maxAllocatedPort > 0)
        {
            int nextPort = 1 + maxAllocatedPort;
            portTracker.setNextPort(nextPort);
            if (logger.isDebugEnabled())
            {
                logger.debug("Updating the port tracker min port: " + nextPort);
            }
        }

        return iceAgent;
    }

    /**
     * @return the URL to advertise for COLIBRI WebSocket connections for this
     * transport manager.
     */
    private String getColibriWsUrl()
    {
        BundleContext bundleContext
            = getConference().getVideobridge().getBundleContext();
        ColibriWebSocketService colibriWebSocketService
            = ServiceUtils.getService(
                    bundleContext, ColibriWebSocketService.class);
        if (colibriWebSocketService != null)
        {
            return colibriWebSocketService.getColibriWebSocketUrl(
                getConference().getID(),
                id,
                iceAgent.getLocalPassword());
        }

        return null;
    }

    /**
     * Gets the <tt>Conference</tt> object that this <tt>TransportManager</tt>
     * is associated with.
     */
    protected Conference getConference()
    {
        return conference;
    }

    /**
     * Gets the ICE local username fragment.
     */
    String getLocalUfrag()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalUfrag();
    }

    /**
     * Gets the ICE password.
     */
    String getIcePassword()
    {
        Agent iceAgent = this.iceAgent;
        return iceAgent == null ? null : iceAgent.getLocalPassword();
    }

    /**
     * Gets the <tt>BundleContext</tt> associated with the <tt>Channel</tt>
     * that this {@link net.java.sip.communicator.service.protocol.media
     * .TransportManager} is servicing. The method is a
     * convenience which gets the <tt>BundleContext</tt> associated with the
     * XMPP component implementation in which the <tt>Videobridge</tt>
     * associated with this instance is executing.
     *
     * @return the <tt>BundleContext</tt> associated with this
     * <tt>IceUdpTransportManager</tt>
     */
    public BundleContext getBundleContext()
    {
        return conference != null ? conference.getBundleContext() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getXmlNamespace()
    {
        return IceUdpTransportPacketExtension.NAMESPACE;
    }

    /**
     * Notifies this instance about a change of the value of a property of a
     * <tt>CandidatePair</tt> of {@link #iceStream}.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies the
     * <tt>CandidatePair</tt>, the name of the <tt>CandidatePair</tt> property,
     * and its old and new values
     */
    private void iceStreamPairChange(PropertyChangeEvent ev)
    {
        if (IceMediaStream.PROPERTY_PAIR_CONSENT_FRESHNESS_CHANGED.equals(
                ev.getPropertyName()))
        {
            //TODO(brian): touch activity in new scheme here
            // TODO we might not necessarily want to keep all channels alive by
            // the ICE connection.
//            getChannels().forEach(
//                channel -> channel.touch(Channel.ActivityType.TRANSPORT));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected()
    {
        return iceConnected;
    }

    /**
     * Gets a string with identifying information for this instance to be used
     * when logging.
     * @return
     */
    public String getLoggingId()
    {
        return "[endpointId=" + id + " local_ufrag=" + getLocalUfrag() + "] ";
    }

    /**
     * {@inheritDoc}
     * @param transportPacketExtension
     */
    @Override
    public void startConnectivityEstablishment(
            IceUdpTransportPacketExtension transportPacketExtension)
    {
        if (iceAgent.getState().isEstablished())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Connection already established for " +
                        getLoggingId());
            }
            return;
        }

        // Set the remote ufrag/password
        if (transportPacketExtension.getUfrag() != null)
        {
            iceStream.setRemoteUfrag(transportPacketExtension.getUfrag());
        }
        if (transportPacketExtension.getPassword() != null)
        {
            iceStream.setRemotePassword(transportPacketExtension.getPassword());
        }

        // If ICE is running already, we try to update the checklists with the
        // candidates. Note that this is a best effort.
        boolean iceAgentStateIsRunning
                = IceProcessingState.RUNNING.equals(iceAgent.getState());

        List<CandidatePacketExtension> remoteCandidates
                = transportPacketExtension.getChildExtensionsOfType(
                        CandidatePacketExtension.class);
        if (iceAgentStateIsRunning && remoteCandidates.isEmpty()) {
            if (logger.isDebugEnabled())
            {
                logger.debug(getLoggingId() +
                        "Ignoring transport extension with no candidates, "
                        + "the Agent is already running.");
            }
            return;
        }

        int remoteCandidateCount
                = addRemoteCandidates(remoteCandidates, iceAgentStateIsRunning);

        if (iceAgentStateIsRunning)
        {
            if (remoteCandidateCount == 0)
            {
                // XXX Effectively, the check above but realizing that all
                // candidates were ignored:
                // iceAgentStateIsRunning && candidates.isEmpty().
            }
            else
            {
                iceComponent.updateRemoteCandidates();
            }
        }
        else if (remoteCandidateCount != 0)
        {
            // Once again, because the ICE Agent does not support adding
            // candidates after the connectivity establishment has been started
            // and because multiple transport-info JingleIQs may be used to send
            // the whole set of transport candidates from the remote peer to the
            // local peer, do not really start the connectivity establishment
            // until we have at least one remote candidate per ICE Component.
            if (iceComponent.getRemoteCandidateCount() >= 1)
            {
                logger.info(getLoggingId() +
                        "Starting the agent with remote candidates.");
                iceAgent.startConnectivityEstablishment();
            }
        }
        else if (iceStream.getRemoteUfrag() != null
                && iceStream.getRemotePassword() != null)
        {
            // We don't have any remote candidates, but we already know the
            // remote ufrag and password, so we can start ICE.
            logger.info(getLoggingId() +
                    "Starting the Agent without remote candidates. ");
            iceAgent.startConnectivityEstablishment();
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(getLoggingId() +
                        " Not starting ICE, no ufrag and pwd yet. " +
                        transportPacketExtension.toXML());
            }
        }

    }

    /**
     * @return the number of network reachable remote candidates contained in
     * the given list of candidates.
     */
    private int addRemoteCandidates(
            List<CandidatePacketExtension> candidates,
            boolean iceAgentStateIsRunning)
    {
        // Sort the remote candidates (host < reflexive < relayed) in order to
        // create first the host, then the reflexive, the relayed candidates and
        // thus be able to set the relative-candidate matching the
        // rel-addr/rel-port attribute.
        Collections.sort(candidates);

        int generation = iceAgent.getGeneration();
        int remoteCandidateCount = 0;

        for (CandidatePacketExtension candidate : candidates)
        {
            // Is the remote candidate from the current generation of the
            // iceAgent?
            if (candidate.getGeneration() != generation)
                continue;

            Component component
                    = iceStream.getComponent(candidate.getComponent());
            String relAddr;
            int relPort;
            TransportAddress relatedAddress = null;

            if ((relAddr = candidate.getRelAddr()) != null
                    && (relPort = candidate.getRelPort()) != -1)
            {
                relatedAddress
                        = new TransportAddress(
                        relAddr,
                        relPort,
                        Transport.parse(candidate.getProtocol()));
            }

            RemoteCandidate relatedCandidate
                    = component.findRemoteCandidate(relatedAddress);
            RemoteCandidate remoteCandidate
                    = new RemoteCandidate(
                    new TransportAddress(
                            candidate.getIP(),
                            candidate.getPort(),
                            Transport.parse(candidate.getProtocol())),
                    component,
                    org.ice4j.ice.CandidateType.parse(
                            candidate.getType().toString()),
                    candidate.getFoundation(),
                    candidate.getPriority(),
                    relatedCandidate);

            // XXX IceUdpTransportManager harvests host candidates only and the
            // ICE Components utilize the UDP protocol/transport only at the
            // time of this writing. The ice4j library will, of course, check
            // the theoretical reachability between the local and the remote
            // candidates. However, we would like (1) to not mess with a
            // possibly running iceAgent and (2) to return a consistent return
            // value.
            if (!TransportUtils.canReach(component, remoteCandidate))
            {
                continue;
            }

            if (iceAgentStateIsRunning)
            {
                component.addUpdateRemoteCandidates(remoteCandidate);
            }
            else
            {
                component.addRemoteCandidate(remoteCandidate);
            }
            remoteCandidateCount++;
        }

        return remoteCandidateCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        pe.setPassword(iceAgent.getLocalPassword());
        pe.setUfrag(iceAgent.getLocalUfrag());
        List<LocalCandidate> localCandidates = iceComponent.getLocalCandidates();
        if (localCandidates != null)
        {
            localCandidates.forEach(
                    localCandidate -> describe(localCandidate, pe));
        }
        pe.addChildExtension(new RtcpmuxPacketExtension());

        //TODO(brian): need to include the Colibiri websocket url when describing
        // (see IceUdpTransportManager#describe and #getColibriWsUrl)
    }

    private void describe(
            LocalCandidate candidate,
            IceUdpTransportPacketExtension pe)
    {
        CandidatePacketExtension candidatePE = new CandidatePacketExtension();
        org.ice4j.ice.Component component = candidate.getParentComponent();

        candidatePE.setComponent(component.getComponentID());
        candidatePE.setFoundation(candidate.getFoundation());
        candidatePE.setGeneration(
                component.getParentStream().getParentAgent().getGeneration());
        candidatePE.setID(generateCandidateID(candidate));
        candidatePE.setNetwork(0);
        candidatePE.setPriority(candidate.getPriority());

        // Advertise 'tcp' candidates for which SSL is enabled as 'ssltcp'
        // (although internally their transport protocol remains "tcp")
        Transport transport = candidate.getTransport();
        if (transport == Transport.TCP && candidate.isSSL())
        {
            transport = Transport.SSLTCP;
        }
        candidatePE.setProtocol(transport.toString());

        if (transport == Transport.TCP || transport == Transport.SSLTCP)
        {
            candidatePE.setTcpType(candidate.getTcpType());
        }

        candidatePE.setType(
                CandidateType.valueOf(candidate.getType().toString()));

        TransportAddress transportAddress = candidate.getTransportAddress();

        candidatePE.setIP(transportAddress.getHostAddress());
        candidatePE.setPort(transportAddress.getPort());

        TransportAddress relatedAddress = candidate.getRelatedAddress();

        if (relatedAddress != null)
        {
            candidatePE.setRelAddr(relatedAddress.getHostAddress());
            candidatePE.setRelPort(relatedAddress.getPort());
        }

        pe.addChildExtension(candidatePE);
    }

    private String generateCandidateID(LocalCandidate candidate)
    {
        StringBuilder candidateID = new StringBuilder();

        candidateID.append(conference.getID());
        candidateID.append(Long.toHexString(hashCode()));

        Agent iceAgent
                = candidate.getParentComponent().getParentStream().getParentAgent();

        candidateID.append(Long.toHexString(iceAgent.hashCode()));
        candidateID.append(Long.toHexString(iceAgent.getGeneration()));
        candidateID.append(Long.toHexString(candidate.hashCode()));

        return candidateID.toString();
    }

    private void iceStateChange(PropertyChangeEvent ev)
    {
        IceProcessingState oldState = (IceProcessingState) ev.getOldValue();
        IceProcessingState newState = (IceProcessingState) ev.getNewValue();

        logger.info(getLoggingId() + "ICE state changed old_state=" +
                oldState + " new_state=" + newState);

        // We should be using newState.isEstablished() here, but we see
        // transitions from RUNNING to COMPLETED, which should not happen and
        // when they happen the connection is not successful. So we handle that
        // case separately below.
        if (IceProcessingState.COMPLETED.equals(newState))
        {
            iceConnected = true;
            onIceConnected();
        }
        else if (IceProcessingState.FAILED.equals(newState)
            || (IceProcessingState.RUNNING.equals(oldState)
                    && IceProcessingState.TERMINATED.equals(newState)))
        {
            onIceFailed();
        }
    }

    protected void onIceConnected()
    {
    }

    protected void onIceFailed()
    {
    }


}
