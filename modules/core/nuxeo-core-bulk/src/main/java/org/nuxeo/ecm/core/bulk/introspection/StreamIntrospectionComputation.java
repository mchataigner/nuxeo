/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
package org.nuxeo.ecm.core.bulk.introspection;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;
import org.nuxeo.lib.stream.computation.AbstractComputation;
import org.nuxeo.lib.stream.computation.ComputationContext;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.metrics.MetricsService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.dropwizard.metrics5.Gauge;
import io.dropwizard.metrics5.MetricName;
import io.dropwizard.metrics5.MetricRegistry;
import io.dropwizard.metrics5.SharedMetricRegistries;

/**
 * A computation that reads processor and metrics streams to build a representation of stream activities in the cluster.
 * The representation is pushed to the KV Store.
 *
 * @since 11.5
 */
public class StreamIntrospectionComputation extends AbstractComputation {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(
            StreamIntrospectionComputation.class);

    public static final String NAME = "stream/introspection";

    public static final String INTROSPECTION_KV_STORE = "introspection";

    public static final String INTROSPECTION_KEY = "streamIntrospection";

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected final Map<String, JsonNode> streams = new HashMap<>();

    protected final Map<String, JsonNode> processors = new HashMap<>();

    protected final Map<String, JsonNode> metrics = new HashMap<>();

    protected static final long TTL_SECONDS = 300;

    protected String model;

    protected final MetricRegistry registry = SharedMetricRegistries.getOrCreate(MetricsService.class.getName());

    protected int scaleMetric = 0;

    protected int currentWorkerNodes = 1;

    public StreamIntrospectionComputation() {
        super(NAME, 2, 0);
    }

    @Override
    public void init(ComputationContext context) {
        MetricName scaleMetric = MetricName.build("nuxeo", "streams", "scale", "metric");
        MetricName workerMetric = MetricName.build("nuxeo", "cluster", "worker", "count");
        registry.remove(scaleMetric);
        registry.remove(workerMetric);
        if (context.isSpareComputation()) {
            log.info("Spare instance nothing to report");
            return;
        }
        log.warn("Instance elected to introspect Nuxeo Stream activity");
        loadModel(getKvStore().getString(INTROSPECTION_KEY));
        registry.register(scaleMetric, (Gauge<Integer>) this::getScaleMetric);
        registry.register(workerMetric, (Gauge<Integer>) this::getCurrentWorkerNodes);
    }

    protected int getCurrentWorkerNodes() {
        return currentWorkerNodes;
    }

    protected int getScaleMetric() {
        return scaleMetric;
    }

    protected void loadModel(String modelJson) {
        streams.clear();
        processors.clear();
        metrics.clear();
        if (isBlank(modelJson)) {
            model = null;
            return;
        }
        try {
            JsonNode modelNode = OBJECT_MAPPER.readTree(modelJson);
            JsonNode node = modelNode.get("streams");
            if (node.isArray()) {
                for (JsonNode item : node) {
                    streams.put(item.get("name").asText(), item);
                }
            }
            node = modelNode.get("processors");
            if (node.isArray()) {
                for (JsonNode item : node) {
                    processors.put(getProcessorKey(item), item);
                }
            }
            node = modelNode.get("metrics");
            if (node.isArray()) {
                for (JsonNode item : node) {
                    metrics.put(item.get("nodeId").asText(), item);
                }
            }
            model = modelJson;
        } catch (JsonProcessingException e) {
            log.error("Unable to parse KV model as JSON {}", modelJson, e);
            model = null;
        }
    }

    @Override
    public void processRecord(ComputationContext context, String inputStreamName, Record record) {
        JsonNode json = getJson(record);
        if (json != null) {
            if (INPUT_1.equals(inputStreamName)) {
                updateStreamsAndProcessors(json);
            } else if (INPUT_2.equals(inputStreamName)) {
                if (json.has("nodeId")) {
                    metrics.put(json.get("nodeId").asText(), json);
                }
            }
        }
        removeOldNodes();
        buildModel();
        updateModel();
        context.askForCheckpoint();
    }

    protected void updateStreamsAndProcessors(JsonNode node) {
        JsonNode streamsNode = node.get("streams");
        if (streamsNode == null) {
            log.warn("Invalid metric without streams field: {}", node);
            return;
        }
        if (streamsNode.isArray()) {
            for (JsonNode item : streamsNode) {
                streams.put(item.get("name").asText(), item);
            }
        }
        ((ObjectNode) node).remove("streams");
        processors.put(getProcessorKey(node), node);
    }

    protected String getProcessorKey(JsonNode json) {
        return json.at("/metadata/nodeId").asText() + ":" + json.at("/metadata/processorName").asText();
    }

    protected void updateModel() {
        KeyValueStore kv = getKvStore();
        kv.put(INTROSPECTION_KEY, model);
        StreamIntrospectionConverter convert = new StreamIntrospectionConverter(model);
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode activity = mapper.readTree(convert.getActivity());
            scaleMetric = activity.at("/scale/metric").asInt();
            currentWorkerNodes = activity.at("/scale/currentNodes").asInt();
            log.trace("Scale metric: {}, worker nodes: {}, activity: {}", scaleMetric, currentWorkerNodes, activity);
        } catch (JsonProcessingException e) {
            log.warn("Invalid json model: {}", e::getMessage);
        }
    }

    protected KeyValueStore getKvStore() {
        return Framework.getService(KeyValueService.class).getKeyValueStore(INTROSPECTION_KV_STORE);
    }

    protected void buildModel() {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        ArrayNode streamsNode = OBJECT_MAPPER.createArrayNode();
        streamsNode.addAll(streams.values());
        node.set("streams", streamsNode);
        ArrayNode processorsNode = OBJECT_MAPPER.createArrayNode();
        processorsNode.addAll(processors.values());
        node.set("processors", processorsNode);
        ArrayNode metricsNode = OBJECT_MAPPER.createArrayNode();
        metricsNode.addAll(metrics.values());
        node.set("metrics", metricsNode);
        try {
            model = OBJECT_MAPPER.writer().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Cannot build JSON model", e);
            model = "{}";
        }
    }

    protected void removeOldNodes() {
        // Remove all nodes with metrics older than TTL
        long now = System.currentTimeMillis() / 1000;
        List<String> toRemove = metrics.values()
                                       .stream()
                                       .filter(json -> (now - json.get("timestamp").asLong()) > TTL_SECONDS)
                                       .map(json -> json.get("nodeId").asText())
                                       .collect(Collectors.toList());
        log.debug("Removing nodes with old metrics: {}", toRemove);
        toRemove.forEach(metrics::remove);
        Set<String> toKeep = metrics.values().stream().map(json -> json.get("nodeId").asText()).collect(Collectors.toSet());
        log.debug("List of active nodes: {}", toKeep);
        // Remove processors with inactive nodes and older than TTL
        Iterator<Map.Entry<String, JsonNode>> entries = processors.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode node = entry.getValue();
            String nodeId = node.at("/metadata/nodeId").asText();
            if (!toKeep.contains(nodeId)) {
                JsonNode created = node.at("/metadata/created");
                if (created == null || ((now - created.asLong()) > TTL_SECONDS)) {
                    entries.remove();
                }
            }
        }
    }

    protected JsonNode getJson(Record record) {
        String json = new String(record.getData(), UTF_8);
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("Invalid JSON from record {}: {}", record, json, e);
            return null;
        }
    }
}
