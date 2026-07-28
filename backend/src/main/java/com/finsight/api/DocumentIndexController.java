package com.finsight.api;

import com.finsight.application.DocumentIndexingService;
import com.finsight.domain.model.EvidenceChunk;
import com.finsight.rag.HybridRetrievalGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/document-index")
public class DocumentIndexController {
    private final DocumentIndexingService documentIndexingService;
    private final HybridRetrievalGateway retrievalGateway;

    public DocumentIndexController(
            DocumentIndexingService documentIndexingService,
            HybridRetrievalGateway retrievalGateway
    ) {
        this.documentIndexingService = documentIndexingService;
        this.retrievalGateway = retrievalGateway;
    }

    @PostMapping("/{companySymbol}/rebuild")
    public DocumentIndexingService.IndexingResult rebuild(@PathVariable String companySymbol) {
        return documentIndexingService.indexCompany(companySymbol);
    }

    @GetMapping("/{companySymbol}/count")
    public ChunkCount count(@PathVariable String companySymbol) {
        return new ChunkCount(companySymbol, documentIndexingService.countChunks(companySymbol));
    }

    @GetMapping("/{companySymbol}/search")
    public List<EvidenceChunk> search(
            @PathVariable String companySymbol,
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return searchEvidence(companySymbol, q, limit);
    }

    @GetMapping("/search")
    public List<EvidenceChunk> searchAll(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return searchEvidence(null, q, limit);
    }

    private List<EvidenceChunk> searchEvidence(String companySymbol, String query, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 12);
        return retrievalGateway.search(companySymbol, query, boundedLimit).stream()
                .map(hit -> new EvidenceChunk(
                        hit.chunk().documentId(),
                        hit.chunk().title(),
                        hit.chunk().documentType(),
                        hit.chunk().publishedAt(),
                        hit.chunk().section() + " / " + hit.channel(),
                        hit.chunk().text(),
                        hit.score()
                ))
                .toList();
    }

    public record ChunkCount(String companySymbol, long count) {
    }
}
