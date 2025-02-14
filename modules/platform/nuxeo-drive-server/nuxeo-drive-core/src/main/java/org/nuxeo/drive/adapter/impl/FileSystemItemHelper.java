/*
 * (C) Copyright 2013-2016 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 *     Thierry Martins <tmartins@nuxeo.com>
 */
package org.nuxeo.drive.adapter.impl;

import java.io.IOException;

import org.apache.commons.codec.digest.DigestUtils;
import org.nuxeo.drive.adapter.FileSystemItem;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Helper for {@link FileSystemItem} manipulation.
 *
 * @author Antoine Taillefer
 */
public final class FileSystemItemHelper {

    public static final String MD5_DIGEST_ALGORITHM = "MD5";

    private FileSystemItemHelper() {
        // Helper class
    }

    /**
     * Gets the md5 digest of the given blob.
     */
    public static String getMD5Digest(Blob blob) {
        try {
            return DigestUtils.md5Hex(blob.getStream());
        } catch (IOException e) {
            throw new NuxeoException(String.format("Error while computing digest for blob %s.", blob.getFilename()), e);
        }
    }

}
