/*
 * (C) Copyright 2012-2019 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     mguillaume
 *     Yannis JULIENNE
 */
package org.nuxeo.launcher.info;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.connect.update.LocalPackage;
import org.nuxeo.connect.update.Package;
import org.nuxeo.connect.update.PackageDependency;
import org.nuxeo.connect.update.PackageException;
import org.nuxeo.connect.update.PackageState;
import org.nuxeo.connect.update.PackageType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "package")
@XmlType(propOrder = { "id", "state", "version", "name", "type", "targetPlatforms", "vendor", "supportsHotReload",
        "provides", "dependencies", "conflicts", "title", "description", "licenseType", "licenseUrl", "templates" })
public class PackageInfo {

    private static final Logger log = LogManager.getLogger(PackageInfo.class);

    public String name;

    public String version;

    public String id;

    public PackageState state;

    public String title;

    public String description;

    public String licenseType;

    public String licenseUrl;

    public String[] targetPlatforms;

    public PackageType type;

    public String vendor;

    public PackageDependency[] provides;

    public PackageDependency[] dependencies;

    public PackageDependency[] conflicts;

    public boolean supportsHotReload;

    public Set<String> templates;

    public PackageInfo() {
    }

    /**
     * @since 5.7
     */
    public PackageInfo(Package pkg) {
        name = pkg.getName();
        version = pkg.getVersion().toString();
        id = pkg.getId();
        state = pkg.getPackageState();
        title = pkg.getTitle();
        description = pkg.getDescription();
        licenseType = pkg.getLicenseType();
        licenseUrl = pkg.getLicenseUrl();
        targetPlatforms = pkg.getTargetPlatforms();
        type = pkg.getType();
        provides = pkg.getProvides();
        dependencies = pkg.getDependencies();
        conflicts = pkg.getConflicts();
        supportsHotReload = pkg.supportsHotReload();
        templates = templates(pkg);
    }

    /**
     * @since 8.3
     */
    private Set<String> templates(Package pkg) {
        if (!(pkg instanceof LocalPackage)) {
            return Collections.emptySet();
        }
        Set<String> templatesFound = new HashSet<>();
        try {
            File installFile = ((LocalPackage) pkg).getInstallFile();
            if (!installFile.exists()) {
                return Collections.emptySet();
            }
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document dom = db.parse(installFile);
            NodeList nodes = dom.getDocumentElement().getElementsByTagName("config");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                if (!node.hasAttribute("addtemplate")) {
                    continue;
                }
                StringTokenizer tokenizer = new StringTokenizer(node.getAttribute("addtemplate"), ",");
                while (tokenizer.hasMoreTokens()) {
                    templatesFound.add(tokenizer.nextToken());
                }

            }
        } catch (PackageException | ParserConfigurationException | SAXException | IOException e) {
            log.warn("Could not parse install file for {}", pkg.getName(), e);
        }
        return templatesFound;
    }
}
