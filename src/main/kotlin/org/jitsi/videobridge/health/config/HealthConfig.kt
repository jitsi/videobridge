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

package org.jitsi.videobridge.health.config

import org.jitsi.config.JitsiConfig
import org.jitsi.config.LegacyFallbackConfigProperty
import org.jitsi.utils.config.FallbackProperty
import org.jitsi.utils.config.helpers.attributes
import java.time.Duration

class HealthConfig {
    class Config {
        companion object {
            class HealthIntervalProperty : FallbackProperty<Long>(
                attributes {
                    name("org.jitsi.videobridge.health.INTERVAL")
                    readOnce()
                    fromConfig(JitsiConfig.legacyConfig)
                },
                attributes {
                    name("videobridge.health.interval")
                    readOnce()
                    fromConfig(JitsiConfig.newConfig)
                    retrievedAs<Duration>() convertedBy { it.toMillis() }
                }
            )

            private val interval = HealthIntervalProperty()

            @JvmStatic
            fun getInterval(): Long = interval.value

            class TimeoutProperty : FallbackProperty<Long>(
                attributes {
                    name("org.jitsi.videobridge.health.TIMEOUT")
                    readOnce()
                    fromConfig(JitsiConfig.legacyConfig)
                },
                attributes {
                    name("videobridge.health.timeout")
                    readOnce()
                    fromConfig(JitsiConfig.newConfig)
                    retrievedAs<Duration>() convertedBy { it.toMillis() }
                }
            )

            private val timeout = TimeoutProperty()

            @JvmStatic
            fun getTimeout(): Long = timeout.value

            class StickyFailuresProperty : LegacyFallbackConfigProperty<Boolean>(
                Boolean::class,
                readOnce = true,
                legacyName = "org.jitsi.videobridge.health.STICKY_FAILURES",
                newName = "videobridge.health.sticky-failures"
            )

            private val stickyFailures = StickyFailuresProperty()

            @JvmStatic
            fun stickyFailures() = stickyFailures.value
        }
    }
}