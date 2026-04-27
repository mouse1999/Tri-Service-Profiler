package com.mouse.profiler.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Redis connection using the Lettuce client.
 *
 * Lettuce is the async Redis client that Bucket4j uses to store
 * rate limit counters in Redis. Storing counters in Redis (rather than
 * in-memory) means rate limits survive server restarts and work correctly
 * if you ever run multiple instances of this backend.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    /**
     * Creates and exposes a Lettuce {@link RedisClient} as a Spring bean.
     *
     * This bean is injected into {@code Bucket4jConfig} where it is used
     * to create the Redis-backed proxy that Bucket4j reads and writes
     * rate limit counters through.
     * @return a configured Lettuce RedisClient ready to accept connections
     */
    @Bean
    public RedisClient redisClient() {
        return null; // TODO
    }
}
