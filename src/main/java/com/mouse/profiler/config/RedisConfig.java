package com.mouse.profiler.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    @Value("${spring.data.redis.timeout:30s}")
    private Duration timeout;

    /**
     * Creates and exposes a Lettuce {@link RedisClient} as a Spring bean.
     *
     * This bean is injected into {@code Bucket4jConfig} where it is used
     * to create the Redis-backed proxy that Bucket4j reads and writes
     * rate limit counters through.
     *
     * <p>This bean is only created when rate limiting is enabled,
     * preventing unnecessary Redis connections in development.</p>
     *
     * @return a configured Lettuce RedisClient ready to accept connections
     */
    @Bean
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
    public RedisClient redisClient() {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withDatabase(redisDatabase)
                .withTimeout(timeout);

        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }

        RedisURI redisUri = uriBuilder.build();
        return RedisClient.create(redisUri);
    }
}