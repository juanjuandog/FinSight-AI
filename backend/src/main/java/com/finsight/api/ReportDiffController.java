package com.finsight.api;

import com.finsight.application.ReportDiffService;
import com.finsight.application.ReportExportService;
import com.finsight.domain.model.ReportDiff;
import com.finsight.domain.model.StockAnalysisReport;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping({"/api/research/stock/{symbol}/reports", "/api/research/reports/{symbol}"})
public class ReportDiffController {
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);
    private final ReportDiffService reportDiffService;
    private final ReportExportService reportExportService;

    public ReportDiffController(ReportDiffService reportDiffService, ReportExportService reportExportService) {
        this.reportDiffService = reportDiffService;
        this.reportExportService = reportExportService;
    }

    @GetMapping
    public List<StockAnalysisReport> history(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "12") int limit
    ) {
        return reportExportService.history(symbol, limit);
    }

    @GetMapping("/{fromId}/diff/{toId}")
    public ReportDiff diff(
            @PathVariable String symbol,
            @PathVariable String fromId,
            @PathVariable String toId
    ) {
        return reportDiffService.diff(symbol, fromId, toId);
    }

    @GetMapping(value = "/{reportId}.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable String symbol, @PathVariable String reportId) {
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(symbol, reportId, "md"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(reportExportService.markdown(symbol, reportId));
    }

    @GetMapping(value = "/{reportId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String symbol, @PathVariable String reportId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(symbol, reportId, "pdf"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(reportExportService.pdf(symbol, reportId));
    }

    private String attachment(String symbol, String reportId, String extension) {
        String safeSymbol = symbol == null ? "report" : symbol.replaceAll("[^A-Za-z0-9._-]", "-");
        String safeId = reportId == null ? "latest" : reportId.replaceAll("[^A-Za-z0-9._-]", "-");
        return ContentDisposition.attachment()
                .filename("finsight-" + safeSymbol + "-" + safeId + "." + extension, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
