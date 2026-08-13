package com.finsight.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.domain.model.Company;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.domain.model.FinancialDocument;
import com.finsight.domain.model.FinancialMetric;
import com.finsight.domain.model.RiskSignal;
import com.finsight.domain.model.StockAnalysisReport;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.DocumentRepository;
import com.finsight.domain.repository.MetricRepository;
import com.finsight.domain.repository.StockAnalysisReportRepository;
import com.finsight.market.ExchangeResolver;
import com.finsight.market.MarketDataService;
import com.finsight.market.MarketQuote;
import com.finsight.rag.EvidenceRetriever;
import com.finsight.workflow.WorkflowLease;
import com.finsight.workflow.WorkflowLeaseService;
import com.finsight.ai.AiSidecarCircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StockAiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(StockAiAnalysisService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String GUIDANCE_VERSION = "research-guidance-v1";

    private final CompanyRepository companyRepository;
    private final MetricRepository metricRepository;
    private final DocumentRepository documentRepository;
    private final MarketDataService marketDataService;
    private final ExchangeResolver exchangeResolver;
    private final StockUniverseService stockUniverseService;
    private final EvidenceRetriever evidenceRetriever;
    private final StockAnalysisReportRepository reportRepository;
    private final StockAnalysisCache analysisCache;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final Duration analysisCacheTtl;
    private final WorkflowLeaseService leaseService;
    private final ConcurrentAnalysisWaiter concurrentWaiter;
    private final AiSidecarCircuitBreaker circuitBreaker;
    private final ReportPersistenceService reportPersistence;
    private final GuidanceScorer guidanceScorer;
    private final MeterRegistry meterRegistry;
    private final Map<String, StockAiAnalysisResponse> latestResponses = new ConcurrentHashMap<>();

    public StockAiAnalysisService(
            CompanyRepository companyRepository,
            MetricRepository metricRepository,
            DocumentRepository documentRepository,
            MarketDataService marketDataService,
            ExchangeResolver exchangeResolver,
            StockUniverseService stockUniverseService,
            EvidenceRetriever evidenceRetriever,
            StockAnalysisReportRepository reportRepository,
            StockAnalysisCache analysisCache,
            ObjectMapper objectMapper,
            WebClient.Builder builder,
            WorkflowLeaseService leaseService,
            ConcurrentAnalysisWaiter concurrentWaiter,
            AiSidecarCircuitBreaker circuitBreaker,
            ReportPersistenceService reportPersistence,
            GuidanceScorer guidanceScorer,
            MeterRegistry meterRegistry,
            @Value("${finsight.ai-service-url:http://localhost:8001}") String aiServiceUrl,
            @Value("${finsight.cache.analysis-ttl:PT6H}") Duration analysisCacheTtl
    ) {
        this.companyRepository = companyRepository;
        this.metricRepository = metricRepository;
        this.documentRepository = documentRepository;
        this.marketDataService = marketDataService;
        this.exchangeResolver = exchangeResolver;
        this.stockUniverseService = stockUniverseService;
        this.evidenceRetriever = evidenceRetriever;
        this.reportRepository = reportRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
        this.webClient = builder.baseUrl(trimTrailingSlash(aiServiceUrl)).build();
        this.leaseService = leaseService;
        this.concurrentWaiter = concurrentWaiter;
        this.circuitBreaker = circuitBreaker;
        this.reportPersistence = reportPersistence;
        this.guidanceScorer = guidanceScorer;
        this.meterRegistry = meterRegistry;
        this.analysisCacheTtl = analysisCacheTtl;
    }

    public StockAiAnalysisResponse analyze(String symbol) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        Company company = companyRepository.findBySymbol(normalized)
                .orElseGet(() -> stockUniverseService.resolveAStock(normalized));
        MarketQuote quote = marketDataService.quote(normalized);
        List<FinancialMetric> metrics = metricRepository.findMetrics(normalized).stream()
                .sorted(Comparator.comparing(FinancialMetric::fiscalYear).reversed())
                .limit(24)
                .toList();
        List<RiskSignal> risks = metricRepository.findRiskSignals(normalized).stream()
                .sorted(Comparator.comparing(RiskSignal::detectedAt).reversed())
                .limit(12)
                .toList();
        List<EvidencePayload> evidence = evidence(normalized, company.name());
        StockAiAnalysisRequest request = new StockAiAnalysisRequest(
                company,
                quote,
                metrics,
                risks,
                evidence
        );

        String contextHash = contextHash(request);
        String dataSnapshotHash = contextHash;
        String cacheKey = normalized + ":" + dataSnapshotHash;
        Optional<StockAiAnalysisResponse> cached = analysisCache.get(cacheKey)
                .map(StockAiAnalysisResponse::withCacheHit);
        if (cached.isPresent()) {
            latestResponses.put(normalized, cached.get());
            return cached.get();
        }
        Optional<StockAiAnalysisResponse> latest = reportRepository.findLatest(normalized)
                .filter(report -> report.contextHash().equals(contextHash))
                .map(this::fromReport)
                .map(StockAiAnalysisResponse::withCacheHit);
        if (latest.isPresent()) {
            analysisCache.put(cacheKey, latest.get(), analysisCacheTtl);
            return latest.get();
        }

        String leaseKey = "stock-analysis:" + cacheKey;
        return concurrentWaiter.runOrWait(
                leaseKey,
                Duration.ofSeconds(90),
                () -> executeUnderLease(
                        normalized, contextHash, dataSnapshotHash, cacheKey,
                        request, company, quote, metrics, risks, evidence),
                result -> result != null && result.summary() != null
        );
    }

    private StockAiAnalysisResponse executeUnderLease(
            String normalized,
            String contextHash,
            String dataSnapshotHash,
            String cacheKey,
            StockAiAnalysisRequest request,
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
        Optional<StockAiAnalysisResponse> secondCheck = analysisCache.get(cacheKey)
                .or(() -> reportRepository.findLatest(normalized)
                        .filter(report -> report.contextHash().equals(contextHash))
                        .map(this::fromReport))
                .map(StockAiAnalysisResponse::withCacheHit);
        if (secondCheck.isPresent()) {
            latestResponses.put(normalized, secondCheck.get());
            return secondCheck.get();
        }
        StockAiAnalysisResponse response = callAiOrFallback(request, company, quote, metrics, risks, evidence);
        return persistAndCache(normalized, contextHash, dataSnapshotHash, cacheKey, response);
    }

    private StockAiAnalysisResponse callAiOrFallback(
            StockAiAnalysisRequest request,
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
        StockAiAnalysisResponse response = null;
        long start = System.nanoTime();
        String outcome = "skipped";
        if (!circuitBreaker.tryAcquire()) {
            outcome = "circuit_open";
            recordCallMetric(start, outcome);
            return fallback(company, quote, metrics, risks, evidence);
        }
        try {
            response = webClient.post()
                    .uri("/analyze-stock")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(StockAiAnalysisResponse.class)
                    .block(TIMEOUT);
            if (response != null && response.summary() != null && !response.summary().isBlank()) {
                circuitBreaker.recordSuccess();
                outcome = "success";
            } else {
                outcome = "empty";
            }
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure();
            outcome = classify(ex);
            log.warn("AI sidecar /analyze-stock failed for {}: outcome={}", company.symbol(), outcome);
        } finally {
            recordCallMetric(start, outcome);
        }
        return response == null || response.summary() == null || response.summary().isBlank()
                ? fallback(company, quote, metrics, risks, evidence)
                : response.withGuidance(guidanceScorer.score(company, quote, metrics, risks, evidence, response.positivePoints(), response.riskPoints()));
    }

    private String classify(Throwable ex) {
        if (ex instanceof WebClientResponseException webEx) {
            return "http_" + webEx.getStatusCode().value();
        }
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        String message = ex.getClass().getSimpleName();
        return message.isBlank() ? "exception" : message.toLowerCase();
    }

    private void recordCallMetric(long startNanos, String outcome) {
        Timer.builder("finsight.ai.sidecar.analyze.duration")
                .description("AI sidecar analyze-stock latency")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        meterRegistry.counter("finsight.ai.sidecar.analyze.total", "outcome", outcome).increment();
    }

    public Optional<StockAiAnalysisResponse> latest(String symbol) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        StockAiAnalysisResponse current = latestResponses.get(normalized);
        if (current != null) {
            return Optional.of(current);
        }
        return reportRepository.findLatest(normalized).map(this::fromReport);
    }

    public List<StockAiAnalysisResponse> history(String symbol, int limit) {
        String normalized = exchangeResolver.normalizeSymbol(symbol);
        return reportRepository.findByCompanySymbol(normalized, Math.min(Math.max(limit, 1), 50)).stream()
                .map(this::fromReport)
                .toList();
    }

    private List<EvidencePayload> evidence(String symbol, String companyName) {
        List<EvidencePayload> ragEvidence = evidenceRetriever.retrieve(
                        companyName + " 投资价值、财务质量、现金流和主要风险",
                        Map.of("companySymbol", symbol, "requiresMetrics", true)
                ).stream()
                .limit(8)
                .map(this::evidencePayload)
                .toList();
        if (!ragEvidence.isEmpty()) {
            return ragEvidence;
        }
        return documentRepository.findByCompanySymbol(symbol).stream()
                .sorted(Comparator.comparing(FinancialDocument::publishedAt).reversed())
                .limit(8)
                .map(this::evidencePayload)
                .toList();
    }

    private EvidencePayload evidencePayload(EvidenceChunk chunk) {
        String text = chunk.text() == null ? "" : chunk.text();
        if (text.length() > 420) {
            text = text.substring(0, 420);
        }
        return new EvidencePayload(
                chunk.documentId(),
                chunk.title(),
                chunk.documentType().name(),
                chunk.publishedAt() == null ? null : chunk.publishedAt().toString(),
                chunk.section(),
                text
        );
    }

    private EvidencePayload evidencePayload(FinancialDocument document) {
        String text = document.content() == null ? "" : document.content();
        if (text.length() > 360) {
            text = text.substring(0, 360);
        }
        return new EvidencePayload(
                document.id(),
                document.title(),
                document.type().name(),
                document.publishedAt() == null ? null : document.publishedAt().toString(),
                String.valueOf(document.metadata().getOrDefault("section", "公开资料")),
                text
        );
    }

    private StockAiAnalysisResponse persistAndCache(
            String symbol,
            String contextHash,
            String dataSnapshotHash,
            String cacheKey,
            StockAiAnalysisResponse response
    ) {
        StockAiAnalysisResponse enriched = reportPersistence.persist(
                symbol, contextHash, dataSnapshotHash, cacheKey, response
        );
        latestResponses.put(symbol, enriched);
        return enriched;
    }

    private StockAiAnalysisResponse fromReport(StockAnalysisReport report) {
        return new StockAiAnalysisResponse(
                report.rating(),
                report.summary(),
                report.positivePoints(),
                report.riskPoints(),
                report.confidence(),
                report.citations(),
                report.model(),
                report.source(),
                report.aiGenerated(),
                report.id(),
                report.generatedAt(),
                false,
                report.dataSnapshotHash(),
                report.reportVersion(),
                restoredGuidance(report)
        );
    }

    private StockAiAnalysisResponse fallback(
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
        List<String> positives = metrics.stream()
                .filter(metric -> List.of("ROE", "REVENUE_YOY", "OCF_NET_PROFIT").contains(metric.code()))
                .limit(3)
                .map(metric -> metric.name() + "为 " + metric.value())
                .toList();
        List<String> riskPoints = risks.stream()
                .map(RiskSignal::title)
                .limit(4)
                .toList();
        ResearchGuidance guidance = guidanceScorer.score(company, quote, metrics, risks, evidence, positives, riskPoints);
        int confidence = Math.max(55, Math.min(85,
                64 + Math.min(12, evidence.size() * 2) + Math.min(8, metrics.size())
                        - Math.min(12, risks.size() * 3) + (quote.realtime() ? 4 : 0)));
        return new StockAiAnalysisResponse(
                guidance.researchPriority(),
                company.name() + "当前处于“" + guidance.researchPriority() + "”状态。" + guidance.summary(),
                guidance.supportingEvidence(),
                guidance.invalidationSignals(),
                confidence,
                evidence.stream().map(EvidencePayload::title).limit(5).toList(),
                "rule-fallback",
                "fallback-rule",
                false,
                null,
                null,
                false,
                null,
                0,
                guidance
        );
    }

    private String contextHash(StockAiAnalysisRequest request) {
        StringBuilder builder = new StringBuilder(512);
        builder.append(GUIDANCE_VERSION).append('|')
                .append(request.company().symbol()).append('|')
                .append(request.company().name()).append('|')
                .append(request.quote().currentPrice()).append('|')
                .append(request.quote().changePercent()).append('|')
                .append(request.quote().tradeDate()).append('|')
                .append(request.quote().realtime());
        for (FinancialMetric metric : request.metrics()) {
            builder.append('|').append(metric.code()).append(':')
                    .append(metric.fiscalYear()).append(':').append(metric.value());
        }
        for (RiskSignal risk : request.risks()) {
            builder.append('|').append(risk.code()).append(':')
                    .append(risk.detectedAt()).append(':').append(risk.severity());
        }
        for (EvidencePayload item : request.evidence()) {
            builder.append('|').append(item.documentId()).append(':')
                    .append(item.title()).append(':').append(item.section());
        }
        return sha256(builder.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private ResearchGuidance restoredGuidance(StockAnalysisReport report) {
        return guidanceScorer.restoredFromLegacy(
                new StockAiAnalysisResponse(
                        report.rating(),
                        report.summary(),
                        report.positivePoints(),
                        report.riskPoints(),
                        report.confidence(),
                        report.citations(),
                        report.model(),
                        report.source(),
                        report.aiGenerated(),
                        report.id(),
                        report.generatedAt(),
                        false,
                        report.dataSnapshotHash(),
                        report.reportVersion(),
                        null
                )
        );
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8001";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record StockAiAnalysisRequest(
            Company company,
            MarketQuote quote,
            List<FinancialMetric> metrics,
            List<RiskSignal> risks,
            List<EvidencePayload> evidence
    ) {
    }

    public record EvidencePayload(
            String documentId,
            String title,
            String documentType,
            String publishedAt,
            String section,
            String text
    ) {
    }

    public record StockAiAnalysisResponse(
            String rating,
            String summary,
            List<String> positivePoints,
            List<String> riskPoints,
            int confidence,
            List<String> citations,
            String model,
            String source,
            boolean aiGenerated,
            String reportId,
            Instant generatedAt,
            boolean cacheHit,
            String dataSnapshotHash,
            int reportVersion,
            ResearchGuidance guidance
    ) {
        public StockAiAnalysisResponse withPersistence(String reportId, Instant generatedAt, boolean cacheHit) {
            return withPersistence(reportId, generatedAt, cacheHit, dataSnapshotHash, reportVersion);
        }

        public StockAiAnalysisResponse withPersistence(
                String reportId,
                Instant generatedAt,
                boolean cacheHit,
                String dataSnapshotHash,
                int reportVersion
        ) {
            return new StockAiAnalysisResponse(
                    rating,
                    summary,
                    positivePoints,
                    riskPoints,
                    confidence,
                    citations,
                    model,
                    source,
                    aiGenerated,
                    reportId,
                    generatedAt,
                    cacheHit,
                    dataSnapshotHash,
                    reportVersion,
                    guidance
            );
        }

        public StockAiAnalysisResponse withCacheHit() {
            return withPersistence(reportId, generatedAt, true);
        }

        public StockAiAnalysisResponse withGuidance(ResearchGuidance nextGuidance) {
            return new StockAiAnalysisResponse(
                    rating,
                    summary,
                    positivePoints,
                    riskPoints,
                    confidence,
                    citations,
                    model,
                    source,
                    aiGenerated,
                    reportId,
                    generatedAt,
                    cacheHit,
                    dataSnapshotHash,
                    reportVersion,
                    nextGuidance
            );
        }
    }

    public record ResearchGuidance(
            String researchPriority,
            int dataCompleteness,
            String summary,
            List<String> supportingEvidence,
            List<String> confirmationConditions,
            List<String> invalidationSignals,
            List<String> nextResearchActions
    ) {
    }
}
