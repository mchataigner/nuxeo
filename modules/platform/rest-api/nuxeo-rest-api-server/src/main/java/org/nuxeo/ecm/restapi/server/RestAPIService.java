/*
 * (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
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
package org.nuxeo.ecm.restapi.server;

/**
 * @since 2021.25
 * @deprecated since 2023.0, use {@link org.nuxeo.runtime.pubsub.ClusterActionService} instead
 */
@Deprecated
public interface RestAPIService {

    /**
     * Propagate an action to all others nodes in the cluster.
     */
    void propagateAction(String action, String param);
}
