/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.cc.allocation;

import edu.umd.cs.findbugs.annotations.*;
import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.config.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import static org.jitsi.videobridge.cc.allocation.PrioritizeKt.prioritize;

/**
 *
 * @author George Politis
 */
@SuppressWarnings("JavadocReference")
public class BitrateAllocator<T extends MediaSourceContainer>
{
    /**
     * Returns a boolean that indicates whether or not the current bandwidth estimation (in bps) has changed above the
     * configured threshold with respect to the previous bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     * @return true if the bandwidth has changed above the configured threshold, * false otherwise.
     */
    private static boolean bweChangeIsLargerThanThreshold(long previousBwe, long currentBwe)
    {
        if (previousBwe == -1 || currentBwe == -1)
        {
            return true;
        }

        long deltaBwe = currentBwe - previousBwe;

        // If the bwe has increased, we should act upon it, otherwise we may end up in this broken situation: Suppose
        // that the target bitrate is 2.5Mbps, and that the last bitrate allocation was performed with a 2.4Mbps
        // bandwidth estimate.  The bridge keeps probing and, suppose that, eventually the bandwidth estimate reaches
        // 2.6Mbps, which is plenty to accommodate the target bitrate; but the minimum bandwidth estimate that would
        // trigger a new bitrate allocation is 2.4Mbps + 2.4Mbps * 15% = 2.76Mbps.
        if (deltaBwe > 0)
        {
            return true;
        }

        // If, on the other hand, the bwe has decreased, we require at least a 15% drop in order to update the bitrate
        // allocation. This is an ugly hack to prevent too many resolution/UI changes in case the bridge produces too
        // low bandwidth estimate, at the risk of clogging the receiver's pipe.
        // TODO: do we still need this? Do we ever ever see BWE drop by <%15?
        return deltaBwe > 0 || deltaBwe < -1 * previousBwe * BitrateControllerConfig.bweChangeThreshold();
    }

    private final Logger logger;

    /**
     * The estimated available bandwidth in bits per second.
     */
    private long bweBps = -1;

    /**
     * The list of endpoints ids ordered by speech activity.
     */
    private List<String> sortedEndpointIds;

    /**
     * A modified copy of the original video constraints map, augmented with video constraints for the endpoints that
     * fall outside of the last-n set + endpoints not announced in the videoConstraintsMap.
     */
    private Map<String, VideoConstraints> effectiveConstraints = Collections.emptyMap();

    private final Clock clock;

    private final EventEmitter<EventHandler> eventEmitter = new EventEmitter<>();

    private final Supplier<List<T>> endpointsSupplier;
    private final Supplier<Boolean> trustBwe;

    private AllocationSettings allocationSettings = new AllocationSettings();

    /**
     * The last time {@link BitrateAllocator#update()} was called
     */
    private Instant lastUpdateTime = Instant.MIN;

    @NotNull
    private Allocation allocation = new Allocation(Collections.emptySet());

    /**
     * Initializes a new {@link BitrateAllocator} instance which is to
     * belong to a particular {@link Endpoint}.
     */
    BitrateAllocator(
            EventHandler eventHandler,
            Supplier<List<T>> endpointsSupplier,
            Supplier<Boolean> trustBwe,
            Logger parentLogger,
            Clock clock)
    {
        this.logger = parentLogger.createChildLogger(BitrateAllocator.class.getName());
        this.clock = clock;
        this.trustBwe = trustBwe;

        this.endpointsSupplier = endpointsSupplier;
        eventEmitter.addHandler(eventHandler);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("trustBwe", BitrateControllerConfig.trustBwe());
        debugState.put("bweBps", bweBps);
        debugState.put("allocationSettings", allocationSettings.toString());
        debugState.put("effectiveConstraints", effectiveConstraints);
        return debugState;
    }

    @NotNull
    Allocation getAllocation()
    {
        return allocation;
    }

