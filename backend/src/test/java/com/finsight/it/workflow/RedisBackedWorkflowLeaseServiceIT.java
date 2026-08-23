package com.finsight.it.workflow;

import com.finsight.it.AbstractRedisIT;
import com.finsight.workflow.RedisBackedWorkflowLeaseService;
import com.finsight.workflow.WorkflowLease;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RedisBackedWorkflowLeaseServiceIT extends AbstractRedisIT {
    @Autowired
    RedisBackedWorkflowLeaseService leaseService;

    @Test
    void acquireStoresLeaseInRedis() {
        WorkflowLease lease = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();

        assertThat(redisTemplate.opsForValue().get(RedisBackedWorkflowLeaseService.WORKFLOW_KEY_PREFIX + "task-a"))
                .isEqualTo(lease.owner() + ":" + lease.fencingToken());
    }

    @Test
    void secondOwnerCannotAcquireActiveLease() {
        leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();

        assertThat(leaseService.tryAcquire("task-a", Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void releaseAllowsNewOwnerAndAdvancesFence() {
        WorkflowLease first = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();
        leaseService.release(first);

        WorkflowLease second = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();

        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(second.owner()).isNotEqualTo(first.owner());
    }

    @Test
    void staleOwnerCannotReleaseCurrentLease() {
        WorkflowLease current = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();
        WorkflowLease stale = new WorkflowLease(
                current.key(),
                "stale-owner",
                current.fencingToken(),
                current.expiresAt()
        );

        leaseService.release(stale);

        assertThat(leaseService.tryAcquire("task-a", Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void renewKeepsFenceAndExtendsExpiry() {
        WorkflowLease acquired = leaseService.tryAcquire("task-a", Duration.ofMillis(500)).orElseThrow();

        WorkflowLease renewed = leaseService.renew(acquired, Duration.ofSeconds(10)).orElseThrow();

        assertThat(renewed.fencingToken()).isEqualTo(acquired.fencingToken());
        assertThat(renewed.owner()).isEqualTo(acquired.owner());
        assertThat(renewed.expiresAt()).isAfter(acquired.expiresAt());
        assertThat(redisTemplate.getExpire(RedisBackedWorkflowLeaseService.WORKFLOW_KEY_PREFIX + "task-a"))
                .isPositive();
    }

    @Test
    void staleOwnerCannotRenewLease() {
        WorkflowLease acquired = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();
        WorkflowLease stale = new WorkflowLease(
                acquired.key(),
                "stale-owner",
                acquired.fencingToken(),
                Instant.now().plusSeconds(10)
        );

        assertThat(leaseService.renew(stale, Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void expiredLeaseCanBeAcquiredWithNewFence() throws InterruptedException {
        WorkflowLease first = leaseService.tryAcquire("task-a", Duration.ofMillis(80)).orElseThrow();
        Thread.sleep(160);

        WorkflowLease second = leaseService.tryAcquire("task-a", Duration.ofSeconds(10)).orElseThrow();

        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
    }

    @Test
    void analysisAndWorkflowNamespacesDoNotCollide() {
        WorkflowLease workflow = leaseService.tryAcquire("same-key", Duration.ofSeconds(10)).orElseThrow();
        WorkflowLease analysis = leaseService.tryAcquireAnalysis("same-key", Duration.ofSeconds(10)).orElseThrow();

        assertThat(workflow.fencingToken()).isPositive();
        assertThat(analysis.fencingToken()).isPositive();
        assertThat(redisTemplate.hasKey(RedisBackedWorkflowLeaseService.WORKFLOW_KEY_PREFIX + "same-key")).isTrue();
        assertThat(redisTemplate.hasKey(RedisBackedWorkflowLeaseService.ANALYSIS_KEY_PREFIX + "same-key")).isTrue();
    }

    @Test
    void concurrentAcquisitionHasSingleWinner() {
        Set<WorkflowLease> winners = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 100).parallel().forEach(ignored ->
                leaseService.tryAcquire("contended", Duration.ofSeconds(10)).ifPresent(winners::add)
        );

        assertThat(winners).hasSize(1);
    }
}
