/*
 * (C) Copyright 2009 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Anahide Tchertchian
 */
package org.nuxeo.ecm.directory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.directory.impl.AbstractDirectoryDeleteConstraint;

/**
 * Delete constraint preventing from removing an entry from a parent directory if it is referenced in a child directory.
 * <p>
 * Needs to know the child directory name and the field where parent entry id is declared on it.
 *
 * @author Anahide Tchertchian
 */
public class HierarchicalDirectoryDeleteConstraint extends AbstractDirectoryDeleteConstraint {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(HierarchicalDirectoryDeleteConstraint.class);

    protected String targetDirectory;

    protected String targetDirectoryField;

    @Override
    public void setProperties(Map<String, String> properties) {
        String targetDirKey = "targetDirectory";
        String targetDirFieldKey = "targetDirectoryField";
        if (properties == null) {
            throw new DirectoryException(String.format("This delete constraint requires properties '%s' and '%s'",
                    targetDirKey, targetDirFieldKey));
        }
        if (!properties.containsKey(targetDirKey)) {
            throw new DirectoryException(String.format("This delete constraint requires property '%s'", targetDirKey));
        }
        if (!properties.containsKey(targetDirFieldKey)) {
            throw new DirectoryException(
                    String.format("This delete constraint requires property '%s'", targetDirFieldKey));
        }
        targetDirectory = properties.get(targetDirKey);
        targetDirectoryField = properties.get(targetDirFieldKey);
    }

    @Override
    public boolean canDelete(DirectoryService dirService, String entryId) {
        try (Session dirSession = dirService.open(targetDirectory)) {
            // search for given entry id usage in this directory
            Map<String, Serializable> filter = new HashMap<>();
            filter.put(targetDirectoryField, entryId);
            DocumentModelList res = dirSession.query(filter);
            if (res.isEmpty()) {
                return true;
            }
            log.debug("Can not delete: {} / {}, constraint on {}:{}", targetDirectory, entryId, targetDirectoryField,
                    res.get(0).getId());
            return false;
        }
    }

}
