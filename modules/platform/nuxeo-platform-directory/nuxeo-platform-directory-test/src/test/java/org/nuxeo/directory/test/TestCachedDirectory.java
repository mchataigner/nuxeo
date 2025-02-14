/*
 * (C) Copyright 2017-2023 Nuxeo (http://nuxeo.com/) and others.
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
 *     Funsho David
 *
 */
package org.nuxeo.directory.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.directory.Directory;
import org.nuxeo.ecm.directory.DirectoryCache;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.runtime.metrics.MetricsService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import io.dropwizard.metrics5.Counter;
import io.dropwizard.metrics5.MetricRegistry;
import io.dropwizard.metrics5.SharedMetricRegistries;

/**
 * @since 9.2
 */
@RunWith(FeaturesRunner.class)
@Features(DirectoryFeature.class)
@Deploy("org.nuxeo.ecm.core.cache")
@Deploy("org.nuxeo.ecm.directory.tests:directory-cache-config.xml")
public class TestCachedDirectory extends AbstractDirectoryTest {

    protected final static String ENTRY_CACHE_NAME = "entry-cache";

    protected final static String ENTRY_CACHE_WITHOUT_REFERENCES_NAME = "entry-cache-without-references";

    @Before
    public void setUp() throws Exception {

        Directory dir = getDirectory();
        DirectoryCache cache = dir.getCache();
        cache.setEntryCacheName(ENTRY_CACHE_NAME);
        cache.setEntryCacheWithoutReferencesName(ENTRY_CACHE_WITHOUT_REFERENCES_NAME);

    }

    @Test
    public void testGetFromCache() throws Exception {
        try (Session session = getDirectory().getSession()) {
            MetricRegistry registry = SharedMetricRegistries.getOrCreate(MetricsService.class.getName());
            Counter hitsCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "hit")
                                  .tagged("directory", "userDirectory"));
            Counter negativeHitsCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "hit", "null")
                                  .tagged("directory", "userDirectory"));
            Counter missesCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "miss")
                                  .tagged("directory", "userDirectory"));
            long baseHitsCount = hitsCounter.getCount();
            long baseNegativeHitsCount = negativeHitsCounter.getCount();
            long baseMissesCount = missesCounter.getCount();

            // First call will update cache
            DocumentModel entry = session.getEntry("user_1");
            assertNotNull(entry);
            assertEquals(baseHitsCount, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());

            // Second call will use the cache
            entry = session.getEntry("user_1");
            assertNotNull(entry);
            assertEquals(baseHitsCount + 1, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());

            // Again
            entry = session.getEntry("user_1");
            assertNotNull(entry);
            assertEquals(baseHitsCount + 2, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());
        }
    }

    @Test
    public void testNegativeCaching() throws Exception {
        DirectoryCache cache = getDirectory().getCache();
        cache.setNegativeCaching(Boolean.TRUE);
        try {
            doTestNegativeCaching();
        } finally {
            cache.setNegativeCaching(null);
        }
    }

    protected void doTestNegativeCaching() throws Exception {
        try (Session session = getDirectory().getSession()) {
            MetricRegistry registry = SharedMetricRegistries.getOrCreate(MetricsService.class.getName());
            Counter hitsCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "hit")
                                  .tagged("directory", "userDirectory"));
            Counter negativeHitsCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "hit", "null")
                                  .tagged("directory", "userDirectory"));
            Counter missesCounter = registry.counter(
                    MetricRegistry.name("nuxeo", "directories", "directory", "cache", "miss")
                                  .tagged("directory", "userDirectory"));
            long baseHitsCount = hitsCounter.getCount();
            long baseNegativeHitsCount = negativeHitsCounter.getCount();
            long baseMissesCount = missesCounter.getCount();

            // First call will update cache
            DocumentModel entry = session.getEntry("NO_SUCH_USER");
            assertNull(entry);
            assertEquals(baseHitsCount, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());

            // Second call will use the negative cache
            entry = session.getEntry("NO_SUCH_USER");
            assertNull(entry);
            assertEquals(baseHitsCount, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount + 1, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());

            // Again
            entry = session.getEntry("NO_SUCH_USER");
            assertNull(entry);
            assertEquals(baseHitsCount, hitsCounter.getCount());
            assertEquals(baseNegativeHitsCount + 2, negativeHitsCounter.getCount());
            assertEquals(baseMissesCount + 1, missesCounter.getCount());
        }
    }
}
