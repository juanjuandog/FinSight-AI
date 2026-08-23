package com.finsight.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("redis")
@SpringBootTest(properties = {
        "finsight.ai-service.enabled=false",
        "finsight.scheduler.enabled=false",
        "finsight.workflow.allow-local-lease-fallback=false",
        "management.health.rabbit.enabled=false"
})
public abstract class AbstractRedisIT {
    static {
        IntegrationContainers.startRedis();
    }

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://"
                + IntegrationContainers.REDIS.getHost() + ":"
                + IntegrationContainers.REDIS.getFirstMappedPort());
    }

    @BeforeEach
    void flushRedis() {
        var factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("Redis connection factory was not configured");
        }
        factory.getConnection().serverCommands().flushAll();
    }
}
