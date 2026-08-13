package com.finsight.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class RedisBackedWorkflowLeaseServiceTest {

    @Test
    void rejectsLocalFallbackWhenProductionPolicyDisablesIt() {
        ObjectProvider<StringRedisTemplate> provider = unavailableRedisProvider();
        RedisBackedWorkflowLeaseService service =
                new RedisBackedWorkflowLeaseService(provider, "test-worker", false);

        assertThatThrownBy(() -> service.tryAcquire("report:600519", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local fallback is disabled");
    }

    @Test
    void localFallbackStillProvidesSingleFlightForDevelopment() {
        ObjectProvider<StringRedisTemplate> provider = unavailableRedisProvider();
        RedisBackedWorkflowLeaseService service =
                new RedisBackedWorkflowLeaseService(provider, "test-worker", true);

        WorkflowLease first = service.tryAcquire("report:600519", Duration.ofSeconds(30)).orElseThrow();

        assertThat(service.tryAcquire("report:600519", Duration.ofSeconds(30))).isEmpty();
        WorkflowLease renewed = service.renew(first, Duration.ofMinutes(1)).orElseThrow();
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        assertThat(service.renew(
                new WorkflowLease(first.key(), "stale-worker", first.fencingToken(), first.expiresAt()),
                Duration.ofMinutes(1)
        )).isEmpty();
        service.release(renewed);
        assertThat(service.tryAcquire("report:600519", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void analysisLeaseUsesSeparateNamespace() {
        ObjectProvider<StringRedisTemplate> provider = unavailableRedisProvider();
        RedisBackedWorkflowLeaseService service =
                new RedisBackedWorkflowLeaseService(provider, "test-worker", true);

        WorkflowLease workflow = service.tryAcquire("report:600519", Duration.ofSeconds(30)).orElseThrow();
        WorkflowLease analysis = service.tryAcquireAnalysis("report:600519", Duration.ofSeconds(30)).orElseThrow();

        assertThat(workflow.key()).isEqualTo(analysis.key());
        assertThat(workflow.fencingToken()).isNotEqualTo(analysis.fencingToken());
        // Workflow and analysis slots are tracked independently under local fallback.
        assertThat(service.tryAcquire("report:600519", Duration.ofSeconds(30))).isEmpty();
        assertThat(service.tryAcquireAnalysis("report:600519", Duration.ofSeconds(30))).isEmpty();

        service.releaseAnalysis(analysis);
        assertThat(service.tryAcquireAnalysis("report:600519", Duration.ofSeconds(30))).isPresent();
        // The workflow lease remains held.
        assertThat(service.tryAcquire("report:600519", Duration.ofSeconds(30))).isEmpty();
        service.release(workflow);
    }

    private ObjectProvider<StringRedisTemplate> unavailableRedisProvider() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(Object... args) {
                return null;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return null;
            }

            @Override
            public StringRedisTemplate getObject() {
                return null;
            }

            @Override
            public Iterator<StringRedisTemplate> iterator() {
                return Collections.emptyIterator();
            }
        };
    }
}
