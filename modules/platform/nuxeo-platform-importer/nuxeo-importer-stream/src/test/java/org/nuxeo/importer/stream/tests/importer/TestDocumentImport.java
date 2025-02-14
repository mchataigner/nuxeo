/*
 * (C) Copyright 2016-2023 Nuxeo (http://nuxeo.com/) and others.
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
package org.nuxeo.importer.stream.tests.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.importer.stream.StreamImporters;
import org.nuxeo.importer.stream.consumer.BlobInfoWriter;
import org.nuxeo.importer.stream.consumer.BlobMessageConsumerFactory;
import org.nuxeo.importer.stream.consumer.DocumentMessageConsumerFactory;
import org.nuxeo.importer.stream.consumer.LogBlobInfoWriter;
import org.nuxeo.importer.stream.message.BlobInfoMessage;
import org.nuxeo.importer.stream.message.BlobMessage;
import org.nuxeo.importer.stream.message.DocumentMessage;
import org.nuxeo.importer.stream.producer.FileBlobMessageProducerFactory;
import org.nuxeo.importer.stream.producer.RandomDocumentMessageProducerFactory;
import org.nuxeo.importer.stream.producer.RandomStringBlobMessageProducerFactory;
import org.nuxeo.lib.stream.codec.Codec;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.lib.stream.log.Name;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerFactory;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerPolicy;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerPool;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerStatus;
import org.nuxeo.lib.stream.pattern.producer.ProducerFactory;
import org.nuxeo.lib.stream.pattern.producer.ProducerPool;
import org.nuxeo.lib.stream.pattern.producer.ProducerStatus;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.transaction.TransactionHelper;

@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("org.nuxeo.importer.stream")
@Deploy("org.nuxeo.ecm.platform.dublincore")
@Deploy("org.nuxeo.importer.stream:test-core-type-contrib.xml")
public class TestDocumentImport {

    @Inject
    protected StreamService streamService;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    CoreSession session;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File(FeaturesRunner.getBuildDirectory()));

    protected LogManager getLogManager() {
        return streamService.getLogManager();
    }

    protected List<Name> streamsToClean = new ArrayList<>();

    @After
    public void cleanStreams() {
        LogManager manager = getLogManager();
        streamsToClean.forEach(manager::delete);
        streamsToClean.clear();
    }

    @Test
    public void twoStepsImport() throws Exception {
        final int NB_QUEUE = 5;
        final short NB_PRODUCERS = 5;
        final int NB_DOCUMENTS = 2 * 100;
        final Name LOG_DOC = Name.ofUrn("import/twoSteps-doc");
        streamsToClean.add(LOG_DOC);

        Codec<DocumentMessage> docCodec = StreamImporters.getDocCodec();
        // 1. generate documents with blobs
        getLogManager().createIfNotExists(LOG_DOC, NB_QUEUE);
        ProducerPool<DocumentMessage> producers = new ProducerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                new RandomDocumentMessageProducerFactory(NB_DOCUMENTS, "en_US", 2), NB_PRODUCERS);
        List<ProducerStatus> ret = producers.start().get();
        assertEquals(NB_PRODUCERS, ret.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, ret.stream().mapToLong(r -> r.nbProcessed).sum());

        // 2. import documents
        DocumentModel root = session.getRootDocument();
        ConsumerPool<DocumentMessage> consumers = new ConsumerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                new DocumentMessageConsumerFactory(root.getRepositoryName(), root.getPathAsString()),
                ConsumerPolicy.BOUNDED);
        List<ConsumerStatus> ret2 = consumers.start().get();
        assertEquals(NB_QUEUE, ret2.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, ret2.stream().mapToLong(r -> r.committed).sum());
    }

    @Test
    public void fourStepsImport() throws Exception {
        final int NB_QUEUE = 5;
        final short NB_PRODUCERS = 5;
        final long NB_BLOBS = 100;
        final long NB_DOCUMENTS = 2_00;
        final Name LOG_BLOB = Name.ofUrn("import/fourSteps-blob");
        final Name LOG_BLOB_INFO = Name.ofUrn("import/fourSteps-blobInfo");
        final Name LOG_DOC = Name.ofUrn("import/fourSteps-doc");
        streamsToClean.add(LOG_BLOB);
        streamsToClean.add(LOG_BLOB_INFO);
        streamsToClean.add(LOG_DOC);

        Codec<BlobMessage> blobCodec = StreamImporters.getBlobCodec();
        Codec<BlobInfoMessage> blobInfoCodec = StreamImporters.getBlobInfoCodec();
        Codec<DocumentMessage> docCodec = StreamImporters.getDocCodec();
        assertTrue(getLogManager().createIfNotExists(LOG_BLOB, NB_QUEUE));
        // 1. generates blobs
        ProducerPool<BlobMessage> blobProducers = new ProducerPool<>(LOG_BLOB.getUrn(), getLogManager(), blobCodec,
                new RandomStringBlobMessageProducerFactory(NB_BLOBS, "en_US", 2, "1234"), NB_PRODUCERS);
        List<ProducerStatus> blobProducersStatus = blobProducers.start().get();
        assertEquals(NB_PRODUCERS, blobProducersStatus.size());
        assertEquals(NB_PRODUCERS * NB_BLOBS, blobProducersStatus.stream().mapToLong(r -> r.nbProcessed).sum());

        // 2. import blobs
        String blobProviderName = "test";
        assertTrue(getLogManager().createIfNotExists(LOG_BLOB_INFO, 1));
        BlobInfoWriter blobInfoWriter = new LogBlobInfoWriter(
                getLogManager().getAppender(LOG_BLOB_INFO, blobInfoCodec));
        ConsumerFactory<BlobMessage> blobFactory = new BlobMessageConsumerFactory(blobProviderName, blobInfoWriter);
        ConsumerPool<BlobMessage> blobConsumers = new ConsumerPool<>(LOG_BLOB.getUrn(), getLogManager(), blobCodec,
                blobFactory, ConsumerPolicy.BOUNDED);
        List<ConsumerStatus> blobConsumersStatus = blobConsumers.start().get();
        assertEquals(NB_QUEUE, blobConsumersStatus.size());
        assertEquals(NB_PRODUCERS * NB_BLOBS, blobConsumersStatus.stream().mapToLong(r -> r.committed).sum());

        getLogManager().createIfNotExists(LOG_DOC, NB_QUEUE);
        // 3. generate documents using blob reference
        ProducerFactory<DocumentMessage> randomDocFactory = new RandomDocumentMessageProducerFactory(NB_DOCUMENTS,
                "en_US", getLogManager(), LOG_BLOB_INFO.getUrn());
        ProducerPool<DocumentMessage> docProducers = new ProducerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                randomDocFactory, NB_PRODUCERS);
        List<ProducerStatus> docProducersStatus = docProducers.start().get();
        assertEquals(NB_PRODUCERS, docProducersStatus.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, docProducersStatus.stream().mapToLong(r -> r.nbProcessed).sum());

        // 4. import documents without creating blobs
        DocumentModel root = session.getRootDocument();
        ConsumerFactory<DocumentMessage> docFactory = new DocumentMessageConsumerFactory(root.getRepositoryName(),
                root.getPathAsString());
        ConsumerPool<DocumentMessage> docConsumers = new ConsumerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                docFactory, ConsumerPolicy.BOUNDED);
        List<ConsumerStatus> docConsumersStatus = docConsumers.start().get();
        assertEquals(NB_QUEUE, docConsumersStatus.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, docConsumersStatus.stream().mapToLong(r -> r.committed).sum());

    }

    @Test
    public void fourStepsImportFileBlob() throws Exception {
        final int NB_QUEUE = 2;
        final short NB_PRODUCERS = 2;
        final long NB_DOCUMENTS = 100;
        final long NB_BLOBS = 100;
        final Name LOG_BLOB = Name.ofUrn("import/fourStepsFileBlob-blob");
        final Name LOG_BLOB_INFO = Name.ofUrn("import/fourStepsFileBlob-blobInfo");
        final Name LOG_DOC = Name.ofUrn("import/fourStepsFileBlob-doc");
        streamsToClean.add(LOG_BLOB);
        streamsToClean.add(LOG_BLOB_INFO);
        streamsToClean.add(LOG_DOC);

        Codec<BlobMessage> blobCodec = StreamImporters.getBlobCodec();
        Codec<BlobInfoMessage> blobInfoCodec = StreamImporters.getBlobInfoCodec();
        Codec<DocumentMessage> docCodec = StreamImporters.getDocCodec();
        assertTrue(getLogManager().createIfNotExists(LOG_BLOB, NB_QUEUE));
        // 1. generates blobs from files
        ProducerPool<BlobMessage> blobProducers = new ProducerPool<>(LOG_BLOB.getUrn(), getLogManager(), blobCodec,
                new FileBlobMessageProducerFactory(getFileList("files/list.txt"), getBasePathList("files"), NB_BLOBS),
                NB_PRODUCERS);
        List<ProducerStatus> blobProducersStatus = blobProducers.start().get();
        assertEquals(NB_PRODUCERS, blobProducersStatus.size());
        // assertEquals(NB_PRODUCERS * NB_BLOBS, blobProducersStatus.stream().mapToLong(r -> r.nbProcessed).sum());

        // 2. import blobs
        String blobProviderName = "test";
        assertTrue(getLogManager().createIfNotExists(LOG_BLOB_INFO, 1));
        BlobInfoWriter blobInfoWriter = new LogBlobInfoWriter(
                getLogManager().getAppender(LOG_BLOB_INFO, blobInfoCodec));
        ConsumerFactory<BlobMessage> blobFactory = new BlobMessageConsumerFactory(blobProviderName, blobInfoWriter,
                "foobar");
        ConsumerPool<BlobMessage> blobConsumers = new ConsumerPool<>(LOG_BLOB.getUrn(), getLogManager(), blobCodec,
                blobFactory, ConsumerPolicy.BOUNDED);
        List<ConsumerStatus> blobConsumersStatus = blobConsumers.start().get();
        assertEquals(NB_QUEUE, blobConsumersStatus.size());
        // assertEquals(NB_PRODUCERS * NB_BLOBS, blobConsumersStatus.stream().mapToLong(r -> r.committed).sum());

        getLogManager().createIfNotExists(LOG_DOC, NB_QUEUE);
        // 3. generate documents using blob reference
        ProducerFactory<DocumentMessage> randomDocFactory = new RandomDocumentMessageProducerFactory(NB_DOCUMENTS,
                "en_US", getLogManager(), LOG_BLOB_INFO.getUrn());
        ProducerPool<DocumentMessage> docProducers = new ProducerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                randomDocFactory, NB_PRODUCERS);
        List<ProducerStatus> docProducersStatus = docProducers.start().get();
        assertEquals(NB_PRODUCERS, docProducersStatus.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, docProducersStatus.stream().mapToLong(r -> r.nbProcessed).sum());

        // 4. import documents without creating blobs
        DocumentModel root = session.getRootDocument();
        ConsumerFactory<DocumentMessage> docFactory = new DocumentMessageConsumerFactory(root.getRepositoryName(),
                root.getPathAsString());
        ConsumerPool<DocumentMessage> docConsumers = new ConsumerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                docFactory, ConsumerPolicy.BOUNDED);
        List<ConsumerStatus> docConsumersStatus = docConsumers.start().get();
        assertEquals(NB_QUEUE, docConsumersStatus.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, docConsumersStatus.stream().mapToLong(r -> r.committed).sum());

        WorkManager service = Framework.getService(WorkManager.class);
        assertTrue(service.awaitCompletion(10, TimeUnit.SECONDS));

        // make sure there is no visibility pb with mysql
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        DocumentModelList docs = session.query("SELECT * FROM Document");
        assertEquals(NB_DOCUMENTS * NB_PRODUCERS, docs.totalSize());

        docs = session.query("SELECT * FROM File");
        long nbFiles = docs.totalSize();
        assertFalse(docs.isEmpty());

        docs = session.query("SELECT * FROM Document WHERE content/name LIKE '%.txt'");
        assertFalse(docs.isEmpty());
        long nbText = docs.totalSize();

        docs = session.query("SELECT * FROM Document WHERE content/name LIKE '%.jpg'");
        assertFalse(docs.isEmpty());
        long nbPicture = docs.totalSize();

        docs = session.query("SELECT * FROM Document WHERE content/name LIKE '%.mp4'");
        assertFalse(docs.isEmpty());
        long nbVideo = docs.totalSize();

        assertEquals(nbFiles, nbPicture + nbText + nbVideo);

        if (coreFeature.getStorageConfiguration().supportsFulltextSearch()) {
            docs = session.query("SELECT * FROM Document WHERE ecm:fulltext='youknowforsearchtag'");
            assertFalse(docs.isEmpty());

            docs = session.query("SELECT * FROM Document WHERE ecm:fulltext='foobar'");
            assertEquals(nbText, docs.totalSize());
        }
    }

    @Ignore("Only to work on perf")
    @Test
    public void docGenerationPerf() throws Exception {
        final int NB_QUEUE = 1;
        final short NB_PRODUCERS = 1;
        final int NB_DOCUMENTS = 1_000_000;
        final Name LOG_DOC = Name.ofUrn("import/docGenerationPerf");
        streamsToClean.add(LOG_DOC);

        Codec<DocumentMessage> docCodec = StreamImporters.getDocCodec();
        // 1. generate documents with blobs
        getLogManager().createIfNotExists(LOG_DOC, NB_QUEUE);
        ProducerPool<DocumentMessage> producers = new ProducerPool<>(LOG_DOC.getUrn(), getLogManager(), docCodec,
                new RandomDocumentMessageProducerFactory(NB_DOCUMENTS, "en_US", 2), NB_PRODUCERS);
        List<ProducerStatus> ret = producers.start().get();
        assertEquals(NB_PRODUCERS, ret.size());
        assertEquals(NB_PRODUCERS * NB_DOCUMENTS, ret.stream().mapToLong(r -> r.nbProcessed).sum());
    }

    protected File getFileList(String filename) {
        return new File(this.getClass().getClassLoader().getResource(filename).getPath());
    }

    protected String getBasePathList(String base) {
        return this.getClass().getClassLoader().getResource(base).getPath();
    }
}
