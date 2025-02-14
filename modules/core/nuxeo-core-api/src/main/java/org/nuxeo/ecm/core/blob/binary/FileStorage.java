/*
 * (C) Copyright 2011-2014 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.blob.binary;

import java.io.File;
import java.io.IOException;

import org.nuxeo.ecm.core.blob.BlobStoreBlobProvider;

/**
 * Interface to store a file or fetch a file or its length.
 *
 * @since 5.9.2
 * @deprecated since 2023.9, prefer {@link BlobStoreBlobProvider} implementation instead
 */
@Deprecated(since = "2023.9")
public interface FileStorage {

    /**
     * Stores a file based on a key.
     *
     * @param key the file key
     * @param file the file
     * @throws IOException if a storage error occurred
     */
    void storeFile(String key, File file) throws IOException;

    /**
     * Fetches a file based on its key.
     *
     * @param key the file key
     * @param file the file to use to store the fetched data
     * @return {@code true} if the file was fetched, {@code false} if the file was not found
     * @throws IOException if a storage error occurred
     */
    boolean fetchFile(String key, File file) throws IOException;

    /**
     * Does a blob with the given key exist in the storage.
     * <p>
     * The existence of the blob is checked in the storage where the blob is effectively stored. Intermediate cache is
     * ignored.
     *
     * @param key the blob key
     * @return true if it exists in the storage
     * @since 2023
     */
    default boolean exists(String key) {
        return false;
    }

}
