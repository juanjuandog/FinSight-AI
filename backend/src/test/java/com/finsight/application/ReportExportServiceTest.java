package com.finsight.application;

import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.infrastructure.InMemoryStockAnalysisCache;
import com.finsight.infrastructure.InMemoryStockAnalysisReportRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportExportServiceTest {

    @Test
    void cachesRenderedPdfByCompanyAndReportVersion() {
        InMemoryStockAnalysisReportRepository reports = new InMemoryStockAnalysisReportRepository();
        reports.save(ReportMarkdownExporterTest.report());
        CompanyRepository companies = mock(CompanyRepository.class);
        MetricRepository metrics = mock(MetricRepository.class);
        ReportPdfExporter pdfExporter = mock(ReportPdfExporter.class);
        when(companies.findBySymbol("600519")).thenReturn(Optional.of(ReportMarkdownExporterTest.company()));
        when(metrics.findMetrics("600519")).thenReturn(ReportMarkdownExporterTest.metrics());
        when(metrics.findRiskSignals("600519")).thenReturn(ReportMarkdownExporterTest.risks());
        when(pdfExporter.export(ReportMarkdownExporterTest.report(), ReportMarkdownExporterTest.company(),
                ReportMarkdownExporterTest.metrics(), ReportMarkdownExporterTest.risks()))
                .thenReturn(new byte[]{1, 2, 3});
        ReportDiffService diffService = new ReportDiffService(reports);
        ReportExportService service = new ReportExportService(
                reports,
                companies,
                metrics,
                diffService,
                new ReportMarkdownExporter(),
                pdfExporter,
                new InMemoryStockAnalysisCache(),
                Duration.ofHours(24)
        );

        byte[] first = service.pdf("600519", "report-2");
        first[0] = 9;
        byte[] second = service.pdf("600519", "report-2");

        assertThat(second).containsExactly(1, 2, 3);
        verify(pdfExporter, times(1)).export(ReportMarkdownExporterTest.report(),
                ReportMarkdownExporterTest.company(), ReportMarkdownExporterTest.metrics(),
                ReportMarkdownExporterTest.risks());
        verify(companies, times(1)).findBySymbol("600519");
        verify(metrics, times(1)).findMetrics("600519");
        verify(metrics, times(1)).findRiskSignals("600519");
    }
}
