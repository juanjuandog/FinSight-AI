package com.finsight.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finsight.ai-service.enabled=false",
        "finsight.scheduler.enabled=false",
        "management.health.rabbit.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
public abstract class AbstractPostgresIT {
    static {
        IntegrationContainers.startPostgres();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationContainers.POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach
    void truncatePostgres() {
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
    }
}
