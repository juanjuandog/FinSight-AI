package com.finsight.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStockAnalysisReportRepositoryTest {

    @Test
    void allocatesUniqueMonotonicVersionsUnderConcurrency() {
        InMemoryStockAnalysisReportRepository repository = new InMemoryStockAnalysisReportRepository();
        Set<Integer> versions = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 100)
                .parallel()
                .forEach(ignored -> versions.add(repository.nextVersion("600519")));

        assertThat(versions).hasSize(100);
        assertThat(versions).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 100).boxed().toList()
        );
        assertThat(repository.nextVersion("000001")).isEqualTo(1);
    }
}
