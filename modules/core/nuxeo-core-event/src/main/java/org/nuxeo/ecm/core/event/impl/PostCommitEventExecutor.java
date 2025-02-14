/*
 * (C) Copyright 2006-2013 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Thierry Delprat
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.event.impl;

import static org.nuxeo.common.concurrent.ThreadFactories.newThreadFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.EventStats;
import org.nuxeo.ecm.core.event.ReconnectedEventBundle;
import org.nuxeo.lib.stream.Log4jCorrelation;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

import io.opencensus.common.Scope;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.BlankSpan;
import io.opencensus.trace.Link;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.propagation.BinaryFormat;
import io.opencensus.trace.propagation.SpanContextParseException;

/**
 * Executor that passes an event bundle to post-commit asynchronous listeners (in a separated thread in order to manage
 * transactions).
 * <p>
 * Allows a bulk mode where transaction management is not per-listener done once for the whole set of listeners.
 */
public class PostCommitEventExecutor {

    private static final Logger log = LogManager.getLogger(PostCommitEventExecutor.class);

    public static final String TIMEOUT_MS_PROP = "org.nuxeo.ecm.core.event.tx.PostCommitExecutor.timeoutMs";

    public static final int DEFAULT_TIMEOUT_MS = 300; // 0.3s

    public static final int DEFAULT_TIMEOUT_TEST_MS = 60000; // 1 min

    private Integer defaultTimeoutMs;

    public static final String DEFAULT_BULK_TIMEOUT_S = "600"; // 10min

    public static final String BULK_TIMEOUT_PROP = "org.nuxeo.ecm.core.event.tx.BulkExecutor.timeout";

    private static final long KEEP_ALIVE_TIME_SECOND = 10;

    private static final int MAX_POOL_SIZE = 100;

    protected final ExecutorService executor;

    public PostCommitEventExecutor() {
        // use as much thread as needed up to MAX_POOL_SIZE
        // keep them alive a moment for reuse
        // have all threads torn down when there is no work to do
        ThreadFactory threadFactory = newThreadFactory("Nuxeo-Event-PostCommit");
        executor = new ThreadPoolExecutor(0, MAX_POOL_SIZE, KEEP_ALIVE_TIME_SECOND, TimeUnit.SECONDS,
                new SynchronousQueue<>(), threadFactory);
        ((ThreadPoolExecutor) executor).allowCoreThreadTimeOut(true);
    }

    protected int getDefaultTimeoutMs() {
        if (defaultTimeoutMs == null) {
            if (Framework.getProperty(TIMEOUT_MS_PROP) != null) {
                defaultTimeoutMs = Integer.parseInt(Framework.getProperty(TIMEOUT_MS_PROP));
            } else if (Framework.isTestModeSet()) {
                defaultTimeoutMs = DEFAULT_TIMEOUT_TEST_MS;
            } else {
                defaultTimeoutMs = DEFAULT_TIMEOUT_MS;
            }
        }
        return defaultTimeoutMs;
    }

