package com.finsight.rag;

import com.finsight.domain.model.DocumentChunk;
import com.finsight.domain.repository.DocumentChunkRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HybridRetrievalGateway {
    private static final double RRF_K = 60.0;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    public HybridRetrievalGateway(DocumentChunkRepository chunkRepository, EmbeddingService embeddingService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
    }

    public List<RetrievalHit> search(String companySymbol, String question, int limit) {
        Map<String, FusionCandidate> candidates = new LinkedHashMap<>();
        List<DocumentChunk> keywordChunks = chunkRepository.keywordSearch(companySymbol, question, limit);
        for (int i = 0; i < keywordChunks.size(); i++) {
            addRank(candidates, keywordChunks.get(i), "keyword", i + 1);
        }

        List<Double> queryEmbedding = embeddingService.embed(question);
        List<DocumentChunk> vectorChunks = chunkRepository.vectorSearch(companySymbol, queryEmbedding, limit);
        for (int i = 0; i < vectorChunks.size(); i++) {
            addRank(candidates, vectorChunks.get(i), "vector", i + 1);
        }

        return candidates.values().stream()
                .map(candidate -> new RetrievalHit(
                        candidate.chunk(),
                        candidate.ranks().values().stream()
                                .mapToDouble(rank -> 1.0 / (RRF_K + rank))
                                .sum(),
                        String.join("+", candidate.ranks().keySet()),
                        Map.copyOf(candidate.ranks())
                ))
                .sorted(Comparator.comparingDouble(RetrievalHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private void addRank(
            Map<String, FusionCandidate> candidates,
            DocumentChunk chunk,
            String channel,
            int rank
    ) {
        candidates.computeIfAbsent(
                chunk.id(),
                ignored -> new FusionCandidate(chunk, new LinkedHashMap<>())
        ).ranks().put(channel, rank);
    }

    private record FusionCandidate(DocumentChunk chunk, Map<String, Integer> ranks) {
    }
}
