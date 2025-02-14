/*
 * (C) Copyright 2014-2023 Nuxeo (http://nuxeo.com/) and others.
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
 *     Nelson Silva <nelson.silva@inevo.pt>
 */
package org.nuxeo.ecm.platform.auth.saml.key;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.saml.common.SAMLRuntimeException;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.impl.KeyStoreCredentialResolver;

import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import net.shibboleth.utilities.java.support.resolver.ResolverException;

/**
 * An implementation of {@link KeyManager} that uses a JKS key store.
 */
public class KeyManagerImpl extends DefaultComponent implements KeyManager {

    private static final Logger log = LogManager.getLogger(KeyManagerImpl.class);

    private static final String KEYSTORE_TYPE = "JKS";

    protected KeyDescriptor config;

    private KeyStore keyStore;

    private KeyStoreCredentialResolver credentialResolver;

    private Set<String> availableCredentials;

    @Override
    public void registerContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        config = (KeyDescriptor) contribution;
        setup();
    }

    private void setup() {
        if (config != null) {
            try {
                keyStore = getKeyStore(config.getKeystoreFilePath(), config.getKeystorePassword());
            } catch (SecurityException e) {
                throw new RuntimeException(e);
            }
            credentialResolver = new KeyStoreCredentialResolver(keyStore, config.getPasswords());
        } else {
            keyStore = null;
            credentialResolver = null;
            availableCredentials = null;
        }
    }

    private KeyStore getKeyStore(String path, String password) throws SecurityException {
        KeyStore ks;
        try {
            File rootKeystoreFile = new File(path);
            if (!rootKeystoreFile.exists()) {
                throw new SecurityException(
                        "Unable to find keyStore at " + new File(".").getAbsolutePath() + File.separator + path);
            }
            try (InputStream keystoreIS = new FileInputStream(rootKeystoreFile)) {
                ks = KeyStore.getInstance(KEYSTORE_TYPE);
                ks.load(keystoreIS, password.toCharArray());
            }
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new SecurityException("Unable to load the key store", e);
        }
        return ks;
    }

    @Override
    public void unregisterContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        config = null;
        setup();
    }

    @Override
    public Credential getCredential(String keyName) {
        try {
            return resolveSingle(new CriteriaSet(new EntityIdCriterion(keyName)));
        } catch (ResolverException e) {
            throw new SAMLRuntimeException("Can't obtain SP signing key", e);
        }
    }

    @Override
    public Set<String> getAvailableCredentials() {
        if (availableCredentials != null) {
            return availableCredentials;
        }
        try {
            availableCredentials = new HashSet<>();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                availableCredentials.add(aliases.nextElement());
            }
            return availableCredentials;
        } catch (KeyStoreException e) {
            throw new RuntimeException("Unable to load aliases from keyStore", e);
        }
    }

    @Override
    public X509Certificate getCertificate(String alias) {
        if (alias == null || alias.length() == 0) {
            return null;
        }
        try {
            return (X509Certificate) keyStore.getCertificate(alias);
        } catch (KeyStoreException e) {
            log.error("Error loading certificate", e);
        }
        return null;
    }

    @Override
    public Credential getSigningCredential() {
        if (!hasCredentials() || config.getSigningKey() == null) {
            return null;
        }
        return getCredential(config.getSigningKey());
    }

    @Override
    public Credential getEncryptionCredential() {
        if (!hasCredentials() || config.getEncryptionKey() == null) {
            return null;
        }
        return getCredential(config.getEncryptionKey());
    }

    @Override
    public Credential getTlsCredential() {
        if (!hasCredentials() || config.getTlsKey() == null) {
            return null;
        }
        return getCredential(config.getTlsKey());
    }

    @NotNull
    @Override
    public Iterable<Credential> resolve(CriteriaSet criteria) throws ResolverException {
        return credentialResolver.resolve(criteria);
    }

    @Override
    public Credential resolveSingle(CriteriaSet criteria) throws ResolverException {
        return credentialResolver.resolveSingle(criteria);
    }

    private boolean hasCredentials() {
        return config != null && credentialResolver != null;
    }
}