    /**
     * We can't just use {@link #bweBps} to get the most recent bandwidth
     * estimate as we must take into account the initial join period where
     * we don't 'trust' the bwe anyway, as well as whether or not we'll
     * trust it at all (we ignore it always if the endpoint doesn't support
     * RTX, which is our way of filtering out endpoints that don't do
     * probing)
     *
     * @return the estimated bandwidth, in bps, based on the last update
     * we received and taking into account whether or not we 'trust' the
     * bandwidth estimate.
     */
    private long getAvailableBandwidth()
    {
        return trustBwe.get() ? bweBps : Long.MAX_VALUE;
    }

    /**
     * Notify the {@link BitrateAllocator} that the estimated available bandwidth has changed.
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    void bandwidthChanged(long newBandwidthBps)
    {
        if (!bweChangeIsLargerThanThreshold(bweBps, newBandwidthBps))
        {
            logger.debug(() -> "New bandwidth (" + newBandwidthBps
                    + ") is not significantly " +
                    "changed from previous estimate (" + bweBps + "), ignoring");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bitrate allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "new bandwidth is " + newBandwidthBps + ", updating");

            bweBps = newBandwidthBps;
            update();
        }
    }

    /**
     * Called when the ordering of endpoints has changed in some way. This could
     * be due to an endpoint joining or leaving, a new dominant speaker, or a
     * change in which endpoints are selected.
     *
     * @param conferenceEndpoints the endpoints of the conference sorted in
     * dominant speaker order (NOTE this does NOT take into account orderings
     * specific to this particular endpoint, e.g. selected, though
     * this method SHOULD be invoked when those things change; they will be
     * taken into account in this flow)
     */
    synchronized void endpointOrderingChanged(List<String> conferenceEndpoints)
    {
        logger.debug(() -> "Endpoint ordering has changed, updating.");

        // TODO: Maybe suppress calling update() unless the order actually changed?
        sortedEndpointIds = conferenceEndpoints;
        update();
    }

    void update(AllocationSettings allocationSettings)
    {
        this.allocationSettings = allocationSettings;
        update();
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     */
    private synchronized void update()
    {
        lastUpdateTime = clock.instant();

        if (sortedEndpointIds == null || sortedEndpointIds.isEmpty())
        {
            return;
        }

        // Order the endpoints by selection, followed by speech activity.
        List<T> sortedEndpoints
                = prioritize(sortedEndpointIds, allocationSettings.getSelectedEndpoints(), endpointsSupplier.get());

        Map<String, VideoConstraints> oldEffectiveConstraints = effectiveConstraints;
        effectiveConstraints = PrioritizeKt.getEffectiveConstraints(sortedEndpoints, allocationSettings);

        // Compute the bitrate allocation.
        Allocation newAllocation = allocate(getAvailableBandwidth(), sortedEndpoints);

        boolean allocationChanged = !allocation.isTheSameAs(newAllocation);
        if (allocationChanged)
        {
            eventEmitter.fireEvent(handler -> {
                handler.allocationChanged(newAllocation);
                return Unit.INSTANCE;
            });
        }
        allocation = newAllocation;

        if (!effectiveConstraints.equals(oldEffectiveConstraints))
        {
            eventEmitter.fireEvent(handler ->
            {
                handler.effectiveVideoConstraintsChanged(oldEffectiveConstraints, effectiveConstraints);
                return Unit.INSTANCE;
            });
        }
    }

    /**
     * Computes the ideal and the target bitrate, limiting the target to be
     * less than bandwidth estimation specified as an argument.
     *
     * @param maxBandwidth the max bandwidth estimation that the target bitrate
     * must not exceed.
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return an array of {@link SingleSourceAllocation}.
     */
    private synchronized @NotNull Allocation allocate(
            long maxBandwidth,
            List<T> conferenceEndpoints)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = createAllocations(conferenceEndpoints);

        if (sourceBitrateAllocations.isEmpty())
        {
            return new Allocation(Collections.emptySet());
        }

        long oldMaxBandwidth = -1;

        int oldStateLen = 0;
        int[] oldRatedTargetIndices = new int[sourceBitrateAllocations.size()];
        int[] newRatedTargetIndices = new int[sourceBitrateAllocations.size()];
        Arrays.fill(newRatedTargetIndices, -1);

        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newRatedTargetIndices, 0, oldRatedTargetIndices, 0, oldRatedTargetIndices.length);

