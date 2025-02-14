/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Mariana Cedica
 */
package org.nuxeo.ecm.platform.routing.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.routing.core.api.DocumentRoutingEscalationService.SUSPENDED_NODES_WITH_ESCALATION_QUERY;
import static org.nuxeo.ecm.platform.routing.core.listener.DocumentRoutingEscalationListener.EXECUTE_ESCALATION_RULE_EVENT;
import static org.nuxeo.ecm.platform.routing.core.listener.DocumentRoutingEscalationListener.USE_LEGACY_CONF_KEY;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.EventContextImpl;
import org.nuxeo.ecm.platform.routing.api.DocumentRoute;
import org.nuxeo.ecm.platform.routing.api.DocumentRoutingService;
import org.nuxeo.ecm.platform.routing.core.api.DocumentRoutingEngineService;
import org.nuxeo.ecm.platform.routing.core.api.DocumentRoutingEscalationService;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode.EscalationRule;
import org.nuxeo.ecm.platform.routing.core.impl.GraphRoute;
import org.nuxeo.ecm.platform.routing.core.listener.DocumentRoutingEscalationListener;
import org.nuxeo.ecm.platform.task.Task;
import org.nuxeo.ecm.platform.task.TaskService;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * @since 5.7.2
 */
@RunWith(FeaturesRunner.class)
@Features({ WorkflowFeature.class, LogCaptureFeature.class })
public class WorkflowEscalationTest extends AbstractGraphRouteTest {

    @Inject
    protected CoreSession session;

    @Inject
    protected DocumentRoutingService routing;

    @Inject
    protected DocumentRoutingEngineService routingService;

    // init userManager now for early user tables creation (cleaner debug)
    @Inject
    protected UserManager userManager;

    @Inject
    protected TaskService taskService;

    @Inject
    protected DocumentRoutingEscalationService escalationService;

    @Inject
    protected EventService eventService;

    @Inject
    protected TransactionalFeature transactionalFeature;

    @Inject
    protected LogCaptureFeature.Result logCaptureResults;

    @Before
    public void setUp() {
        assertNotNull(routing);
        routing.invalidateRouteModelsCache();
        doc = session.createDocumentModel("/", "file", "File");
        doc.setPropertyValue("dc:title", "file");
        doc = session.createDocument(doc);
        routeDoc = createRoute("myroute", session);
    }

