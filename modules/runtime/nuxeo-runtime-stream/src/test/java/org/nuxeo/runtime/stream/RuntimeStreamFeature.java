/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
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
 *       Kevin Leturc <kleturc@nuxeo.com>
 */
package org.nuxeo.runtime.stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.runtime.RuntimeServiceException;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;
import org.nuxeo.runtime.test.runner.RuntimeFeature;
import org.nuxeo.runtime.test.runner.RuntimeHarness;

/**
 * The runtime stream feature provides an In-Memory or Kafka stream implementation depending on test configuration.
 * <p>
 * To run your unit tests on a Memory or Kafka you need to declare {@code nuxeo.test.stream} to either
 * {@link #STREAM_MEM mem} or {@link #STREAM_KAFKA kafka} in your system properties.
 * <p>
 *
 * @since 10.3
 */
@Deploy("org.nuxeo.runtime.stream")
@Deploy("org.nuxeo.runtime.stream.test")
@Features(RuntimeFeature.class)
public class RuntimeStreamFeature implements RunnerFeature {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(RuntimeStreamFeature.class);

    public static final String BUNDLE_TEST_NAME = "org.nuxeo.runtime.stream.test";

    public static final String STREAM_PROPERTY = "nuxeo.test.stream";

    public static final String STREAM_MEM = "mem";

    public static final String STREAM_KAFKA = "kafka";

    // kafka properties part

    public static final String KAFKA_SERVERS_PROPERTY = "nuxeo.test.kafka.servers";

    public static final String KAFKA_SERVERS_DEFAULT = "localhost:9092";

    protected String streamType;

    private boolean cleanupTopics;

    protected static String defaultProperty(String name, String def) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty() || value.equals("${" + name + "}")) {
            value = def;
        }
        Framework.getProperties().setProperty(name, value);
        return value;
    }

    @Override
    public void start(FeaturesRunner runner) {
        RuntimeHarness harness = runner.getFeature(RuntimeFeature.class).getHarness();
        streamType = defaultProperty(STREAM_PROPERTY, STREAM_MEM);
        try {
            String msg = "Deploying Nuxeo Stream using " + StringUtils.capitalize(streamType.toLowerCase());
            // System.out used on purpose, don't remove
            System.out.println(getClass().getSimpleName() + ": " + msg); // NOSONAR
            log.info(msg);
            switch (streamType) {
            case STREAM_MEM:
                initMem(harness);
                break;
            case STREAM_KAFKA:
                initKafka(harness);
                break;
            default:
                throw new UnsupportedOperationException(streamType + " stream type is not supported");
            }
        } catch (Exception e) {
            throw new RuntimeServiceException("Unable to configure the stream implementation", e);
        }
    }

    protected void initMem(RuntimeHarness harness) throws Exception {
        log.debug("Deploy Mem config");
        harness.deployContrib(BUNDLE_TEST_NAME, "OSGI-INF/test-stream-mem-contrib.xml");
        cleanupTopics = true;
    }

    protected void initKafka(RuntimeHarness harness) throws Exception {
        // no need to re-init kafka as we use a random prefix
        log.debug("Deploy Kafka config");
        defaultProperty(KAFKA_SERVERS_PROPERTY, KAFKA_SERVERS_DEFAULT);
        // deploy component
        harness.deployContrib(BUNDLE_TEST_NAME, "OSGI-INF/test-stream-kafka-contrib.xml");
        cleanupTopics = true;
    }

    @Override
    public void stop(FeaturesRunner runner) throws Exception {
        if (!cleanupTopics) {
            return;
        }
        log.debug("Cleaning Streams");
        StreamService service = Framework.getService(StreamService.class);
        service.stopProcessors();
        LogManager manager = service.getLogManager();
        if (STREAM_KAFKA.equals(streamType)) {
            // deleting records is much lighter for Kafka
            manager.listAllNames().forEach(manager::deleteRecords);
            try {
                manager.deleteConsumers();
            } catch (RuntimeException e) {
                // ignore failure if group is seen as not empty
                log.warn("Fail to delete consumers: {}", e::getMessage);
            }
        } else {
            manager.listAllNames().forEach(manager::delete);
        }
        cleanupTopics = false;
    }

}
