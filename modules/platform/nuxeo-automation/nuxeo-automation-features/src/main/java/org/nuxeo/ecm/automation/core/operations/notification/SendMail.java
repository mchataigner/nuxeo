/*
 * (C) Copyright 2006-2018 Nuxeo (http://nuxeo.com/) and others.
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
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.core.operations.notification;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.nuxeo.ecm.core.management.api.AdministrativeStatus.PASSIVE;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.mail.MessagingException;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.collectors.DocumentModelCollector;
import org.nuxeo.ecm.automation.core.mail.Composer;
import org.nuxeo.ecm.automation.core.mail.Mailer;
import org.nuxeo.ecm.automation.core.mail.Mailer.Message;
import org.nuxeo.ecm.automation.core.mail.Mailer.Message.AS;
import org.nuxeo.ecm.automation.core.scripting.Scripting;
import org.nuxeo.ecm.automation.core.util.StringList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.PropertyException;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.impl.ListProperty;
import org.nuxeo.ecm.core.api.model.impl.MapProperty;
import org.nuxeo.ecm.core.api.model.impl.primitives.BlobProperty;
import org.nuxeo.ecm.core.management.api.AdministrativeStatusManager;
import org.nuxeo.ecm.platform.ec.notification.service.NotificationServiceHelper;
import org.nuxeo.ecm.platform.rendering.fm.FreemarkerEngine;
import org.nuxeo.mail.MailException;
import org.nuxeo.mail.MailMessage;
import org.nuxeo.mail.MailService;
import org.nuxeo.runtime.api.Framework;

import freemarker.template.Template;
import freemarker.template.TemplateException;

/**
 * Save the session - TODO remove this?
 *
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
@Operation(id = SendMail.ID, category = Constants.CAT_NOTIFICATION, label = "Send E-Mail", description = "Send an email using the input document to the specified recipients. You can use the HTML parameter to specify whether you message is in HTML format or in plain text. Also you can attach any blob on the current document to the message by using the comma separated list of xpath expressions 'files'. If you xpath points to a blob list all blobs in the list will be attached. Return back the input document(s). If rollbackOnError is true, the whole chain will be rollbacked if an error occurs while trying to send the email (for instance if no SMTP server is configured), else a simple warning will be logged and the chain will continue.", aliases = {
        "Notification.SendMail" })
public class SendMail {

    private static final Logger log = LogManager.getLogger(SendMail.class);

    /**
     * @deprecated since 11.1 due to its static modifier, it messes up tests, instantiate {@link Composer} instead
     */
    @Deprecated(since = "11.1")
    public static final Composer COMPOSER = new Composer();

    public static final String ID = "Document.Mail";

    @Context
    protected OperationContext ctx;

    @Param(name = "from")
    protected String from;

    @Param(name = "to", required = false)
    protected StringList to;

    /**
     * @since 5.9.1
     */
    @Param(name = "cc", required = false)
    protected StringList cc;

    /**
     * @since 5.9.1
     */
    @Param(name = "bcc", required = false)
    protected StringList bcc;

    /**
     * @since 5.9.1
     */
    @Param(name = "replyto", required = false)
    protected StringList replyto;

    @Param(name = "subject")
    protected String subject;

    @Param(name = "message", widget = Constants.W_MAIL_TEMPLATE)
    protected String message;

    @Param(name = "HTML", required = false, values = { "false" })
    protected boolean asHtml = false;

    @Param(name = "files", required = false)
    protected StringList blobXpath;

    @Param(name = "rollbackOnError", required = false, values = { "true" })
    protected boolean rollbackOnError = true;

    /**
     * @since 5.9.1
     */
    @Param(name = "Strict User Resolution", required = false)
    protected boolean isStrict = true;

    @Param(name = "viewId", required = false, values = { "view_documents" })
    protected String viewId = "view_documents";

    @OperationMethod(collector = DocumentModelCollector.class)
    public DocumentModel run(DocumentModel doc) throws TemplateException, OperationException, IOException {
        send(doc);
        return doc;
    }

    protected String getContent() throws OperationException, IOException {
        message = message.trim();
        if (message.startsWith("template:")) {
            String name = message.substring("template:".length()).trim();
            URL url = MailTemplateHelper.getTemplate(name);
            if (url == null) {
                throw new OperationException("No such mail template: " + name);
            }
            try (InputStream in = url.openStream()) {
                return IOUtils.toString(in, UTF_8);
            }
        } else {
            return StringEscapeUtils.unescapeHtml4(message);
        }
    }

    protected void send(DocumentModel doc) throws TemplateException, OperationException, IOException {
        // TODO should sent one by one to each recipient? and have the template
        // rendered for each recipient? Use: "mailto" var name?
        try {
            AdministrativeStatusManager asm = Framework.getService(AdministrativeStatusManager.class);
            if (asm != null && PASSIVE.equals(asm.getStatus("org.nuxeo.ecm.smtp").getState())) {
                log.debug("SMTP is in passive mode, mail not sent");
                return;
            }
            Map<String, Object> map = Scripting.initBindings(ctx);
            // do not use document wrapper which is working only in mvel.
            map.put("Document", doc);
            map.put("docUrl",
                    createDocUrlWithToken(MailTemplateHelper.getDocumentUrl(doc, viewId), (String) map.get("token")));
            map.put("subject", subject);
            map.put("to", to);
            List<MailBox> tos = MailBox.fetchPersonsFromList(to, isStrict);
            map.put("toResolved", tos);
            map.put("from", from);
            List<MailBox> froms = MailBox.fetchPersonsFromString(from, isStrict);
            map.put("fromResolved", froms);
            map.put("cc", cc);
            List<MailBox> ccs = MailBox.fetchPersonsFromList(cc, isStrict);
            map.put("ccResolved", ccs);
            map.put("bcc", bcc);
            List<MailBox> bccs = MailBox.fetchPersonsFromList(bcc, isStrict);
            map.put("bccResolved", bccs);
            map.put("replyto", replyto);
            List<MailBox> replytos = MailBox.fetchPersonsFromList(replyto, isStrict);
            map.put("replytoResolved", replytos);
            map.put("viewId", viewId);
            map.put("baseUrl", NotificationServiceHelper.getNotificationService().getServerUrlPrefix());
            map.put("Runtime", Framework.getRuntime());

            var contentType = String.format("text/%s ; charset=utf-8", asHtml ? "html" : "plain");
            var msg = new MailMessage.Builder(asStringList(tos)).from(asStringList(froms))
                                                                .cc(asStringList(ccs))
                                                                .bcc(asStringList(bccs))
                                                                .replyTo(asStringList(replytos))
                                                                .attachments(getBlobs(doc))
                                                                .subject(subject)
                                                                .content(renderContent(map), contentType)
                                                                .build();

            // send
            Framework.getService(MailService.class).sendMail(msg);
        } catch (NuxeoException | TemplateException | OperationException | IOException e) {
            if (rollbackOnError) {
                if (e instanceof MailException) {
                    // the Automation framework doesn't handle MailException (instance of NuxeoException) the same way
                    // as OperationException
                    throw new OperationException(e);
                }
                throw e;
            } else {
                log.warn(
                        "An error occurred while trying to execute the {} operation, see complete stack trace below. Continuing chain since 'rollbackOnError' was set to false.",
                        ID, e);
            }
        }
    }

    // Only visible for testing purposes
    protected String createDocUrlWithToken(String documentUrl, String token) {
        AdministrativeStatusManager asm = Framework.getService(AdministrativeStatusManager.class);
        if (asm != null && PASSIVE.equals(asm.getStatus("org.nuxeo.ecm.smtp").getState())) {
            log.debug("SMTP is in passive mode, mail not sent");
            return null;
        }
        return token != null ? UriBuilder.fromUri(documentUrl).queryParam("token", token).build().toString()
                : documentUrl;
    }

    /**
     * @since 5.9.1
     * @deprecated since 2023.4 unused. {@link #send(DocumentModel)} now uses a {@link MailMessage.Builder}
     */
    @Deprecated(since = "2023.4")
    protected void addMailBoxInfo(Mailer.Message msg, Map<String, Object> map) throws MessagingException {
        addMailBoxInfoInMessageHeader(msg, AS.FROM, (List<MailBox>) map.get("fromResolved"));
        addMailBoxInfoInMessageHeader(msg, AS.TO, (List<MailBox>) map.get("toResolved"));
        addMailBoxInfoInMessageHeader(msg, AS.CC, (List<MailBox>) map.get("ccResolved"));
        addMailBoxInfoInMessageHeader(msg, AS.BCC, (List<MailBox>) map.get("bccResolved"));
        if (replyto != null && !replyto.isEmpty()) {
            msg.setReplyTo(null);
            addMailBoxInfoInMessageHeader(msg, AS.REPLYTO, (List<MailBox>) map.get("replytoResolved"));
        }
    }

    /**
     * @since 5.9.1
     */
    protected void addMailBoxInfoInMessageHeader(Message msg, AS as, List<MailBox> persons) throws MessagingException {
        for (MailBox person : persons) {
            msg.addInfoInMessageHeader(person.toString(), as);
        }
    }

    /**
     * @deprecated since 2023.4 unused. {@link #send(DocumentModel)} now uses a {@link MailMessage.Builder}
     */
    @Deprecated(since = "2023.4")
    protected Mailer.Message createMessage(DocumentModel doc, String message, Map<String, Object> map)
            throws MessagingException, TemplateException, IOException {
        var composer = new Composer();
        return composer.newMixedMessage(message, map, asHtml ? "html" : "plain", getBlobs(doc));
    }

    protected String renderContent(Map<String, Object> map) throws IOException, OperationException, TemplateException {
        var engine = new FreemarkerEngine();
        var reader = new StringReader(getContent());
        var template = new Template("@inline", reader, engine.getConfiguration(), "UTF-8");
        var writer = new StringWriter();
        var env = template.createProcessingEnvironment(map, writer, engine.getObjectWrapper());
        env.process();
        return writer.toString();
    }

    protected <P> List<String> asStringList(List<P> inputList) {
        return inputList.stream().map(Objects::toString).collect(Collectors.toList());
    }

    protected List<Blob> getBlobs(DocumentModel doc) {
        if (blobXpath == null) {
            return List.of();
        }
        List<Blob> blobs = new ArrayList<>();
        for (String xpath : blobXpath) {
            try {
                Property p = doc.getProperty(xpath);
                if (p instanceof BlobProperty) {
                    getBlob(p.getValue(), blobs);
                } else if (p instanceof ListProperty) {
                    for (Property pp : p) {
                        getBlob(pp.getValue(), blobs);
                    }
                } else if (p instanceof MapProperty) {
                    for (Property sp : ((MapProperty) p).values()) {
                        getBlob(sp.getValue(), blobs);
                    }
                } else {
                    Object o = p.getValue();
                    if (o instanceof Blob) {
                        blobs.add((Blob) o);
                    }
                }
            } catch (PropertyException pe) {
                log.error("Error while fetching blobs: {}", pe.getMessage());
                log.debug(pe, pe);
            }
        }
        return blobs;
    }

    /**
     * @since 5.7
     * @param o: the object to introspect to find a blob
     * @param blobs: the Blob list where the blobs are put during property introspection
     */
    @SuppressWarnings("unchecked")
    protected void getBlob(Object o, List<Blob> blobs) {
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                getBlob(item, blobs);
            }
        } else if (o instanceof Map) {
            for (Object item : ((Map<String, Object>) o).values()) {
                getBlob(item, blobs);
            }
        } else if (o instanceof Blob) {
            blobs.add((Blob) o);
        }

    }
}
