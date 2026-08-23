package com.finsight.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles({"postgres", "redis", "prod"})
@SpringBootTest(properties = {
        "finsight.ai-service.enabled=false",
        "finsight.scheduler.enabled=false",
        "finsight.workflow.allow-local-lease-fallback=false",
        "management.health.rabbit.enabled=false",
        "management.health.redis.enabled=false"
})
public abstract class AbstractPostgresRedisIT {
    static {
        IntegrationContainers.startPostgres();
        IntegrationContainers.startRedis();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationContainers.POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.url", () -> "redis://"
                + IntegrationContainers.REDIS.getHost() + ":"
                + IntegrationContainers.REDIS.getFirstMappedPort());
    }

    @BeforeEach
    void resetInfrastructure() {
        jdbcTemplate.execute("""
                DO $$
                DECLARE table_row RECORD;
                BEGIN
                    FOR table_row IN
                        SELECT tablename
                        FROM pg_tables
                        WHERE schemaname = 'public'
                          AND tablename <> 'flyway_schema_history'
                    LOOP
                        EXECUTE format('TRUNCATE TABLE %I RESTART IDENTITY CASCADE', table_row.tablename);
                    END LOOP;
                END $$
                """);
        var factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("Redis connection factory was not configured");
        }
        factory.getConnection().serverCommands().flushAll();
    }
}
