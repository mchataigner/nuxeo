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
 *     Thomas Roger
 */

package org.nuxeo.wopi;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.nuxeo.runtime.model.Descriptor.UNIQUE_DESCRIPTOR_ID;
import static org.nuxeo.wopi.Constants.WOPI_DISCOVERY_KEY;
import static org.nuxeo.wopi.Constants.WOPI_DISCOVERY_REFRESH_EVENT;
import static org.nuxeo.wopi.Constants.WOPI_DISCOVERY_URL_PROPERTY;
import static org.nuxeo.wopi.Constants.WOPI_KEY_VALUE_STORE_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.common.Environment;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventProducer;
import org.nuxeo.ecm.core.event.impl.EventContextImpl;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.cluster.ClusterService;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.pubsub.AbstractPubSubBroker;
import org.nuxeo.runtime.pubsub.SerializableMessage;
import org.nuxeo.runtime.services.config.ConfigurationService;
import org.nuxeo.wopi.WOPIDiscovery.NetZone;

/**
 * @since 10.3
 */
public class WOPIServiceImpl extends DefaultComponent implements WOPIService {

    private static final Logger log = LogManager.getLogger(WOPIServiceImpl.class);

    // @since 2021.40
    public static final String XP_CHECK_FILE_INFO_UPDATER = "checkFileInfoUpdater";

    public static final String PLACEHOLDER_IS_LICENSED_USER = "IsLicensedUser";

    public static final String PLACEHOLDER_IS_LICENSED_USER_VALUE = "1";

    public static final String WOPI_PROPERTY_NAMESPACE = "org.nuxeo.wopi";

    public static final String SUPPORTED_APP_NAMES_PROPERTY_KEY = "supportedAppNames";

    // @since 2021.37
    public static final String DOWNLOAD_REASON = "download";

    protected static final String WOPI_DISCOVERY_INVAL_PUBSUB_TOPIC = "wopiDiscoveryInval";

    // extension => app name
    protected Map<String, String> extensionAppNames = new HashMap<>();

    // extension => wopi action => wopi action url
    protected Map<String, Map<String, String>> extensionActionURLs = new HashMap<>();

    protected PublicKey proofKey;

    protected PublicKey oldProofKey;

    protected String discoveryURL;

    protected WOPIDiscoveryInvalidator invalidator;

    protected CheckFileInfoUpdater checkFileInfoUpdater;

    @Override
    public void start(ComponentContext context) {
        CheckFileInfoUpdaterDescriptor desc = getDescriptor(XP_CHECK_FILE_INFO_UPDATER, UNIQUE_DESCRIPTOR_ID);
        if (desc != null) {
            checkFileInfoUpdater = desc.newInstance();
        }

        discoveryURL = Framework.getProperty(WOPI_DISCOVERY_URL_PROPERTY);
        if (!hasDiscoveryURL()) {
            log.warn("No WOPI discovery URL configured, WOPI disabled. Please configure the '{}' property.",
                    WOPI_DISCOVERY_URL_PROPERTY);
            return;
        }

        registerInvalidator();
        loadDiscovery();
    }

    @Override
    public void stop(ComponentContext context) {
        checkFileInfoUpdater = null;
        discoveryURL = null;
        proofKey = null;
        oldProofKey = null;

        unregisterInvalidator();
    }

    protected boolean hasDiscoveryURL() {
        return isNotBlank(discoveryURL);
    }

    protected void registerInvalidator() {
        ClusterService clusterService = Framework.getService(ClusterService.class);
        if (clusterService.isEnabled()) {
            // register WOPI discovery invalidator
            String nodeId = clusterService.getNodeId();
            invalidator = new WOPIDiscoveryInvalidator();
            invalidator.initialize(WOPI_DISCOVERY_INVAL_PUBSUB_TOPIC, nodeId);
            log.info("Registered WOPI discovery invalidator for node: {}", nodeId);
        } else {
            log.info("Not registering a WOPI discovery invalidator because clustering is not enabled");
        }
    }

    protected void unregisterInvalidator() {
        if (invalidator != null) {
            invalidator.close();
            invalidator = null;
        }
    }

    protected void loadDiscovery() {
        byte[] discoveryBytes = getDiscovery();
        if (ArrayUtils.isEmpty(discoveryBytes)) {
            refreshDiscovery();
        } else {
            loadDiscovery(discoveryBytes);
            fireRefreshDiscovery();
        }
    }

