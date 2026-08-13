package com.finsight.application;

import com.finsight.workflow.RedisBackedWorkflowLeaseService;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tracks in-flight analysis attempts per cache key so concurrent callers can wait for a
 * finishing peer instead of polling the cache and database in a busy loop.
 *
 * <p>Uses per-key {@link CompletableFuture} which avoids reactive dependencies and is
 * straightforward to test with plain JUnit + sleeps.
 */
@Component
public class ConcurrentAnalysisWaiter {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentAnalysisWaiter.class);
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    private final Map<String, CompletableFuture<Object>> slots = new ConcurrentHashMap<>();
    private final ObjectProvider<WorkflowLeaseService> leaseServiceProvider;

    public ConcurrentAnalysisWaiter(ObjectProvider<WorkflowLeaseService> leaseServiceProvider) {
        this.leaseServiceProvider = leaseServiceProvider;
    }

    /**
     * Run the analysis under the analysis lease, or wait for a concurrent peer to finish.
     *
     * @return the analysis result, never null
     * @throws IllegalStateException if neither this caller nor any peer finishes within {@code wait}
     */
    public <T> T runOrWait(
            String key,
            Duration leaseTtl,
            Loader<T> loader,
            Asserter<T> asserter
    ) {
        WorkflowLeaseService leaseService = leaseServiceProvider.getObject();
        Optional<WorkflowLease> lease = tryAcquireAnalysis(leaseService, key, leaseTtl);
        if (lease.isPresent()) {
            return runAndPublish(key, lease.get(), leaseService, loader);
        }
        return waitForPeer(key, DEFAULT_WAIT, asserter);
    }

    private Optional<WorkflowLease> tryAcquireAnalysis(WorkflowLeaseService service, String key, Duration ttl) {
        if (service instanceof RedisBackedWorkflowLeaseService redis) {
            return redis.tryAcquireAnalysis(key, ttl);
        }
        return service.tryAcquire(key, ttl);
    }

    @SuppressWarnings("unchecked")
    private <T> T runAndPublish(String key, WorkflowLease lease, WorkflowLeaseService leaseService, Loader<T> loader) {
        CompletableFuture<Object> slot = new CompletableFuture<>();
        CompletableFuture<Object> existing = slots.putIfAbsent(key, slot);
        try {
            T result = loader.load();
            if (existing == null) {
                slot.complete(result);
            } else {
                // Another waiter raced ahead; let the original slot finish.
                existing.complete(result);
            }
            return result;
        } catch (RuntimeException | Error ex) {
            if (existing == null) {
                slot.completeExceptionally(ex);
            } else {
                existing.completeExceptionally(ex);
            }
            throw ex;
        } finally {
            leaseService.release(lease);
            // Optimistic cleanup; safe even if another consumer is still waiting.
            slots.remove(key, slot);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T waitForPeer(String key, Duration wait, Asserter<T> asserter) {
        CompletableFuture<Object> slot = slots.computeIfAbsent(key, ignored -> new CompletableFuture<>());
        try {
            Object result = slot.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            T typed = (T) result;
            if (asserter != null && !asserter.isAcceptable(typed)) {
                throw new IllegalStateException("Concurrent peer produced an unexpected result");
            }
            return typed;
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Stock analysis is already running and peer did not finish in time", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for stock analysis", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("Peer analysis failed", cause);
        }
    }

    public void invalidate(String key) {
        slots.remove(key);
    }

    public interface Loader<T> {
        T load();
    }

    public interface Asserter<T> {
        boolean isAcceptable(T result);
    }

    @SuppressWarnings("unused")
    private static void logSlotMiss(String key) {
        log.debug("No peer slot for {}", key);
    }
}
