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

package org.jitsi.videobridge.util.config;

import com.typesafe.config.*;

import java.util.function.*;

/**
 * A helper class which contains specifically where to pull 'legacy' config
 * values from.
 *
 * @param <T> the type of the configuration property's value
 */
public class LegacyConfigValueSupplier<T> implements Supplier<T>
{
    protected final Supplier<T> typesafeConfigSupplier;

    public LegacyConfigValueSupplier(Function<Config, T> getter)
    {
        Config config = JvbConfig.getLegacyConfig();
        this.typesafeConfigSupplier =
            new TypesafeConfigValueSupplier<>(() -> getter.apply(config));
    }

    @Override
    public T get()
    {
        return typesafeConfigSupplier.get();
    }
}