            int newStateLen = 0;
            for (int i = 0; i < sourceBitrateAllocations.size(); i++)
            {
                SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);

                if (sourceBitrateAllocation.constraints.getMaxHeight() <= 0)
                {
                    continue;
                }

                maxBandwidth += sourceBitrateAllocation.getTargetBitrate();
                sourceBitrateAllocation.improve(maxBandwidth);
                // We will "force" forward the lowest layer of the highest priority (first in line)
                // participant if we weren't able to allocate any bandwidth for it and
                // enableOnstageVideoSuspend is false.
                if (i == 0 &&
                        sourceBitrateAllocation.ratedTargetIdx < 0 &&
                        !BitrateControllerConfig.enableOnstageVideoSuspend())
                {
                    sourceBitrateAllocation.ratedTargetIdx = 0;
                    sourceBitrateAllocation.oversending = true;
                }
                maxBandwidth -= sourceBitrateAllocation.getTargetBitrate();

                newRatedTargetIndices[i] = sourceBitrateAllocation.ratedTargetIdx;
                if (sourceBitrateAllocation.getTargetIndex() > -1)
                {
                    newStateLen++;
                }

                if (sourceBitrateAllocation.ratedTargetIdx < sourceBitrateAllocation.ratedPreferredIdx)
                {
                    break;
                }
            }

            if (oldStateLen > newStateLen)
            {
                // rollback state to prevent jumps in the number of forwarded
                // participants.
                for (int i = 0; i < sourceBitrateAllocations.size(); i++)
                {
                    sourceBitrateAllocations.get(i).ratedTargetIdx = oldRatedTargetIndices[i];
                }

                break;
            }

            oldStateLen = newStateLen;
        }

        // at this point, maxBandwidth is what we failed to allocate.

        return new Allocation(
                sourceBitrateAllocations.stream().map(SingleSourceAllocation::getResult).collect(Collectors.toSet()));
    }


    /**
     * Returns a prioritized {@link SingleSourceAllocation} array where
     * selected endpoint are at the top of the array followed by any other remaining endpoints. The
     * priority respects the order induced by the <tt>conferenceEndpoints</tt>
     * parameter.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return a prioritized {@link SingleSourceAllocation} array where
     * selected endpoint are at the top of the array,
     * followed by any other remaining endpoints.
     */
    private synchronized @NotNull List<SingleSourceAllocation> createAllocations(List<T> conferenceEndpoints)
    {
        // Init.
        List<SingleSourceAllocation> sourceBitrateAllocations = new ArrayList<>(conferenceEndpoints.size());

        for (MediaSourceContainer endpoint : conferenceEndpoints)
        {
            MediaSourceDesc[] sources = endpoint.getMediaSources();

            if (!ArrayUtils.isNullOrEmpty(sources))
            {
                for (MediaSourceDesc source : sources)
                {
                    sourceBitrateAllocations.add(
                            new SingleSourceAllocation(
                                    endpoint.getId(),
                                    source,
                                    effectiveConstraints.get(endpoint.getId()),
                                    allocationSettings.getStrategy(),
                                    clock));
                }
            }
        }

        return sourceBitrateAllocations;
    }

    void maybeUpdate()
    {
        if (Duration.between(lastUpdateTime, clock.instant())
                .compareTo(BitrateControllerConfig.maxTimeBetweenCalculations()) > 0)
        {
            logger.debug("Forcing an update");
            TaskPools.CPU_POOL.submit((Runnable) this::update);
        }
    }

    public interface EventHandler
    {
        default void allocationChanged(@NotNull Allocation allocation) {}
        default void effectiveVideoConstraintsChanged(
                @NotNull Map<String, VideoConstraints> oldEffectiveConstraints,
                @NotNull Map<String, VideoConstraints> newEffectiveConstraints) {}
    }
}
