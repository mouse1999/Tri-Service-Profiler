package com.mouse.profiler.base;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Container
    static final RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withStartupTimeout(Duration.ofSeconds(90))
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected RedisClient redisClient;

    @AfterEach
    void flushRedis() {
        try (var connection = redisClient.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            commands.flushall();
        } catch (Exception e) {
            System.err.println("Warning: Failed to flush Redis - " + e.getMessage());
            // Don't fail the test just because cleanup failed
        }
    }
}