package com.mouse.profiler.base;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    @Container
    static final RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withStartupTimeout(Duration.ofSeconds(90))
            .withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Disable rate limiting conditionally if Redis fails
        registry.add("rate.limiting.enabled", () -> true);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired(required = false)
    protected RedisClient redisClient;

    @AfterEach
    void flushRedis() {
        if (redisClient != null) {
            try (var connection = redisClient.connect()) {
                connection.sync().flushall();
            } catch (Exception e) {
                System.err.println("Redis flush failed (this is ok in some tests): " + e.getMessage());
            }
        }
    }
}