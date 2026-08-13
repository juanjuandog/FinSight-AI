package com.finsight.application;

import com.finsight.workflow.RedisBackedWorkflowLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrentAnalysisWaiterTest {

    @Test
    void waiterRunsLoaderAndReturnsResult() {
        ConcurrentAnalysisWaiter waiter = newWaiter(localLeaseService());
        String result = waiter.runOrWait(
                "k1",
                Duration.ofSeconds(30),
                () -> "value",
                value -> true
        );
        assertThat(result).isEqualTo("value");
    }

    @Test
    void waiterHandsResultToConcurrentPeer() throws Exception {
        ConcurrentAnalysisWaiter waiter = newWaiter(localLeaseService());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch loaderReached = new CountDownLatch(1);
        CountDownLatch primaryProceed = new CountDownLatch(1);
        AtomicInteger loaderCalls = new AtomicInteger();

        try {
            Future<String> primary = pool.submit(() -> waiter.runOrWait(
                    "k2",
                    Duration.ofSeconds(30),
                    () -> {
                        loaderCalls.incrementAndGet();
                        loaderReached.countDown();
                        boolean ok;
                        try {
                            ok = primaryProceed.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted", ex);
                        }
                        assertThat(ok).isTrue();
                        return "primary-finished";
                    },
                    value -> true
            ));
            assertThat(loaderReached.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> peer = pool.submit(() -> waiter.runOrWait(
                    "k2",
                    Duration.ofSeconds(30),
                    () -> {
                        throw new IllegalStateException("loader must not run for the peer");
                    },
                    value -> true
            ));

            Thread.sleep(50);
            primaryProceed.countDown();

            String primaryResult = primary.get(5, TimeUnit.SECONDS);
            String peerResult = peer.get(5, TimeUnit.SECONDS);

            assertThat(primaryResult).isEqualTo("primary-finished");
            assertThat(peerResult).isEqualTo("primary-finished");
            assertThat(loaderCalls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waiterTimesOutWhenPeerNeverFinishes() throws Exception {
        ConcurrentAnalysisWaiter waiter = new ConcurrentAnalysisWaiter(
                stubProvider(newTimedOutWaiterLeaseService()));
        CountDownLatch primaryStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> waiter.runOrWait(
                    "k3",
                    Duration.ofSeconds(30),
                    () -> {
                        primaryStarted.countDown();
                        try {
                            Thread.sleep(15_000);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return "never";
                    },
                    value -> true
            ));

            assertThat(primaryStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.runOrWait(
                    "k3",
                    Duration.ofSeconds(30),
                    () -> "ignored",
                    value -> true
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("peer did not finish");
        } finally {
            pool.shutdownNow();
        }
    }

    private static com.finsight.workflow.WorkflowLeaseService newTimedOutWaiterLeaseService() {
        // Use a service that always issues a lease so the peer must rely on slot wait.
        return new RedisBackedWorkflowLeaseService(unavailableRedisProvider(), "test-worker", true);
    }

    private static ConcurrentAnalysisWaiter newWaiter(RedisBackedWorkflowLeaseService service) {
        return new ConcurrentAnalysisWaiter(stubProvider((com.finsight.workflow.WorkflowLeaseService) service));
    }

    private static RedisBackedWorkflowLeaseService localLeaseService() {
        return new RedisBackedWorkflowLeaseService(unavailableRedisProvider(), "test-worker", true);
    }

    private static ObjectProvider<com.finsight.workflow.WorkflowLeaseService> stubProvider(com.finsight.workflow.WorkflowLeaseService service) {
        return new ObjectProvider<>() {
            @Override
            public com.finsight.workflow.WorkflowLeaseService getObject(Object... args) {
                return service;
            }

            @Override
            public com.finsight.workflow.WorkflowLeaseService getIfAvailable() {
                return service;
            }

            @Override
            public com.finsight.workflow.WorkflowLeaseService getIfUnique() {
                return service;
            }

            @Override
            public com.finsight.workflow.WorkflowLeaseService getObject() {
                return service;
            }

            @Override
            public Iterator<com.finsight.workflow.WorkflowLeaseService> iterator() {
                return Collections.singletonList(service).iterator();
            }
        };
    }

    private static ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> unavailableRedisProvider() {
        return new ObjectProvider<>() {
            @Override
            public org.springframework.data.redis.core.StringRedisTemplate getObject(Object... args) {
                return null;
            }

            @Override
            public org.springframework.data.redis.core.StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public org.springframework.data.redis.core.StringRedisTemplate getIfUnique() {
                return null;
            }

            @Override
            public org.springframework.data.redis.core.StringRedisTemplate getObject() {
                return null;
            }

            @Override
            public Iterator<org.springframework.data.redis.core.StringRedisTemplate> iterator() {
                return Collections.emptyIterator();
            }
        };
    }
}
