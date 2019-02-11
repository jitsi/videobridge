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
package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.service.neomedia.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Utilities to deserialize {@link PayloadTypePacketExtension} into a
 * {PayloadType}. This is currently in {@code jitsi-videbridge} in order to
 * avoid adding the XML extensions as a dependency to
 * {@code jitsi-media-transform}.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
public class PayloadTypeUtil
{
    /**
     * Creates a {@link PayloadType} for the payload type described in the
     * given {@link PayloadTypePacketExtension}.
     * @param ext the XML extension which describes the payload type.
     */
    public static PayloadType create(
            @NotNull PayloadTypePacketExtension ext,
            MediaType mediaType)
    {
        Map<String, String> parameters = new ConcurrentHashMap<>();
        for (ParameterPacketExtension parameter : ext.getParameters())
        {
            parameters.put(parameter.getName(), parameter.getValue());
        }

        byte id = (byte)ext.getID();
        String encoding = ext.getName();

        if (PayloadType.VP8.equalsIgnoreCase(encoding))
        {
            return new Vp8PayloadType(id, parameters);
        }
        else if (PayloadType.H264.equalsIgnoreCase(encoding))
        {
            return new H264PayloadType(id, parameters);
        }
        else if (PayloadType.VP9.equalsIgnoreCase(encoding))
        {
            return new Vp9PayloadType(id, parameters);
        }
        else if (PayloadType.RTX.equalsIgnoreCase(encoding))
        {
            return new RtxPayloadType(id, parameters);
        }
        else if (PayloadType.OPUS.equalsIgnoreCase(encoding))
        {
            return new OpusPayloadType(id, parameters);
        }
        else if (MediaType.VIDEO.equals(mediaType))
        {
            return new VideoPayloadType(
                    id, encoding, ext.getClockrate(), parameters);
        }
        else if (MediaType.AUDIO.equals(mediaType))
        {
            return new AudioPayloadType(
                    id, encoding, ext.getClockrate(), parameters);
        }

        return null;
    }
}
