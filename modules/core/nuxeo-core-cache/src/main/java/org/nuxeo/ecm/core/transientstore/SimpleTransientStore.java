/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Thierry Delprat <tdelprat@nuxeo.com>
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 */

package org.nuxeo.ecm.core.transientstore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreConfig;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Default implementation (i.e., not cluster aware) of the {@link TransientStore}. Uses {@link StorageEntry} as a
 * representation of an entry in the store.
 *
 * @since 7.2
 */
public class SimpleTransientStore extends AbstractTransientStore {

    private static final Logger log = LogManager.getLogger(SimpleTransientStore.class);

    protected Cache<String, Serializable> l1Cache;

    protected Cache<String, Serializable> l2Cache;

    protected AtomicLong storageSize = new AtomicLong(0);

    public SimpleTransientStore() {
    }

    @Override
    public void init(TransientStoreConfig config) {
        log.debug("Initializing SimpleTransientStore: {}", config::getName);
        super.init(config);
        l1Cache = CacheBuilder.newBuilder().expireAfterWrite(config.getFirstLevelTTL(), TimeUnit.MINUTES).build();
        l2Cache = CacheBuilder.newBuilder().expireAfterWrite(config.getSecondLevelTTL(), TimeUnit.MINUTES).build();
    }

    @Override
    public void shutdown() {
        log.debug("Shutting down SimpleTransientStore: {}", config::getName);
    }

    @Override
    public boolean exists(String key) {
        return getL1Cache().getIfPresent(key) != null || getL2Cache().getIfPresent(key) != null;
    }

    @Override
    public Set<String> keySet() {
        Set<String> keys = new HashSet<>();
        keys.addAll(getL1Cache().asMap().keySet());
        keys.addAll(getL2Cache().asMap().keySet());
        return keys;
    }

    @Override
    public Stream<String> keyStream() {
        return keySet().stream();
    }

    @Override
    public void putParameter(String key, String parameter, Serializable value) {
        synchronized (this) {
            StorageEntry entry = getStorageEntry(key);
            if (entry == null) {
                entry = new StorageEntry();
            }
            entry.putParam(parameter, value);
            log.debug("Setting parameter: {} to value: {} in StorageEntry stored at key: {}", parameter, value, key);
            putStorageEntry(key, entry);
        }
    }

    @Override
    public Serializable getParameter(String key, String parameter) {
        StorageEntry entry = getStorageEntry(key);
        if (entry == null) {
            return null;
        }
        Serializable res = entry.getParam(parameter);
        log.debug("Fetched parameter: {} from StorageEntry stored at key {}: {}", parameter, key, res);
        return res;
    }

    @Override
    public void putParameters(String key, Map<String, Serializable> parameters) {
        synchronized (this) {
            StorageEntry entry = getStorageEntry(key);
            if (entry == null) {
                entry = new StorageEntry();
            }
            entry.putParams(parameters);
            log.debug("Setting parameters: {} in StorageEntry stored at key: {}", parameters, key);
            putStorageEntry(key, entry);
        }
    }

    @Override
    public Map<String, Serializable> getParameters(String key) {
        StorageEntry entry = getStorageEntry(key);
        if (entry == null) {
            return null;
        }
        Map<String, Serializable> res = new HashMap<>(entry.getParams());
        log.debug("Fetched parameters from StorageEntry stored at key {}: {}", key, res);
        return res;
    }

    @Override
    public List<Blob> getBlobs(String key) {
        StorageEntry entry = getStorageEntry(key);
        if (entry == null) {
            return null;
        }
        // Get blob information from the store
        List<Map<String, String>> blobInfos = entry.getBlobInfos();
        if (blobInfos == null) {
            return new ArrayList<>();
        }
        // Load blobs from the file system
        return loadBlobs(blobInfos);
    }

    @Override
    public long getSize(String key) {
        StorageEntry entry = getStorageEntry(key);
        if (entry == null) {
            return -1;
        }
        long size = entry.getSize();
        log.debug("Fetched field \"size\" from StorageEntry stored at key {}: {}", key, size);
        return size;
    }

    @Override
    public boolean isCompleted(String key) {
        StorageEntry entry = getStorageEntry(key);
        boolean completed = entry != null && entry.isCompleted();
        log.debug("Fetched field \"completed\" from StorageEntry stored at key {}: {}", key, completed);
        return completed;
    }