    protected boolean loadDiscovery(byte[] discoveryBytes) {
        WOPIDiscovery discovery;
        try {
            discovery = WOPIDiscovery.read(discoveryBytes);
        } catch (NuxeoException e) {
            log.error("Error while reading WOPI discovery: {}", e::getMessage);
            log.debug(e, e);
            return false;
        }

        NetZone netZone = discovery.getNetZone();
        if (netZone == null) {
            log.error("Invalid WOPI discovery, no net-zone element");
            return false;
        }

        List<String> supportedAppNames = getSupportedAppNames();
        netZone.getApps().stream().filter(app -> supportedAppNames.contains(app.getName())).forEach(this::registerApp);
        log.debug("Successfully loaded WOPI discovery: WOPI enabled");

        WOPIDiscovery.ProofKey pk = discovery.getProofKey();
        proofKey = ProofKeyHelper.getPublicKey(pk.getModulus(), pk.getExponent());
        oldProofKey = ProofKeyHelper.getPublicKey(pk.getOldModulus(), pk.getOldExponent());
        log.debug("Registered proof key: {}", proofKey);
        log.debug("Registered old proof key: {}", oldProofKey);
        return true;
    }

    protected void fireRefreshDiscovery() {
        EventContext ctx = new EventContextImpl();
        Event event = ctx.newEvent(WOPI_DISCOVERY_REFRESH_EVENT);
        Framework.getService(EventProducer.class).fireEvent(event);
    }

    protected List<String> getSupportedAppNames() {
        Serializable supportedAppNames = Framework.getService(ConfigurationService.class)
                                                  .getProperties(WOPI_PROPERTY_NAMESPACE)
                                                  .get(SUPPORTED_APP_NAMES_PROPERTY_KEY);
        if (!(supportedAppNames instanceof String[])) {
            return Collections.emptyList();
        }
        return Arrays.asList((String[]) supportedAppNames);
    }

    protected void registerApp(WOPIDiscovery.App app) {
        app.getActions().forEach(action -> {
            extensionAppNames.put(action.getExt(), app.getName());
            extensionActionURLs.computeIfAbsent(action.getExt(), k -> new HashMap<>())
                               .put(action.getName(),
                                       String.format("%s%s=%s&", action.getUrl().replaceFirst("<.*$", ""),
                                               PLACEHOLDER_IS_LICENSED_USER, PLACEHOLDER_IS_LICENSED_USER_VALUE));
        });
    }

    @Override
    public boolean isEnabled() {
        return !(extensionAppNames.isEmpty() || extensionActionURLs.isEmpty());
    }

    @Override
    public WOPIBlobInfo getWOPIBlobInfo(Blob blob) {
        if (!isEnabled() || !Helpers.supportsSync(blob)) {
            return null;
        }

        String extension = getExtension(blob);
        String appName = extensionAppNames.get(extension);
        Map<String, String> actionURLs = extensionActionURLs.get(extension);
        return appName == null || actionURLs.isEmpty() ? null : new WOPIBlobInfo(appName, actionURLs.keySet());
    }

    @Override
    public boolean isSupported(Blob blob) {
        String extension = getExtension(blob);
        String appName = extensionAppNames.get(extension);
        Map<String, String> actionURLs = extensionActionURLs.get(extension);
        return appName != null && !actionURLs.isEmpty();
    }

    @Override
    public String getActionURL(Blob blob, String action) {
        String extension = getExtension(blob);
        return extensionActionURLs.getOrDefault(extension, Collections.emptyMap()).get(action);
    }

    protected String getExtension(Blob blob) {
        String filename = blob.getFilename();
        if (filename == null) {
            return null;
        }

        String extension = FilenameUtils.getExtension(filename);
        return isNotBlank(extension) ? extension.toLowerCase() : null;
    }

    @Override
    public boolean verifyProofKey(String proofKeyHeader, String oldProofKeyHeader, String url, String accessToken,
            String timestampHeader) {
        if (StringUtils.isBlank(proofKeyHeader)) {
            return true; // assume valid
        }

        long timestamp = Long.parseLong(timestampHeader);
        if (!ProofKeyHelper.verifyTimestamp(timestamp)) {
            return false;
        }

        byte[] expectedProofBytes = ProofKeyHelper.getExpectedProofBytes(url, accessToken, timestamp);
        // follow flow from https://wopi.readthedocs.io/en/latest/scenarios/proofkeys.html#verifying-the-proof-keys
        boolean res = ProofKeyHelper.verifyProofKey(proofKey, proofKeyHeader, expectedProofBytes);
        if (!res && isNotBlank(oldProofKeyHeader)) {
            res = ProofKeyHelper.verifyProofKey(proofKey, oldProofKeyHeader, expectedProofBytes);
            if (!res) {
                res = ProofKeyHelper.verifyProofKey(oldProofKey, proofKeyHeader, expectedProofBytes);
            }
        }
        return res;
    }

