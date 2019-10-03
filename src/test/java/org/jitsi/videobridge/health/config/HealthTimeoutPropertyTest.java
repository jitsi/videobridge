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

package org.jitsi.videobridge.health.config;

import com.typesafe.config.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.util.config.*;
import org.junit.*;

import static org.junit.Assert.*;

public class HealthTimeoutPropertyTest
{
    private static Config EMPTY_CONFIG = ConfigFactory.parseString("");
    @Test
    public void whenOnlyOldConfigIsPresent()
    {
        JvbConfig.configSupplier = () -> EMPTY_CONFIG;
        Config legacyConfig = ConfigFactory.parseString(HealthTimeoutProperty.legacyPropKey + "=60000");
        JvbConfig.legacyConfigSupplier = () -> legacyConfig;
        JvbConfig.reloadConfig();

        ConfigProperty<Integer> healthTimeoutProp = HealthTimeoutProperty.createInstance();
        assertEquals("The value from the old config should be read correctly", 60000, (int)healthTimeoutProp.get());
    }

    @Test
    public void whenOnlyNewConfigIsPresent()
    {
        Config newConfig = ConfigFactory.parseString(HealthTimeoutProperty.propKey + "=10 seconds");
        JvbConfig.configSupplier = () -> newConfig;
        JvbConfig.legacyConfigSupplier = () -> EMPTY_CONFIG;
        JvbConfig.reloadConfig();

        ConfigProperty<Integer> healthTimeoutProp = HealthTimeoutProperty.createInstance();
        assertEquals("The value from the new config should be read correctly", 10000, (int)healthTimeoutProp.get());
    }

    @Test
    public void whenOldAndNewConfigsArePresent()
    {
        Config legacyConfig = ConfigFactory.parseString(HealthTimeoutProperty.legacyPropKey + "=60000");
        JvbConfig.legacyConfigSupplier = () -> legacyConfig;
        Config newConfig = ConfigFactory.parseString(HealthTimeoutProperty.propKey + "=10 seconds");
        JvbConfig.configSupplier = () -> newConfig;
        JvbConfig.reloadConfig();

        ConfigProperty<Integer> healthTimeoutProp = HealthTimeoutProperty.createInstance();
        assertEquals("The new config value should be used", 10000, (int)healthTimeoutProp.get());
    }

    @Test
    public void doesNotChangeAfterConfigReload()
    {
        Config newConfig = ConfigFactory.parseString(HealthTimeoutProperty.propKey + "=10 seconds");
        JvbConfig.configSupplier = () -> newConfig;
        JvbConfig.legacyConfigSupplier = () -> EMPTY_CONFIG;
        JvbConfig.reloadConfig();

        ConfigProperty<Integer> healthTimeoutProp = HealthTimeoutProperty.createInstance();

        Config changedConfig = ConfigFactory.parseString(HealthTimeoutProperty.propKey + "=90 seconds");
        JvbConfig.configSupplier = () -> changedConfig;
        JvbConfig.reloadConfig();

        assertEquals(10000, (int)healthTimeoutProp.get());
    }
}