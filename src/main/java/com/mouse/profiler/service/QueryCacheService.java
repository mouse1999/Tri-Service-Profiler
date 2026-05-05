package com.mouse.profiler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.mouse.profiler.dto.NewProfileResponseDto;
import com.mouse.profiler.entity.Profile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Result-set cache service providing a look-aside caching layer for profile queries.
 * Data is serialized as JSON to ensure the cache remains independent of the database schema
 * at runtime and can be served directly to the consumer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCacheService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final String KEY_PATTERN = "profiles:query:*";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Retrieves a cached query response.
     * Fails gracefully on Redis connection issues or deserialization errors.
     *
     * @param cacheKey The canonical query key.
     * @return Optional containing the cached result set.
     */
    public Optional<NewProfileResponseDto<Profile>> get(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) return Optional.empty();

            var type = objectMapper.getTypeFactory()
                    .constructParametricType(NewProfileResponseDto.class, Profile.class);

            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Cache read failure for key {}: {}. Falling back to source.", cacheKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persists a query result set to Redis with a 5-minute TTL.
     */
    public void put(String cacheKey, NewProfileResponseDto<Profile> result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, json, DEFAULT_TTL);
        } catch (JsonProcessingException e) {
            log.error("Serialization error for cache key {}: {}", cacheKey, e.getMessage());
        } catch (Exception e) {
            log.warn("Cache write failure for key {}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Invalidates all cached query results.
     * Typically triggered after write operations to prevent stale data.
     */
    public void evictAll() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PATTERN);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Successfully invalidated {} query cache entries.", keys.size());
            }
        } catch (Exception e) {
            log.error("Global cache eviction failed: {}", e.getMessage());
        }
    }
}