package com.finsight.it.infrastructure;

import com.finsight.domain.model.Company;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.it.AbstractPostgresRedisRabbitIT;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureSmokeIT extends AbstractPostgresRedisRabbitIT {
    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    WorkflowLeaseService workflowLeaseService;

    @Autowired
    StockAnalysisReportRepository reportRepository;

    @Autowired
    CompanyRepository companyRepository;

    @Test
    void applicationStartsWithProductionInfrastructureProfiles() {
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