    @Override
    public boolean refreshDiscovery() {
        if (!hasDiscoveryURL()) {
            // no need to try refreshing
            return false;
        }

        byte[] discoveryBytes = fetchDiscovery();
        if (ArrayUtils.isEmpty(discoveryBytes)) {
            return false;
        }
        log.debug("Successfully fetched WOPI discovery");

        if (loadDiscovery(discoveryBytes)) {
            storeDiscovery(discoveryBytes);
            if (invalidator != null) {
                invalidator.sendMessage(new WOPIDiscoveryInvalidation());
            }
            return true;
        }
        return false;
    }

    protected byte[] fetchDiscovery() {
        log.debug("Fetching WOPI discovery from discovery URL: {}", discoveryURL);
        HttpGet request = new HttpGet(discoveryURL);
        try (CloseableHttpClient httpClient = buildHttpClient();
                CloseableHttpResponse response = httpClient.execute(request);
                InputStream is = response.getEntity().getContent()) {
            return IOUtils.toByteArray(is);
        } catch (IOException e) {
            log.error("Error while fetching WOPI discovery: {}", e::getMessage);
            log.debug(e, e);
            return ArrayUtils.EMPTY_BYTE_ARRAY;
        }
    }

    protected CloseableHttpClient buildHttpClient() {
        var builder = HttpClientBuilder.create();
        configureProxy(builder);
        return builder.build();
    }

    protected void configureProxy(HttpClientBuilder builder) {
        var proxyHost = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_HOST);
        var proxyPort = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_PORT);
        var proxyLogin = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_LOGIN);
        var proxyPassword = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_PASSWORD);

        if (isNotBlank(proxyHost) && isNotBlank(proxyPort)) {
            var proxy = new HttpHost(proxyHost, Integer.parseInt(proxyPort));
            builder.setRoutePlanner(new DefaultProxyRoutePlanner(proxy));

            // proxyPassword could be blank
            if (isNotBlank(proxyLogin) && proxyPassword != null) {
                var credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(new AuthScope(proxy),
                        new UsernamePasswordCredentials(proxyLogin, proxyPassword));
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }
        }
    }

    protected byte[] getDiscovery() {
        return getKeyValueStore().get(WOPI_DISCOVERY_KEY);
    }

    protected void storeDiscovery(byte[] discoveryBytes) {
        getKeyValueStore().put(WOPI_DISCOVERY_KEY, discoveryBytes);
    }

    protected KeyValueStore getKeyValueStore() {
        return Framework.getService(KeyValueService.class).getKeyValueStore(WOPI_KEY_VALUE_STORE_NAME);
    }

    @Override
    public boolean checkDownloadBlob(DocumentModel doc, String xpath, Blob blob) {
        return Framework.getService(DownloadService.class).checkPermission(doc, xpath, blob, DOWNLOAD_REASON, Map.of());
    }

    @Override
    public Map<String, Serializable> updateCheckFileInfoProperties(Map<String, Serializable> checkFileInfoProperties) {
        if (checkFileInfoUpdater != null) {
            return checkFileInfoUpdater.update(checkFileInfoProperties);
        }
        return checkFileInfoProperties;
    }

    public static class WOPIDiscoveryInvalidation implements SerializableMessage {

        private static final long serialVersionUID = 1L;

        @Override
        public void serialize(OutputStream out) {
            // nothing to write, sending the message itself is enough
        }
    }

    public class WOPIDiscoveryInvalidator extends AbstractPubSubBroker<WOPIDiscoveryInvalidation> {

        @Override
        public WOPIDiscoveryInvalidation deserialize(InputStream in) {
            return new WOPIDiscoveryInvalidation();
        }

        @Override
        public void receivedMessage(WOPIDiscoveryInvalidation message) {
            // nothing to read from the message, receiving the message itself is enough
            byte[] discoveryBytes = getDiscovery();
            loadDiscovery(discoveryBytes);
        }
    }

}
