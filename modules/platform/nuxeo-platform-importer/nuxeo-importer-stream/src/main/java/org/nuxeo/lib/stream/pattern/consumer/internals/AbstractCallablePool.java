/*
 * (C) Copyright 2017 Nuxeo SA (http://nuxeo.com/) and others.
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
package org.nuxeo.lib.stream.pattern.consumer.internals;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.nuxeo.common.concurrent.ThreadFactories.newThreadFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Execute a pool of callables.
 *
 * @since 9.1
 */
public abstract class AbstractCallablePool<T> implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(AbstractCallablePool.class);

    protected final short nbThreads;

    protected ExecutorService threadPool;

    protected ExecutorService supplyThreadPool;

    public AbstractCallablePool(short nbThreads) {
        this.nbThreads = nbThreads;
    }

    /**
     * Value to return when there was an exception during execution
     */
    protected abstract T getErrorStatus();

    protected abstract Callable<T> getCallable(int i);

    protected abstract String getThreadPrefix();

    protected abstract void afterCall(List<T> ret);

    public int getNbThreads() {
        return nbThreads;
    }

    public CompletableFuture<List<T>> start() {
        supplyThreadPool = Executors.newSingleThreadExecutor(newThreadFactory(getThreadPrefix() + "Pool"));
        CompletableFuture<List<T>> ret = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> {
            try {
                ret.complete(runPool());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ret.completeExceptionally(e);
            } catch (Exception e) {
                log.error("Exception catch in pool: {}", e.getMessage(), e);
                ret.completeExceptionally(e);
            }
            return ret;
        }, supplyThreadPool);
        // the threadpool will shutdown once the task is done
        supplyThreadPool.shutdown();
        return ret;
    }

    protected List<T> runPool() throws InterruptedException {
        threadPool = newFixedThreadPool(nbThreads, newThreadFactory(getThreadPrefix()));
        log.warn("Start {} Pool on {} thread(s).", getThreadPrefix(), nbThreads);
        List<CompletableFuture<T>> futures = new ArrayList<>(nbThreads);

        for (int i = 0; i < nbThreads; i++) {
            Callable<T> callable = getCallable(i);
            CompletableFuture<T> future = new CompletableFuture<>();
            CompletableFuture.supplyAsync(() -> {
                try {
                    future.complete(callable.call());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                } catch (Throwable e) { // NOSONAR
                    // Throwable is needed to catch all kind of problem that can happen in custom code
                    // when using future the UncaughtExceptionHandler is not reporting all errors.
                    // A LinkageError will stop silently the thread resulting in a hang during future.get
                    log.error("Exception catch in runner: {}", e.getMessage(), e);
                    future.completeExceptionally(e);
                }
                return future;
            }, threadPool);
            futures.add(future);
        }
        log.info("Pool is up and running");
        threadPool.shutdown();
        // We may return here and wait only in the get impl, but the sync cost should be cheap here
        List<T> ret = new ArrayList<>(nbThreads);
        for (CompletableFuture<T> future : futures) {
            T status;
            try {
                status = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("End of consumer interrupted.");
                status = getErrorStatus();
            } catch (ExecutionException e) {
                log.error("End of consumer in error: {} - {}",e.getMessage(), future);
                status = getErrorStatus();
            }
            ret.add(status);
        }
        afterCall(ret);
        return ret;
    }

    @Override
    public void close() {
        supplyThreadPool.shutdownNow();
        threadPool.shutdownNow();
    }

}
