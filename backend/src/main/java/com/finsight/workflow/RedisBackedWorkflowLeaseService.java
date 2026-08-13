package com.finsight.workflow;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RedisBackedWorkflowLeaseService implements WorkflowLeaseService {
    public static final String WORKFLOW_KEY_PREFIX = "finsight:workflow:lease:";
    public static final String WORKFLOW_FENCE_PREFIX = "finsight:workflow:fence:";
    public static final String ANALYSIS_KEY_PREFIX = "finsight:analysis:lease:";
    public static final String ANALYSIS_FENCE_PREFIX = "finsight:analysis:fence:";
    private static final String ACQUIRE_LUA = """
            local leaseKey = KEYS[1]
            local fenceKey = KEYS[2]
            local owner = ARGV[1]
            local ttlMillis = tonumber(ARGV[2])
            if redis.call('exists', leaseKey) == 0 then
                local token = redis.call('incr', fenceKey)
                redis.call('psetex', leaseKey, ttlMillis, owner .. ':' .. token)
                return token
            end
            return nil
            """;
    private static final String RELEASE_LUA = """
            local leaseKey = KEYS[1]
            local expected = ARGV[1]
            if redis.call('get', leaseKey) == expected then
                return redis.call('del', leaseKey)
            end
            return 0
            """;
    private static final String RENEW_LUA = """
            local leaseKey = KEYS[1]
            local expected = ARGV[1]
            local ttlMillis = tonumber(ARGV[2])
            if redis.call('get', leaseKey) == expected then
                redis.call('pexpire', leaseKey, ttlMillis)
                return 1
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;
    private final String ownerPrefix;
    private final boolean allowLocalFallback;
    private final ConcurrentHashMap<String, WorkflowLease> localLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowLease> localAnalysisLeases = new ConcurrentHashMap<>();
    private final AtomicLong localFence = new AtomicLong();

    public RedisBackedWorkflowLeaseService(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${finsight.workflow.lease-owner:}") String configuredOwner,
            @Value("${finsight.workflow.allow-local-lease-fallback:true}") boolean allowLocalFallback
    ) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.allowLocalFallback = allowLocalFallback;
        this.ownerPrefix = configuredOwner == null || configuredOwner.isBlank()
                ? ManagementFactory.getRuntimeMXBean().getName()
                : configuredOwner;
    }

    @Override
    public Optional<WorkflowLease> tryAcquire(String key, Duration ttl) {
        String owner = ownerPrefix + ":" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ttl);
        if (redisTemplate != null) {
            try {
                Long token = redisTemplate.execute(
                        new DefaultRedisScript<>(ACQUIRE_LUA, Long.class),
                        List.of(redisKey(key), redisFenceKey(key)),
                        owner,
                        String.valueOf(ttl.toMillis())
                );
                return token == null ? Optional.empty() : Optional.of(new WorkflowLease(key, owner, token, expiresAt));
            } catch (RuntimeException ex) {
                if (!allowLocalFallback) {
                    throw new IllegalStateException("Redis lease acquisition failed and local fallback is disabled", ex);
                }
            }
        }
        if (!allowLocalFallback) {
            throw new IllegalStateException("Redis lease service is unavailable and local fallback is disabled");
        }
        WorkflowLease lease = new WorkflowLease(key, owner, localFence.incrementAndGet(), expiresAt);
        WorkflowLease existing = localLeases.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(Instant.now())) {
                return lease;
            }
            return current;
        });
        return lease.equals(existing) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public Optional<WorkflowLease> renew(WorkflowLease lease, Duration ttl) {
        WorkflowLease renewed = new WorkflowLease(
                lease.key(),
                lease.owner(),
                lease.fencingToken(),
                Instant.now().plus(ttl)
        );
        if (redisTemplate != null) {
            try {
                Long result = redisTemplate.execute(
                        new DefaultRedisScript<>(RENEW_LUA, Long.class),
                        List.of(redisKey(lease.key())),
                        lease.owner() + ":" + lease.fencingToken(),
                        String.valueOf(ttl.toMillis())
                );
                return Long.valueOf(1).equals(result) ? Optional.of(renewed) : Optional.empty();
            } catch (RuntimeException ex) {
                if (!allowLocalFallback) {
                    throw new IllegalStateException("Redis lease renewal failed and local fallback is disabled", ex);
                }
            }
        }
        if (!allowLocalFallback) {
            throw new IllegalStateException("Redis lease service is unavailable and local fallback is disabled");
        }
        boolean replaced = localLeases.replace(lease.key(), lease, renewed);
        return replaced ? Optional.of(renewed) : Optional.empty();
    }

    @Override
    public void release(WorkflowLease lease) {
        if (redisTemplate != null) {
            try {
                redisTemplate.execute(
                        new DefaultRedisScript<>(RELEASE_LUA, Long.class),
                        List.of(redisKey(lease.key())),
                        lease.owner() + ":" + lease.fencingToken()
                );
                return;
            } catch (RuntimeException ex) {
                if (!allowLocalFallback) {
                    throw new IllegalStateException("Redis lease release failed and local fallback is disabled", ex);
                }
            }
        }
        localLeases.remove(lease.key(), lease);
    }

    private String redisKey(String key) {
        return WORKFLOW_KEY_PREFIX + key;
    }

    private String redisFenceKey(String key) {
        return WORKFLOW_FENCE_PREFIX + key;
    }

    public Optional<WorkflowLease> tryAcquireAnalysis(String key, Duration ttl) {
        // Re-uses the same Lua scripts but with a separate namespace so workflow
        // leases and analysis leases do not collide.
        String owner = ownerPrefix + ":analysis:" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ttl);
        if (redisTemplate != null) {
            try {
                Long token = redisTemplate.execute(
                        new DefaultRedisScript<>(ACQUIRE_LUA, Long.class),
                        List.of(ANALYSIS_KEY_PREFIX + key, ANALYSIS_FENCE_PREFIX + key),
                        owner,
                        String.valueOf(ttl.toMillis())
                );
                return token == null ? Optional.empty() : Optional.of(new WorkflowLease(key, owner, token, expiresAt));
            } catch (RuntimeException ex) {
                if (!allowLocalFallback) {
                    throw new IllegalStateException("Redis analysis lease acquisition failed and local fallback is disabled", ex);
                }
            }
        }
        if (!allowLocalFallback) {
            throw new IllegalStateException("Redis lease service is unavailable and local fallback is disabled");
        }
        WorkflowLease lease = new WorkflowLease(key, owner, localFence.incrementAndGet(), expiresAt);
        WorkflowLease existing = localAnalysisLeases.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(Instant.now())) {
                return lease;
            }
            return current;
        });
        return lease.equals(existing) ? Optional.of(lease) : Optional.empty();
    }

    public void releaseAnalysis(WorkflowLease lease) {
        if (redisTemplate != null) {
            try {
                redisTemplate.execute(
                        new DefaultRedisScript<>(RELEASE_LUA, Long.class),
                        List.of(ANALYSIS_KEY_PREFIX + lease.key()),
                        lease.owner() + ":" + lease.fencingToken()
                );
                return;
            } catch (RuntimeException ex) {
                if (!allowLocalFallback) {
                    throw new IllegalStateException("Redis analysis lease release failed and local fallback is disabled", ex);
                }
            }
        }
        localAnalysisLeases.remove(lease.key(), lease);
    }
}
