package com.mouse.profiler.config;


import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.bucket4j.BucketConfiguration;

import java.util.function.Supplier;

/**
 * Defines the Bucket4j rate limit configurations and the Redis-backed
 * ProxyManager that stores bucket state in Redis.
 *
 * <p>Bucket4j works using the "token bucket" algorithm:</p>
 * <ul>
 *   <li>Each user or IP gets a "bucket" that starts full of tokens.</li>
 *   <li>Every request consumes one token.</li>
 *   <li>Tokens refill at a steady rate (e.g. 10 per minute).</li>
 *   <li>When the bucket is empty, the next request gets a 429 response.</li>
 * </ul>
 *
 * <p>The {@code ProxyManager} is a factory. Instead of creating one bucket
 * for the whole system, it creates one bucket per key (e.g. per IP address
 * or per user ID). Each bucket is stored as a separate entry in Redis.</p>
 */
@Configuration
public class Bucket4jConfig {

    @Value("${rate.limit.auth.requests}")
    private int authRequests;

    @Value("${rate.limit.auth.duration.minutes}")
    private int authDurationMinutes;

    @Value("${rate.limit.api.requests}")
    private int apiRequests;

    @Value("${rate.limit.api.duration.minutes}")
    private int apiDurationMinutes;

    /**
     * Creates the Redis-backed {@link ProxyManager} used to store and
     * retrieve per-key bucket state from Redis.
     *
     * <p>A ProxyManager is a factory — you give it a key (like an IP address
     * or user ID as a byte array) and it either loads that bucket from Redis
     * or creates a new one using the configuration you supply.</p>
     *
     * @param redisClient the Lettuce client injected from {@link RedisConfig}
     * @return a ProxyManager that creates/loads per-key buckets from Redis
     */
    @Bean
    public ProxyManager<String> lettuceProxyManager(RedisClient redisClient) {
        return null; // TODO
    }

    /**
     * Defines the bucket configuration for authentication routes
     * ({@code /auth/**}).
     *
     * <p>Auth routes get a stricter limit because they are the most
     * vulnerable to brute-force attacks. 10 requests per minute is
     * enough for a legitimate user (login, refresh, logout) but painful
     * for an attacker trying thousands of passwords.</p>
     * @return a Supplier that produces a BucketConfiguration for auth routes
     */
    @Bean
    public Supplier<BucketConfiguration> authBucketConfiguration() {
        return null; // TODO
    }

    /**
     * Defines the bucket configuration for standard API routes
     * (everything that is not {@code /auth/**}).
     *
     * <p>60 requests per minute is generous for legitimate API usage
     * from the CLI or web portal, but still prevents abuse such as
     * bulk scraping or denial-of-service flooding.</p>
     * @return a Supplier that produces a BucketConfiguration for API routes
     */
    @Bean
    public Supplier<BucketConfiguration> apiBucketConfiguration() {
        return null; // TODO
    }
}