package com.finsight.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.application.StockAiAnalysisService;
import com.finsight.application.StockAnalysisCache;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisStockAnalysisCache implements StockAnalysisCache {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStockAnalysisCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StockAiAnalysisService.StockAiAnalysisResponse> get(String key) {
        String raw = redisTemplate.opsForValue().get(redisKey(key));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, StockAiAnalysisService.StockAiAnalysisResponse.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, StockAiAnalysisService.StockAiAnalysisResponse response, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(response), ttl);
        } catch (JsonProcessingException ignored) {
            // Cache failures should not block the analysis flow.
        }
    }

    @Override
    public Optional<byte[]> getBinary(String key) {
        String raw = redisTemplate.opsForValue().get(binaryRedisKey(key));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getDecoder().decode(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void putBinary(String key, byte[] value, Duration ttl) {
        redisTemplate.opsForValue().set(binaryRedisKey(key), Base64.getEncoder().encodeToString(value), ttl);
    }

    private String redisKey(String key) {
        return "finsight:stock-analysis:" + key;
    }

    private String binaryRedisKey(String key) {
        return "finsight:stock-analysis-binary:" + key;
    }
}
