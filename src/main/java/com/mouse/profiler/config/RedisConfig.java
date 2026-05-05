package com.mouse.profiler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

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

    @Value("${spring.data.redis.timeout:60s}")
    private Duration timeout;

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
    public RedisClient redisClient() {
        DefaultClientResources clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();

        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withDatabase(redisDatabase)
                .withTimeout(timeout);

        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }

        RedisURI redisUri = uriBuilder.build();
        return RedisClient.create(clientResources, redisUri);
    }


    /**
     * StringRedisTemplate operates on String keys and values, which is all we need —
     * query results are serialised to JSON strings before storage.
     *
     * <p>Spring Boot auto-configures the underlying {@link RedisConnectionFactory} from
     * {@code spring.data.redis.*} properties, so we only need to declare the template bean.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }


    /**
     * Shared ObjectMapper with Java time support.
     * Registered as a bean so it is reused across the application (e.g., in QueryCacheService).
     * Disabling WRITE_DATES_AS_TIMESTAMPS ensures ISO-8601 strings in JSON, not epoch longs.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}