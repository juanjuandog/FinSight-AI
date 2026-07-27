package com.finsight;

import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.model.Company;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles({"postgres", "rabbitmq", "prod"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finsight.ai-service.enabled=false",
        "management.health.rabbit.enabled=false",
        "management.health.redis.enabled=false"
})
class FinSightInfrastructureSmokeTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("finsight")
            .withUsername("finsight")
            .withPassword("finsight");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management")
            .withUser("finsight", "finsight")
            .withPermission("/", "finsight", ".*", ".*", ".*");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    WorkflowLeaseService workflowLeaseService;

    @Autowired
    StockAnalysisReportRepository reportRepository;

    @Autowired
    CompanyRepository companyRepository;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "finsight");
        registry.add("spring.rabbitmq.password", () -> "finsight");
        registry.add("spring.data.redis.url", () ->
                "redis://" + redis.getHost() + ":" + redis.getFirstMappedPort()
        );
    }

    @Test
    void applicationStartsWithPostgresPgvectorAndRabbitProfiles() {
        ResponseEntity<Map> health = restTemplate.getForEntity("/actuator/health", Map.class);
        ResponseEntity<Map> summary = restTemplate.getForEntity("/api/workflows/summary", Map.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody()).containsKeys("total", "counts", "failedOrDeadLetter");
    }

    @Test
    void redisLeaseAllowsOnlyOneConcurrentOwner() {
        Set<WorkflowLease> winners = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 50).parallel().forEach(ignored ->
                workflowLeaseService.tryAcquire("integration:single-flight", Duration.ofSeconds(30))
                        .ifPresent(winners::add)
        );

        assertThat(winners).hasSize(1);
        workflowLeaseService.release(winners.iterator().next());
    }

    @Test
    void postgresAllocatesUniqueReportVersionsConcurrently() {
        companyRepository.save(new Company("600519", "贵州茅台", "SH", "白酒"));
        Set<Integer> versions = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 50).parallel().forEach(ignored ->
                versions.add(reportRepository.nextVersion("600519"))
        );

        assertThat(versions).hasSize(50);
    }
}