    /**
     * @since 7.4
     */
    // NXP-17239
    @Test
    @SuppressWarnings("unchecked")
    public void testEscalationDeleteTask() {
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node2", "true", "testchain_title1"));
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_DUE_DATE_EXPR, "CurrentDate.days(-1)");
        setEscalationRules(node1,
                escalationRule("rule1", "WorkflowFn.timeSinceDueDateIsOver() >=3600000", "test_resumeWf", false));
        session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);
        DocumentRoute routeInstance = instantiateAndRun(session);
        String routeInstanceId = routeInstance.getDocument().getId();

        transactionalFeature.nextTransaction();

        Task taskToBeRemoved = routing.getTasks(doc, null, routeInstanceId, null, session).get(0);
        session.removeDocument(taskToBeRemoved.getDocument().getRef());
        session.save();
        transactionalFeature.nextTransaction();

        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());
        DocumentModel nodeDoc = nodes.get(0);
        GraphNode node = nodeDoc.getAdapter(GraphNode.class);
        assertEquals("node1", node.getId());
        List<GraphNode.EscalationRule> rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(1, rules.size());
        escalationService.executeEscalationRule(rules.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEscalationSingleExecution() {
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "NodeVariables[\"button\"] == \"trans1\"", "testchain_title1"));
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_DUE_DATE_EXPR, "CurrentDate.days(-1)");
        setEscalationRules(node1,
                escalationRule("rule1", "WorkflowFn.timeSinceDueDateIsOver() >=3600000", "testchain_title1", false));
        session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);
        DocumentRoute routeInstance = instantiateAndRun(session);
        String routeInstanceId = routeInstance.getDocument().getId();

        transactionalFeature.nextTransaction();

        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());
        DocumentModel nodeDoc = nodes.get(0);
        GraphNode node = nodeDoc.getAdapter(GraphNode.class);
        assertEquals("node1", node.getId());
        List<GraphNode.EscalationRule> rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(1, rules.size());
        escalationService.executeEscalationRule(rules.get(0));

        transactionalFeature.nextTransaction();

        // fetch node doc to check that the rule is marked as executed
        nodeDoc = session.getDocument(nodeDoc.getRef());
        node = nodeDoc.getAdapter(GraphNode.class);
        assertTrue(node.getEscalationRules().get(0).isExecuted());
        // check that the rule was executed
        doc = session.getDocument(doc.getRef());
        assertEquals("title 1", doc.getTitle());

        // check that no nodes with execution rules are found
        nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(0, nodes.size());

        // cancel the route
        routingService.cancel(routeInstance, session);
        routeInstance = session.getDocument(new IdRef(routeInstanceId)).getAdapter(DocumentRoute.class);
        assertTrue(routeInstance.isCanceled());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEscalationMultipleExecution() {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);
        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET, "FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "NodeVariables[\"button\"] == \"trans1\"", "testchain_title1"));

        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        setEscalationRules(node1,
                escalationRule("rule1",
                        "( (WorkflowFn.ruleAlreadyExecuted() && WorkflowFn.timeSinceRuleHasBeenFalse() >0 ) ||"
                                + " !WorkflowFn.ruleAlreadyExecuted()) && WorkflowFn.timeSinceTaskWasStarted() >=0",
                        "testchain_title1", true),
                escalationRule("rule2", "true", "testchain_title2", false),
                escalationRule("rule3", "true", "testchain_stringfield", false),
                escalationRule("rule4", "true", "testchain_stringfield2", false));
        String[] users = { user1.getName() };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node1, button("btn1", "label-btn1", "filterr", null));
        session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);
        DocumentRoute route = instantiateAndRun(session);
        String routeInstanceId = route.getDocument().getId();

        transactionalFeature.nextTransaction();
        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());

        // execute rule1
        DocumentModel nodeDoc = nodes.get(0);
        GraphNode node = nodeDoc.getAdapter(GraphNode.class);
        assertEquals("node1", node.getId());
        List<EscalationRule> rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(4, rules.size());
        escalationService.executeEscalationRule(rules.get(0));

        transactionalFeature.nextTransaction();
        // check that the rule was executed
        doc = session.getDocument(doc.getRef());
        assertEquals("title 1", doc.getTitle());

        // check that are still 4 rules, since rule1 is marked for
        // multipleExecution
        nodeDoc = session.getDocument(new IdRef(node.getDocument().getId()));
        node = nodeDoc.getAdapter(GraphNode.class);
        rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(4, rules.size());

        // execute rule2
        escalationService.executeEscalationRule(rules.get(1));

        transactionalFeature.nextTransaction();
        // check that the rule was executed
        doc = session.getDocument(doc.getRef());
        assertEquals("title 2", doc.getTitle());

        // check that only 3 rules are found now
        nodeDoc = session.getDocument(new IdRef(node.getDocument().getId()));
        node = nodeDoc.getAdapter(GraphNode.class);
        rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(3, rules.size());

        // execute rule3 & rule4
        escalationService.executeEscalationRule(rules.get(1));
        escalationService.executeEscalationRule(rules.get(2));

        transactionalFeature.nextTransaction();
        // check that the rules were executed
        DocumentModel r = session.getDocument(route.getDocument().getRef());
        nodeDoc = session.getDocument(new IdRef(node.getDocument().getId()));
        assertEquals("foo", r.getPropertyValue("fctroute1:stringfield"));
        assertEquals("bar", nodeDoc.getPropertyValue("fctnd1:stringfield2"));
        // cancel the route
        routingService.cancel(route, session);
        DocumentRoute routeInstance = session.getDocument(new IdRef(routeInstanceId)).getAdapter(DocumentRoute.class);
        assertTrue(routeInstance.isCanceled());
    }

    /**
     * @deprecated since 2023.0
     */
    // NXP-31616
    @Test
    @Deprecated
    @WithFrameworkProperty(name = USE_LEGACY_CONF_KEY, value = "true")
    public void testEscalationMultipleExecutionWithListenerLegacy() {
        testEscalationMultipleExecutionWithListener();
    }

    // NXP-31616
    @Test
    @SuppressWarnings("unchecked")
    public void testEscalationMultipleExecutionWithListener() {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);
        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET, "FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "NodeVariables[\"button\"] == \"trans1\"", "testchain_title1"));

        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        setEscalationRules(node1,
                escalationRule("rule1",
                        "( (WorkflowFn.ruleAlreadyExecuted() && WorkflowFn.timeSinceRuleHasBeenFalse() >0 ) ||"
                                + " !WorkflowFn.ruleAlreadyExecuted()) && WorkflowFn.timeSinceTaskWasStarted() >=0",
                        "testchain_title1", true),
                escalationRule("rule2", "true", "testchain_title2", false),
                escalationRule("rule3", "true", "testchain_stringfield", false),
                escalationRule("rule4", "true", "testchain_stringfield2", false));
        String[] users = { user1.getName() };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node1, button("btn1", "label-btn1", "filterr", null));
        session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);
        DocumentRoute route = instantiateAndRun(session);
        String routeInstanceId = route.getDocument().getId();

        transactionalFeature.nextTransaction();
        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());
        DocumentModel nodeDoc = nodes.get(0);

        eventService.fireEvent(new EventContextImpl().newEvent(EXECUTE_ESCALATION_RULE_EVENT));

        transactionalFeature.nextTransaction();
        // check that the rules were executed
        doc = session.getDocument(doc.getRef());
        if ("true".equals(Framework.getProperty(USE_LEGACY_CONF_KEY))) {
            // as rule execution is done asynchronously, we may have rule1 executed after rule2
            assertTrue(Set.of("title 1", "title 2").contains(doc.getTitle()));
        } else {
            // rule execution is done sequentially, rule2 is always executed after rule1
            assertEquals("title 2", doc.getTitle());
        }
        DocumentModel r = session.getDocument(route.getDocument().getRef());
        nodeDoc = session.getDocument(nodeDoc.getRef());
        assertEquals("foo", r.getPropertyValue("fctroute1:stringfield"));
        assertEquals("bar", nodeDoc.getPropertyValue("fctnd1:stringfield2"));
        // cancel the route
        routingService.cancel(route, session);
        DocumentRoute routeInstance = session.getDocument(new IdRef(routeInstanceId)).getAdapter(DocumentRoute.class);
        assertTrue(routeInstance.isCanceled());
    }

    // NXP-31616
    @Test
    @SuppressWarnings("unchecked")
    @Deploy("org.nuxeo.ecm.platform.routing.core.test:OSGI-INF/test-document-routing-escalation-long-running-flag-ttl-contrib.xml")
    @LogCaptureFeature.FilterOn(loggerClass = DocumentRoutingEscalationListener.class, logLevel = "WARN")
    public void testEscalationListenerDoesntTriggerBulkActionIfRunningFlagPresent() {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);
        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET, "FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "NodeVariables[\"button\"] == \"trans1\"", "testchain_title1"));

        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        setEscalationRules(node1,
                escalationRule("rule1",
                        "( (WorkflowFn.ruleAlreadyExecuted() && WorkflowFn.timeSinceRuleHasBeenFalse() >0 ) ||"
                                + " !WorkflowFn.ruleAlreadyExecuted()) && WorkflowFn.timeSinceTaskWasStarted() >=0",
                        "testchain_title1", true),
                escalationRule("rule2", "true", "testchain_title2", false),
                escalationRule("rule3", "true", "testchain_stringfield", false),
                escalationRule("rule4", "true", "testchain_stringfield2", false));
        String[] users = { user1.getName() };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node1, button("btn1", "label-btn1", "filterr", null));
        session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);
        DocumentRoute route = instantiateAndRun(session);

        transactionalFeature.nextTransaction();
        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());
        DocumentModel nodeDoc = nodes.get(0);

        // this will trigger a Bulk Action
        eventService.fireEvent(new EventContextImpl().newEvent(EXECUTE_ESCALATION_RULE_EVENT));
        assertTrue(logCaptureResults.getCaughtEventMessages().isEmpty());

        // wait the Bulk Action to start & finish
        transactionalFeature.nextTransaction();

        // this won't trigger a Bulk Action as the running ttl is set to 1 day
        eventService.fireEvent(new EventContextImpl().newEvent(EXECUTE_ESCALATION_RULE_EVENT));
        assertEquals(1, logCaptureResults.getCaughtEventMessages().size());
        var message = logCaptureResults.getCaughtEventMessages().get(0);
        assertEquals("Not scheduling Workflow Escalation execution on repository: test because one is already running",
                message);
    }

    /**
     * Non regression test for NXP-28078.
     *
     * @since 11.1
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testGetOpenTasksOperation() {
        // define a start node with an escalation rule
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node2"));
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_DUE_DATE_EXPR, "CurrentDate.days(-1)");
        setEscalationRules(node1, escalationRule("rule1", "WorkflowFn.timeSinceDueDateIsOver() >=3600000",
                "test_completeOpenTasks", false));
        session.saveDocument(node1);

        // define an end node
        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        session.saveDocument(node2);

        // start the workflow
        DocumentRoute routeInstance = instantiateAndRun(session);
        String routeInstanceId = routeInstance.getDocument().getId();

        transactionalFeature.nextTransaction();

        // check suspended nodes with escalation rules
        DocumentModelList nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(1, nodes.size());
        DocumentModel nodeDoc = nodes.get(0);
        GraphNode node = nodeDoc.getAdapter(GraphNode.class);
        assertEquals("node1", node.getId());

        // compute and schedule escalation rule
        List<GraphNode.EscalationRule> rules = escalationService.computeEscalationRulesToExecute(node);
        assertEquals(1, rules.size());
        escalationService.executeEscalationRule(rules.get(0));

        transactionalFeature.nextTransaction();

        // fetch node doc to check that the escalation rule is marked as executed
        nodeDoc = session.getDocument(nodeDoc.getRef());
        node = nodeDoc.getAdapter(GraphNode.class);
        assertTrue(node.getEscalationRules().get(0).isExecuted());

        // check that the rule was executed
        assertEquals(0, taskService.getAllTaskInstances(routeInstanceId, nodeDoc.getId(), session).size());

        // check that no nodes with escalation rules are found
        nodes = session.query(SUSPENDED_NODES_WITH_ESCALATION_QUERY);
        assertEquals(0, nodes.size());

        // cancel the route
        routingService.cancel(routeInstance, session);
        routeInstance = session.getDocument(new IdRef(routeInstanceId)).getAdapter(DocumentRoute.class);
        assertTrue(routeInstance.isCanceled());
    }

    @SuppressWarnings("unchecked")
    protected void setEscalationRules(DocumentModel node, Map<String, Serializable>... rules) {
        node.setPropertyValue(GraphNode.PROP_ESCALATION_RULES, (Serializable) List.of(rules));
    }

    protected Map<String, Serializable> escalationRule(String id, String condition, String chain,
            boolean multipleExecution) {
        Map<String, Serializable> m = new HashMap<>();
        m.put(GraphNode.PROP_ESCALATION_RULE_ID, id);
        m.put(GraphNode.PROP_ESCALATION_RULE_CONDITION, condition);
        m.put(GraphNode.PROP_ESCALATION_RULE_CHAIN, chain);
        m.put(GraphNode.PROP_ESCALATION_RULE_MULTIPLE_EXECUTION, multipleExecution);
        return m;
    }
}
