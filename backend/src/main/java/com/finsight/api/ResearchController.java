package com.finsight.api;

import com.finsight.application.StockAiAnalysisService;
import com.finsight.application.StockAnalysisApplicationService;
import com.finsight.domain.model.DocumentChunk;
import com.finsight.rag.HybridRetrievalGateway;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskRepository;
import com.finsight.workflow.WorkflowStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/research")
public class ResearchController {
    private final StockAnalysisApplicationService stockAnalysisApplicationService;
    private final StockAiAnalysisService stockAiAnalysisService;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final HybridRetrievalGateway retrievalGateway;

    public ResearchController(
            StockAnalysisApplicationService stockAnalysisApplicationService,
            StockAiAnalysisService stockAiAnalysisService,
            WorkflowTaskRepository workflowTaskRepository,
            HybridRetrievalGateway retrievalGateway
    ) {
        this.stockAnalysisApplicationService = stockAnalysisApplicationService;
        this.stockAiAnalysisService = stockAiAnalysisService;
        this.workflowTaskRepository = workflowTaskRepository;
        this.retrievalGateway = retrievalGateway;
    }

    @PostMapping("/tasks")
    public ResearchTaskResponse submit(@RequestBody ResearchTaskRequest request) {
        String symbol = request == null || request.symbol() == null ? "" : request.symbol();
        var result = stockAnalysisApplicationService.submitBatch(
                new StockAnalysisApplicationService.BatchAnalysisRequest(List.of(symbol), 1)
        );
        var item = result.items().stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No research task submitted"));
        if (!item.submitted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.error());
        }
        WorkflowTask task = workflowTaskRepository.findById(item.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research workflow task not found"));
        return response(task);
    }

    @GetMapping("/tasks/{taskId}")
    public ResearchTaskResponse task(@PathVariable String taskId) {
        return workflowTaskRepository.findById(taskId)
                .map(this::response)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research workflow task not found"));
    }

    @GetMapping("/tasks/{taskId}/progress")
    public List<ResearchTaskResponse> progress(@PathVariable String taskId) {
        if (workflowTaskRepository.findById(taskId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Research workflow task not found");
        }
        return workflowTaskRepository.findByRootTaskId(taskId).stream()
                .map(this::response)
                .toList();
    }

    @GetMapping("/reports/{symbol}/latest")
    public StockAiAnalysisService.StockAiAnalysisResponse latestReport(@PathVariable String symbol) {
        return stockAiAnalysisService.latest(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research report not found"));
    }

    @GetMapping("/reports/{symbol}/trace")
    public ResearchReportTrace latestTrace(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "6") int limit
    ) {
        var report = stockAiAnalysisService.latest(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research report not found"));
        String query = report.summary() == null || report.summary().isBlank()
                ? symbol + " 投资价值 财务质量 风险"
                : report.summary();
        List<EvidenceTraceItem> evidence = retrievalGateway.search(symbol, query, Math.min(Math.max(limit, 1), 12)).stream()
                .map(hit -> traceItem(hit.chunk(), hit.channel(), hit.score(), hit.ranks()))
                .toList();
        return new ResearchReportTrace(
                report.reportId(),
                symbol,
                report.reportVersion(),
                report.dataSnapshotHash(),
                report.cacheHit(),
                report.model(),
                report.aiGenerated(),
                report.generatedAt(),
                report.citations(),
                evidence
        );
    }

    private ResearchTaskResponse response(WorkflowTask task) {
        String symbol = String.valueOf(task.payload().getOrDefault("companySymbol", ""));
        return new ResearchTaskResponse(
                task.id(),
                symbol,
                task.taskType(),
                task.status(),
                task.stage().name(),
                task.attempts(),
                task.idempotencyKey(),
                task.leaseOwner(),
                task.fencingToken(),
                task.errorMessage(),
                task.createdAt(),
                task.updatedAt(),
                Duration.between(task.createdAt(), Instant.now()).toMillis()
        );
    }

    private EvidenceTraceItem traceItem(
            DocumentChunk chunk,
            String channel,
            double score,
            Map<String, Integer> ranks
    ) {
        return new EvidenceTraceItem(
                chunk.documentId(),
                chunk.title(),
                chunk.section(),
                channel,
                chunk.text(),
                score,
                ranks
        );
    }

    public record ResearchTaskRequest(String symbol) {
    }

    public record ResearchTaskResponse(
            String taskId,
            String symbol,
            String taskType,
            WorkflowStatus status,
            String stage,
            int attempts,
            String idempotencyKey,
            String leaseOwner,
            Long fencingToken,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            long ageMillis
    ) {
    }

    public record ResearchReportTrace(
            String reportId,
            String symbol,
            int reportVersion,
            String dataSnapshotHash,
            boolean cacheHit,
            String model,
            boolean aiGenerated,
            Instant generatedAt,
            List<String> citations,
            List<EvidenceTraceItem> evidence
    ) {
    }

    public record EvidenceTraceItem(
            String documentId,
            String title,
            String section,
            String channel,
            String text,
            double score,
            Map<String, Integer> ranks
    ) {
    }
}
