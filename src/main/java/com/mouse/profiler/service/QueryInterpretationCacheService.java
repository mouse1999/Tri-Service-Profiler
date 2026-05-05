package com.mouse.profiler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.QueryCriteria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Layer 1 Cache Service responsible for mapping raw Natural Language Queries (NLQ)
 * to their structured {@link QueryCriteria} interpretations.
 *
 * This cache prevents redundant processing of identical query strings, reducing
 * latency and compute costs associated with the interpretation engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryInterpretationCacheService {

    private static final String KEY_PREFIX = "nlq:interpret:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Retrieves the interpreted criteria for a given raw query string.
     *
     * @param rawQuery The unparsed user input.
     * @return Optional containing the cached criteria, or empty if not found/invalid.
     */
    public Optional<QueryCriteria> get(String rawQuery) {
        String key = buildKey(rawQuery);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();

            return Optional.of(objectMapper.readValue(json, QueryCriteria.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached criteria for query: {}", rawQuery, e);
            return Optional.empty();
        }
    }

    /**
     * Persists an interpreted query to the cache.
     *
     * @param rawQuery The original user input string.
     * @param criteria The structured interpretation result.
     */
    public void put(String rawQuery, QueryCriteria criteria) {
        String key = buildKey(rawQuery);
        try {
            String json = objectMapper.writeValueAsString(criteria);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize criteria for query: {}", rawQuery, e);
        }
    }

    /**
     * Removes a specific query interpretation from the cache.
     */
    public void evict(String rawQuery) {
        redisTemplate.delete(buildKey(rawQuery));
    }

    /**
     * Flushes all cached NLQ interpretations.
     * Generally used during system maintenance or algorithm updates.
     */
    public void evictAll() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Evicted {} entries from interpretation cache.", keys.size());
        }
    }

    /**
     * Returns the total count of cached interpretations.
     */
    public long size() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }

    private String buildKey(String rawQuery) {
        return KEY_PREFIX + rawQuery.toLowerCase().trim();
    }
}