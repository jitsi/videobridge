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

package org.jitsi.testutils;

import com.typesafe.config.*;
import org.jitsi.videobridge.util.*;

import java.util.function.*;

import static org.jitsi.testutils.ConfigUtils.EMPTY_CONFIG;

/**
 * Helper class to make installing new and legacy configurations
 * easier.
 */
public class ConfigSetup
{
    protected String commandLineArgs = "";
    protected Supplier<Config> legacyConfigSupplier = () -> EMPTY_CONFIG;
    protected Supplier<Config> newConfigSupplier = () -> EMPTY_CONFIG;

    // This no longer needs to be explicitly called
    @Deprecated
    public ConfigSetup withNoLegacyConfig()
    {
        JvbConfig.legacyConfigSupplier = () -> EMPTY_CONFIG;

        return this;
    }

    public ConfigSetup withLegacyConfig(Config legacyConfig)
    {
        legacyConfigSupplier = () -> legacyConfig;

        return this;
    }

    // This no longer needs to be explicitly called
    @Deprecated
    public ConfigSetup withNoNewConfig()
    {
        JvbConfig.configSupplier = () -> EMPTY_CONFIG;

        return this;
    }

    public ConfigSetup withNewConfig(Config newConfig)
    {
        newConfigSupplier = () -> newConfig;

        return this;
    }

    public ConfigSetup withCommandLineArg(String argName, String value)
    {
        commandLineArgs += " " + argName + "=" + value;

        return this;
    }

    public void finishSetup()
    {
        // Make sure config doesn't see any command line args from a previous setup
        if (commandLineArgs.isEmpty())
        {
            JvbConfig.commandLineArgsSupplier =() -> "";
        }
        else
        {
            JvbConfig.commandLineArgsSupplier = () -> commandLineArgs;
        }
        JvbConfig.legacyConfigSupplier = legacyConfigSupplier;
        JvbConfig.configSupplier = newConfigSupplier;

        JvbConfig.reloadConfig();
    }
}
