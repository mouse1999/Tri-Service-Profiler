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
import org.testcontainers.containers.wait.strategy.Wait;
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
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(90))
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));


    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        // These will be set BEFORE Spring context loads
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("rate.limiting.enabled", () -> "true");
    }

    @Autowired(required = false)
    protected TestRestTemplate restTemplate;

    @Autowired(required = false)
    protected RedisClient redisClient;

    @AfterEach
    void flushRedis() {
        if (redisClient != null) {
            try (var connection = redisClient.connect()) {
                connection.sync().flushall();
            } catch (Exception e) {
                // Ignore flush errors in tests
            }
        }
    }
}