package com.mouse.profiler.service;

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
    static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Looks up a cached {@link QueryCriteria} for the given raw query string.
     *
     * @param rawQuery the trimmed, non-blank user query
     * @return the cached criteria if present, or empty on miss or error
     */
    public Optional<QueryCriteria> get(String rawQuery) {
        String key = toKey(rawQuery);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("Interpretation cache miss for: [{}]", rawQuery);
                return Optional.empty();
            }
            log.debug("Interpretation cache hit for: [{}]", rawQuery);
            return Optional.of(objectMapper.readValue(json, QueryCriteria.class));
        } catch (Exception e) {
            log.warn("Interpretation cache read error for [{}]: {}", rawQuery, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores an interpreted {@link QueryCriteria} against the raw query string.
     *
     * @param rawQuery the trimmed, non-blank user query
     * @param criteria the successfully interpreted criteria
     */
    public void put(String rawQuery, QueryCriteria criteria) {
        String key = toKey(rawQuery);
        try {
            String json = objectMapper.writeValueAsString(criteria);
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Interpretation cached for: [{}] (TTL={})", rawQuery, TTL);
        } catch (Exception e) {
            log.warn("Interpretation cache write error for [{}]: {}", rawQuery, e.getMessage());
        }
    }

    /**
     * Evicts a single entry from the interpretation cache.
     *
     * @param rawQuery the raw query string to evict
     */
    public void evict(String rawQuery) {
        String key = toKey(rawQuery);
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Interpretation cache evicted: [{}]", rawQuery);
            }
        } catch (Exception e) {
            log.warn("Interpretation cache evict error for [{}]: {}", rawQuery, e.getMessage());
        }
    }

    /**
     * Evicts all interpretation cache entries.
     *
     * <p>This is useful for testing or when the interpretation logic has changed.
     * Under normal operation, interpretation cache entries expire naturally after 1 hour.
     */
    public void evictAll() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redisTemplate.delete(keys);
                log.info("Interpretation cache evicted {} entries", deleted);
            } else {
                log.debug("Interpretation cache is empty, nothing to evict");
            }
        } catch (Exception e) {
            log.warn("Interpretation cache evictAll error: {}", e.getMessage());
        }
    }

    /**
     * Returns the approximate size of the interpretation cache.
     * Useful for monitoring and debugging.
     *
     * @return number of cached interpretation entries
     */
    public long size() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            return keys.isEmpty() ?  0: keys.size();
        } catch (Exception e) {
            log.warn("Interpretation cache size check error: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Produces a stable Redis key from the raw query.
     * Lowercase + trim ensures "Nigeria Males" and "nigeria males" share an entry.
     */
    private String toKey(String rawQuery) {
        return KEY_PREFIX + rawQuery.toLowerCase().trim();
    }
}