    public void shutdown(long timeoutMillis) throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!executor.isTerminated()) {
            executor.shutdownNow();
        }
    }

    public void run(List<EventListenerDescriptor> listeners, EventBundle event) {
        log.warn(
                "Running post commit event listeners: {}. Post commit event listener execution will soon be deprecated,"
                        + " see NXP-27986. As explained in NXP-26911, please update your post commit event listener"
                        + " contributions to make the listeners asynchronous with <listener async=\"true\"...>.\n"
                        + " You can disable this warning by following the instructions provided in NXP-26911.",
                listeners);
        run(listeners, event, getDefaultTimeoutMs(), false);
    }

    public void runBulk(List<EventListenerDescriptor> listeners, EventBundle event) {
        String timeoutSeconds = Framework.getProperty(BULK_TIMEOUT_PROP, DEFAULT_BULK_TIMEOUT_S);
        run(listeners, event, Long.parseLong(timeoutSeconds) * 1000, true);
    }

    public void run(List<EventListenerDescriptor> listeners, EventBundle bundle, long timeoutMillis, boolean bulk) {
        // check that there's at list one listener interested
        boolean some = false;
        for (EventListenerDescriptor listener : listeners) {
            if (listener.acceptBundle(bundle)) {
                some = true;
                break;
            }
        }
        if (!some) {
            log.debug("Events postcommit execution has nothing to do");
            return;
        }

        log.debug("Events postcommit execution starting with timeout {}ms{}", () -> Long.valueOf(timeoutMillis),
                () -> bulk ? " in bulk mode" : "");

        Callable<Boolean> callable = !bulk ? new EventBundleRunner(listeners, bundle)
                : new EventBundleBulkRunner(listeners, bundle);
        FutureTask<Boolean> futureTask = new FutureTask<>(callable);
        try {
            executor.execute(futureTask);
        } catch (RejectedExecutionException e) {
            log.error("Events postcommit execution rejected", e);
            return;
        }
        try {
            // wait for runner to be finished, with timeout
            Boolean ok = futureTask.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (Boolean.FALSE.equals(ok)) {
                log.error("Events postcommit bulk execution aborted due to previous error");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // interrupt thread
            futureTask.cancel(true); // mayInterruptIfRunning=true
        } catch (TimeoutException e) {
            if (!bulk) {
                log.info("Events postcommit execution exceeded timeout of {}ms, leaving thread running",
                        () -> Long.valueOf(timeoutMillis));
                // don't cancel task, let it run
            } else {
                log.error("Events postcommit bulk execution exceeded timeout of {}ms, interrupting thread",
                        () -> Long.valueOf(timeoutMillis));
                futureTask.cancel(true); // mayInterruptIfRunning=true
            }
        } catch (ExecutionException e) {
            log.error("Events postcommit execution encountered unexpected exception", e::getCause);
        }

        log.debug("Events postcommit execution finished");
    }

    /**
     * Lets the listeners process the event bundle.
     * <p>
     * For each listener, the event bundle is reconnected to a session and a transaction is started.
     * <p>
     * In case of exception in a listener, the transaction is rolled back for that listener but processing continues for
     * the other listeners.
     * <p>
     * In case of timeout, an error is logged but processing continues for the other listeners (the thread is left
     * running separately from the main thread that initiated post-commit processing).
     */
    protected static class EventBundleRunner implements Callable<Boolean> {

        protected final List<EventListenerDescriptor> listeners;

        protected final EventBundle bundle;

        protected final byte[] traceContext;

        protected String callerThread;

        public EventBundleRunner(List<EventListenerDescriptor> listeners, EventBundle bundle) {
            this.listeners = listeners;
            this.bundle = bundle;
            callerThread = Thread.currentThread().getName();
            traceContext = Tracing.getPropagationComponent()
                                  .getBinaryFormat()
                                  .toByteArray(Tracing.getTracer().getCurrentSpan().getContext());
        }

        @Override
        public Boolean call() {
            log.debug("Events postcommit execution starting in thread: {}", () -> Thread.currentThread().getName());
            long t0 = System.currentTimeMillis();
            EventStats stats = Framework.getService(EventStats.class);
            Span span = getTracingSpan("postcommit/EventBundleBulkRunner");
            try (Scope scope = Tracing.getTracer().withSpan(span)) {
                Log4jCorrelation.start(span);
                for (EventListenerDescriptor listener : listeners) {
                    EventBundle filtered = listener.filterBundle(bundle);
                    if (filtered.isEmpty()) {
                        continue;
                    }
                    log.debug("Events postcommit execution start for listener: {}", listener::getName);
                    long t1 = System.currentTimeMillis();

                    boolean ok = false;
                    ReconnectedEventBundle reconnected = null;
                    // transaction timeout is managed by the FutureTask
                    boolean tx = TransactionHelper.startTransaction();
                    try {
                        reconnected = new ReconnectedEventBundleImpl(filtered, listeners.toString());

                        listener.asPostCommitListener().handleEvent(reconnected);

                        ok = true;
                        // don't check for interrupted flag, the event completed normally, no reason to rollback
                    } catch (RuntimeException e) {
                        log.error("Events postcommit execution encountered exception for listener: {}",
                                listener::getName, () -> e);
                        // don't rethrow, but rollback (ok=false) and continue loop
                        span.setStatus(Status.UNKNOWN);
                    } finally {
                        try {
                            if (reconnected != null) {
                                reconnected.disconnect();
                            }
                        } finally {
                            if (tx) {
                                if (!ok) {
                                    TransactionHelper.setTransactionRollbackOnly();
                                    log.error("Rolling back transaction");
                                }
                                TransactionHelper.commitOrRollbackTransaction();
                            }
                            long elapsed = System.currentTimeMillis() - t1;
                            if (stats != null) {
                                stats.logAsyncExec(listener, elapsed);
                            }
                            log.debug("Events postcommit execution end for listener: {} in {}ms", listener::getName,
                                    () -> elapsed);
                            span.addAnnotation("PostCommitEventExecutor#Listener " + listener.getName() + " " + elapsed + " ms");
                        }
                    }
                    // even if interrupted due to timeout, we continue the loop
                }
                span.setStatus(Status.OK);
            } finally {
                span.end();
                Log4jCorrelation.end();
            }

            long elapsed = System.currentTimeMillis() - t0;
            log.debug("Events postcommit execution finished in {}ms", elapsed);
            return Boolean.TRUE; // no error to report
        }

        protected Span getTracingSpan(String spanName) {
            if (traceContext == null) {
                return BlankSpan.INSTANCE;
            }
            Tracer tracer = Tracing.getTracer();
            BinaryFormat binaryFormat = Tracing.getPropagationComponent().getBinaryFormat();
            try {
                SpanContext spanContext = binaryFormat.fromByteArray(traceContext);
                Span span = tracer.spanBuilderWithRemoteParent(spanName, spanContext).startSpan();
                span.addLink(Link.fromSpanContext(spanContext, Link.Type.PARENT_LINKED_SPAN));
                Map<String, AttributeValue> map = new HashMap<>();
                map.put("tx.thread", AttributeValue.stringAttributeValue(Thread.currentThread().getName()));
                map.put("bundle.event_count", AttributeValue.longAttributeValue(bundle.size()));
                map.put("bundle.caller_thread", AttributeValue.stringAttributeValue(callerThread));
                span.putAttributes(map);
                return span;
            } catch (SpanContextParseException e) {
                log.warn("Invalid trace context: " + traceContext.length, e);
                return BlankSpan.INSTANCE;
            }
        }
    }

    /**
     * Lets the listeners process the event bundle in bulk mode.
     * <p>
     * The event bundle is reconnected to a single session and a single transaction is started for all the listeners.
     * <p>
     * In case of exception in a listener, the transaction is rolled back and processing stops.
     * <p>
     * In case of timeout, the transaction is rolled back and processing stops.
     */
    protected static class EventBundleBulkRunner implements Callable<Boolean> {

        protected final List<EventListenerDescriptor> listeners;

        protected final EventBundle bundle;

        protected final String callerThread;

        protected final byte[] traceContext;

        public EventBundleBulkRunner(List<EventListenerDescriptor> listeners, EventBundle bundle) {
            this.listeners = listeners;
            this.bundle = bundle;
            callerThread = Thread.currentThread().getName();
            traceContext = Tracing.getPropagationComponent()
                                  .getBinaryFormat()
                                  .toByteArray(Tracing.getTracer().getCurrentSpan().getContext());
        }

        @Override
        public Boolean call() {
            Span span = getTracingSpan("postcommit/EventBundleBulkRunner");
            log.debug("Events postcommit bulk execution starting in thread: {}",
                    () -> Thread.currentThread().getName());
            long t0 = System.currentTimeMillis();

            boolean ok = false;
            boolean interrupt = false;
            ReconnectedEventBundle reconnected = null;
            // transaction timeout is managed by the FutureTask
            boolean tx = TransactionHelper.startTransaction();
            try (Scope scope = Tracing.getTracer().withSpan(span)) {
                Log4jCorrelation.start(span);
                reconnected = new ReconnectedEventBundleImpl(bundle, listeners.toString());
                for (EventListenerDescriptor listener : listeners) {
                    EventBundle filtered = listener.filterBundle(reconnected);
                    if (filtered.isEmpty()) {
                        continue;
                    }
                    log.debug("Events postcommit bulk execution start for listener: {}", listener::getName);
                    long t1 = System.currentTimeMillis();
                    try {

                        listener.asPostCommitListener().handleEvent(filtered);

                        if (Thread.currentThread().isInterrupted()) {
                            log.error("Events postcommit bulk execution interrupted for listener: {}, will rollback and"
                                    + " abort bulk processing", listener::getName);
                            interrupt = true;
                        }
                    } catch (RuntimeException e) {
                        log.error("Events postcommit bulk execution encountered exception for listener: {}",
                                listener::getName, () -> e);
                        span.setStatus(Status.UNKNOWN);
                        return Boolean.FALSE; // report error
                    } finally {
                        long elapsed = System.currentTimeMillis() - t1;
                        log.debug("Events postcommit bulk execution end for listener: {} in {}ms", listener::getName,
                                () -> elapsed);
                        span.addAnnotation("PostCommitEventExecutor Listener " + listener.getName() + " " + elapsed + " ms");
                    }
                    if (interrupt) {
                        break;
                    }
                }
                ok = !interrupt;
            } finally {
                try {
                    if (reconnected != null) {
                        reconnected.disconnect();
                    }
                } finally {
                    if (tx) {
                        if (!ok) {
                            TransactionHelper.setTransactionRollbackOnly();
                            log.error("Rolling back transaction");
                        }
                        TransactionHelper.commitOrRollbackTransaction();
                    }
                }
                long elapsed = System.currentTimeMillis() - t0;
                log.debug("Events postcommit bulk execution finished in {}ms", elapsed);
                span.end();
                Log4jCorrelation.end();
            }
            return Boolean.TRUE; // no error to report
        }

        protected Span getTracingSpan(String spanName) {
            if (traceContext == null) {
                return BlankSpan.INSTANCE;
            }
            Tracer tracer = Tracing.getTracer();
            BinaryFormat binaryFormat = Tracing.getPropagationComponent().getBinaryFormat();
            try {
                SpanContext spanContext = binaryFormat.fromByteArray(traceContext);
                Span span = tracer.spanBuilderWithRemoteParent(spanName, spanContext).startSpan();
                span.addLink(Link.fromSpanContext(spanContext, Link.Type.PARENT_LINKED_SPAN));
                Map<String, AttributeValue> map = new HashMap<>();
                map.put("tx.thread", AttributeValue.stringAttributeValue(Thread.currentThread().getName()));
                map.put("bundle.event_count", AttributeValue.longAttributeValue(bundle.size()));
                map.put("bundle.caller_thread", AttributeValue.stringAttributeValue(callerThread));
                span.putAttributes(map);
                return span;
            } catch (SpanContextParseException e) {
                log.warn("Invalid trace context: " + traceContext.length, e);
                return BlankSpan.INSTANCE;
            }
        }
    }
}
