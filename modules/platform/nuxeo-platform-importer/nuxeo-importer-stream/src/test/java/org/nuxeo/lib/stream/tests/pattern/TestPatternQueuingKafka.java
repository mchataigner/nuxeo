/*
 * (C) Copyright 2017 Nuxeo SA (http://nuxeo.com/) and others.
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
package org.nuxeo.lib.stream.tests.pattern;

import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.BeforeClass;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.lib.stream.log.kafka.KafkaLogManager;
import org.nuxeo.runtime.stream.IgnoreKafka;

/**
 * @since 9.2
 */
public class TestPatternQueuingKafka extends TestPatternQueuing {
    protected String prefix;

    @BeforeClass
    public static void assumeKafkaEnabled() {
        assumeTrue("Kafka not enabled", new IgnoreKafka().shouldIgnore());
    }

    @Override
    public LogManager createManager() {
        if (prefix == null) {
            prefix = KafkaHelper.getPrefix();
        }
        return new KafkaLogManager(prefix, KafkaHelper.getProducerProps(), KafkaHelper.getConsumerProps());
    }

    @After
    public void resetPrefix() {
        if (manager != null) {
            manager.listAllNames().forEach(manager::delete);
        }
        prefix = null;
    }

}