    @Override
    public void setCompleted(String key, boolean completed) {
        synchronized (this) {
            StorageEntry entry = getStorageEntry(key);
            if (entry == null) {
                entry = new StorageEntry();
            }
            entry.setCompleted(completed);
            log.debug("Setting field \"completed\" to value: {} in StorageEntry stored at key: {}", completed, key);
            putStorageEntry(key, entry);
        }
    }

    @Override
    public void release(String key) {
        StorageEntry entry = (StorageEntry) getL1Cache().getIfPresent(key);
        if (entry != null) {
            log.debug("Invalidating StorageEntry stored at key: {} form L1 cache", key);
            getL1Cache().invalidate(key);
            if (getStorageSize() <= config.getTargetMaxSizeMB() * (1024 * 1024) || config.getTargetMaxSizeMB() < 0) {
                log.debug("Putting StorageEntry at key: {} in L2 cache", key);
                getL2Cache().put(key, entry);
            }
        }
    }

    @Override
    protected void persistBlobs(String key, long sizeOfBlobs, List<Map<String, String>> blobInfos) {
        synchronized (this) {
            StorageEntry entry = getStorageEntry(key);
            // Update storage size
            if (entry == null) {
                if (sizeOfBlobs > 0) {
                    incrementStorageSize(sizeOfBlobs);
                }
                entry = new StorageEntry();
            } else {
                incrementStorageSize(sizeOfBlobs - entry.getSize());
            }
            // Update entry size
            entry.setSize(sizeOfBlobs);
            // Set blob information
            entry.setBlobInfos(blobInfos);
            log.debug("Setting blobs: {} in StorageEntry stored at key: {}", blobInfos, key);
            putStorageEntry(key, entry);
        }
    }

    @Override
    public long getStorageSize() {
        long size = storageSize.get();
        log.debug("Fetched storage size of store {}: {}", config.getName(), size);
        return size;
    }

    @Override
    protected void setStorageSize(long newSize) {
        log.debug("Setting storage size of store: {} to: {}", config.getName(), newSize);
        storageSize.set(newSize);
    }

    @Override
    protected long incrementStorageSize(long size) {
        long incremented = storageSize.addAndGet(size);
        log.debug("Incremented storage size of store: {} to: {}", config.getName(), incremented);
        return incremented;
    }

    @Override
    protected long decrementStorageSize(long size) {
        long decremented = storageSize.addAndGet(-size);
        log.debug("Decremented storage size of store: {} to: {}", config.getName(), decremented);
        return decremented;
    }

    @Override
    protected void removeEntry(String key) {
        synchronized (this) {
            StorageEntry entry = (StorageEntry) getL1Cache().getIfPresent(key);
            if (entry == null) {
                entry = (StorageEntry) getL2Cache().getIfPresent(key);
                log.debug("Invalidating StorageEntry stored at key: {} form L2 cache", key);
                getL2Cache().invalidate(key);
            } else {
                log.debug("Invalidating StorageEntry stored at key: {} form L1 cache", key);
                getL1Cache().invalidate(key);
            }
            if (entry != null) {
                long entrySize = entry.getSize();
                if (entrySize > 0) {
                    decrementStorageSize(entrySize);
                }
            }
        }
    }

    @Override
    protected void removeAllEntries() {
        log.debug("Invalidating all entries from L1 and L2 caches");
        getL1Cache().invalidateAll();
        getL2Cache().invalidateAll();
    }

    public Cache<String, Serializable> getL1Cache() {
        return l1Cache;
    }

    public Cache<String, Serializable> getL2Cache() {
        return l2Cache;
    }

    /**
     * Returns the {@link StorageEntry} representing the entry with the given {@code key} or {@code null} if it doesn't
     * exist.
     */
    protected StorageEntry getStorageEntry(String key) {
        StorageEntry entry = (StorageEntry) getL1Cache().getIfPresent(key);
        if (entry == null) {
            entry = (StorageEntry) getL2Cache().getIfPresent(key);
        }
        return entry;
    }

    /**
     * Stores the given {@code entry} with the given {@code key}.
     * <p>
     * If an entry exists with the given {@code key} it is overwritten.
     */
    protected void putStorageEntry(String key, StorageEntry entry) {
        getL1Cache().put(key, entry);
    }

}
