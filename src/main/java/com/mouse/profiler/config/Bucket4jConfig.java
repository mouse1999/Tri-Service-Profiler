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

@Configuration
public class Bucket4jConfig {

    // AUTH BUCKET PROPERTIES
    @Value("${rate.limit.auth.requests:10}")
    private int authRequests;

    @Value("${rate.limit.auth.duration.minutes:1}")
    private int authDurationMinutes;

    @Value("${rate.limit.auth.duration.seconds:0}")
    private int authDurationSeconds;

    // API BUCKET PROPERTIES
    @Value("${rate.limit.api.requests:60}")
    private int apiRequests;

    @Value("${rate.limit.api.duration.minutes:1}")
    private int apiDurationMinutes;

    @Value("${rate.limit.api.duration.seconds:0}")
    private int apiDurationSeconds;

    // PROXY MANAGER
    @Bean
    @Lazy
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
    public ProxyManager<String> lettuceProxyManager(RedisClient redisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        StatefulRedisConnection<String, byte[]> connection = redisClient.connect(codec);
        return LettuceBasedProxyManager.builderFor(connection).build();
    }

    // BUCKET CONFIGURATIONS
    @Bean
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
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

    @Bean
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
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

    // DURATION RESOLVERS
    private Duration resolveAuthDuration() {
        if (authDurationSeconds > 0) {
            return Duration.ofSeconds(authDurationSeconds);
        }
        return Duration.ofMinutes(authDurationMinutes);
    }

    private Duration resolveApiDuration() {
        if (apiDurationSeconds > 0) {
            return Duration.ofSeconds(apiDurationSeconds);
        }
        return Duration.ofMinutes(apiDurationMinutes);
    }
}