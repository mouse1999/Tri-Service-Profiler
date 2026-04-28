package com.mouse.profiler.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.bucket4j.BucketConfiguration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
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
 * <p><b>Duration resolution strategy:</b></p>
 * <p>Both auth and API buckets support two duration properties:</p>
 * <ul>
 *   <li>{@code rate.limit.*.duration.seconds} — takes priority when > 0.
 *       Used by the test profile to compress windows to 5 seconds so
 *       tests do not wait a full minute for buckets to refill.</li>
 *   <li>{@code rate.limit.*.duration.minutes} — used when seconds is 0
 *       or not set. This is the normal dev and prod value (1 minute).</li>
 * </ul>
 *
 * <p>Resolution logic in plain English:</p>
 * <pre>
 *   if (seconds > 0)  → use seconds (test profile)
 *   else → use minutes (dev and prod profiles)
 * </pre>
 *
 * <p>This means the test profile sets:</p>
 * <pre>
 *   rate.limit.auth.duration.minutes=0
 *   rate.limit.auth.duration.seconds=5
 * </pre>
 * <p>And dev/prod set only:</p>
 * <pre>
 *   rate.limit.auth.duration.minutes=1
 *   (seconds defaults to 0 and is ignored)
 * </pre>
 */
@Configuration
public class Bucket4jConfig {

    // ─────────────────────────────────────────────────────────────────
    // AUTH BUCKET PROPERTIES
    // ─────────────────────────────────────────────────────────────────

    @Value("${rate.limit.auth.requests}")
    private int authRequests;

    /**
     * Auth window in minutes. Used in dev and prod.
     * Defaults to 1 if not set so the app starts safely
     * even if the property is accidentally omitted.
     */
    @Value("${rate.limit.auth.duration.minutes:1}")
    private int authDurationMinutes;

    /**
     * Auth window in seconds. Used in the test profile only.
     * Defaults to 0 meaning "not set — fall back to minutes".
     * When this is > 0 it takes priority over authDurationMinutes.
     */
    @Value("${rate.limit.auth.duration.seconds:0}")
    private int authDurationSeconds;

    // ─────────────────────────────────────────────────────────────────
    // API BUCKET PROPERTIES
    // ─────────────────────────────────────────────────────────────────

    @Value("${rate.limit.api.requests}")
    private int apiRequests;

    /**
     * API window in minutes. Used in dev and prod.
     * Defaults to 1 if not set.
     */
    @Value("${rate.limit.api.duration.minutes:1}")
    private int apiDurationMinutes;

    /**
     * API window in seconds. Used in the test profile only.
     * Defaults to 0 meaning "not set — fall back to minutes".
     * When this is > 0 it takes priority over apiDurationMinutes.
     */
    @Value("${rate.limit.api.duration.seconds:0}")
    private int apiDurationSeconds;

    // ─────────────────────────────────────────────────────────────────
    // PROXY MANAGER
    // ─────────────────────────────────────────────────────────────────

    /**
     * Creates the Redis-backed {@link ProxyManager} used to store and
     * retrieve per-key bucket state from Redis.
     *
     * <p>A ProxyManager is a factory — you give it a key (like an IP address
     * or user ID as a string) and it either loads that bucket from Redis
     * or creates a new one using the configuration you supply.</p>
     *
     * @param redisClient the Lettuce client injected from {@link RedisConfig}
     * @return a ProxyManager that creates/loads per-key buckets from Redis
     */
    @Bean
    @Lazy  // Very important for tests
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = true)
    public ProxyManager<String> lettuceProxyManager(RedisClient redisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        StatefulRedisConnection<String, byte[]> connection = redisClient.connect(codec);
        return LettuceBasedProxyManager.builderFor(connection).build();
    }

    // ─────────────────────────────────────────────────────────────────
    // BUCKET CONFIGURATIONS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Defines the bucket configuration for authentication routes
     * ({@code /auth/**}).
     *
     * <p>Auth routes get a stricter limit because they are the most
     * vulnerable to brute-force attacks. 10 requests per minute is
     * enough for a legitimate user (login, refresh, logout) but painful
     * for an attacker trying thousands of passwords.</p>
     *
     * <p>The duration is resolved by {@link #resolveAuthDuration()}
     * which checks seconds first then falls back to minutes.</p>
     *
     * @return a Supplier that produces a BucketConfiguration for auth routes
     */
    @Bean
    public Supplier<BucketConfiguration> authBucketConfiguration() {
        return () -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(authRequests)
                    .refillGreedy(authRequests, resolveAuthDuration())
                    .build();

            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        };
    }

    /**
     * Defines the bucket configuration for standard API routes
     * (everything that is not {@code /auth/**}).
     *
     * <p>60 requests per minute is generous for legitimate API usage
     * from the CLI or web portal, but still prevents abuse such as
     * bulk scraping or denial-of-service flooding.</p>
     *
     * <p>The duration is resolved by {@link #resolveApiDuration()}
     * which checks seconds first then falls back to minutes.</p>
     *
     * @return a Supplier that produces a BucketConfiguration for API routes
     */
    @Bean
    public Supplier<BucketConfiguration> apiBucketConfiguration() {
        return () -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(apiRequests)
                    .refillGreedy(apiRequests, resolveApiDuration())
                    .build();

            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // DURATION RESOLVERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Resolves the refill duration for the auth bucket.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>If {@code rate.limit.auth.duration.seconds} is greater than 0,
     *       return a {@link Duration} of that many seconds. This is the test
     *       profile path — tests set seconds=5 so buckets reset in 5 seconds
     *       instead of waiting a full minute.</li>
     *   <li>Otherwise return a {@link Duration} of
     *       {@code rate.limit.auth.duration.minutes} minutes. This is the
     *       normal dev and prod path.</li>
     * </ol>
     *
     * <p>Example resolutions per profile:</p>
     * <pre>
     *   dev  profile: minutes=1,  seconds=0 → Duration.ofMinutes(1)
     *   prod profile: minutes=1,  seconds=0 → Duration.ofMinutes(1)
     *   test profile: minutes=0,  seconds=5 → Duration.ofSeconds(5)
     * </pre>
     *
     * @return the Duration to use for the auth bucket refill window
     */
    private Duration resolveAuthDuration() {
        if (authDurationSeconds > 0) {
            return Duration.ofSeconds(authDurationSeconds);
        }
        return Duration.ofMinutes(authDurationMinutes);
    }

    /**
     * Resolves the refill duration for the API bucket.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>If {@code rate.limit.api.duration.seconds} is greater than 0,
     *       return a {@link Duration} of that many seconds. This is the test
     *       profile path — tests set seconds=5 so buckets reset in 5 seconds
     *       instead of waiting a full minute.</li>
     *   <li>Otherwise return a {@link Duration} of
     *       {@code rate.limit.api.duration.minutes} minutes. This is the
     *       normal dev and prod path.</li>
     * </ol>
     *
     * <p>Example resolutions per profile:</p>
     * <pre>
     *   dev  profile: minutes=1,  seconds=0 → Duration.ofMinutes(1)
     *   prod profile: minutes=1,  seconds=0 → Duration.ofMinutes(1)
     *   test profile: minutes=0,  seconds=5 → Duration.ofSeconds(5)
     * </pre>
     *
     * @return the Duration to use for the API bucket refill window
     */
    private Duration resolveApiDuration() {
        if (apiDurationSeconds > 0) {
            return Duration.ofSeconds(apiDurationSeconds);
        }
        return Duration.ofMinutes(apiDurationMinutes);
    }
}