/*
 * (C) Copyright 2019 Nuxeo SA (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     bdelbosc
 */
package org.nuxeo.lib.stream.computation;

import java.util.List;
import java.util.Set;

import org.nuxeo.lib.stream.log.LogOffset;

/**
 * Gives access to StreamProcessor and appender for source provider.
 *
 * @since 11.1
 */
public interface StreamManager extends AutoCloseable {
    /**
     * Registers a processor and initializes the underlying streams, this is needed before creating a processor or
     * appending record in source streams.
     */
    void register(String processorName, Topology topology, Settings settings);

    /**
     * Creates a registered processor without starting it.
     */
    StreamProcessor createStreamProcessor(String processorName);

    /**
     * Registers and creates a processor without starting it.
     */
    default StreamProcessor registerAndCreateProcessor(String processorName, Topology topology, Settings settings) {
        register(processorName, topology, settings);
        return createStreamProcessor(processorName);
    }

    /**
     * Registers some source Streams without any processors.
     *
     * @since 11.4
     */
    void register(List<String> streams, Settings settings);

    /**
     * Appends a record to a processor's source stream.
     */
    LogOffset append(String stream, Record record);

    /**
     * Gets a set of processor names.
     *
     * @since 2021.25
     */
    Set<String> getProcessorNames();

    /**
     * Gets a processor.
     *
     * @return null if the processor doesn't exist
     * @since 2021.25
     */
    StreamProcessor getProcessor(String processorName);

    @Override
    void close();
}
