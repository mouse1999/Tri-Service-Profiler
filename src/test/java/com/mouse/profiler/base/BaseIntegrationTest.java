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

    //Set system properties BEFORE Spring context loads
    static {
        // Force container to start and get the dynamic port
        redis.start();

        String host = redis.getHost();
        int port = redis.getMappedPort(6379);

        // Set system properties that Spring will read
        System.setProperty("spring.data.redis.host", host);
        System.setProperty("spring.data.redis.port", String.valueOf(port));
        System.setProperty("spring.data.redis.timeout", "60s");
        System.setProperty("rate.limiting.enabled", "true");

        System.out.println("=========================================");
        System.out.println("🔴 REDIS PORT MAPPING");
        System.out.println("  Container port: 6379 → Host port: " + port);
        System.out.println("  Connection: redis://" + host + ":" + port);
        System.out.println("=========================================");

        // Verify connection works
        try {
            RedisClient testClient = RedisClient.create("redis://" + host + ":" + port);
            var conn = testClient.connect();
            String pong = conn.sync().ping();
            System.out.println("✅ Redis PING successful: " + pong);
            conn.close();
            testClient.shutdown();
        } catch (Exception e) {
            System.err.println("❌ Redis connection failed: " + e.getMessage());
        }
    }

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        // Override again to be sure
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "60s");
        registry.add("rate.limiting.enabled", () -> "true");
        registry.add("rate.limit.auth.duration.seconds", () -> "5");
        registry.add("rate.limit.auth.duration.minutes", () -> "0");
        registry.add("rate.limit.api.duration.seconds", () -> "5");
        registry.add("rate.limit.api.duration.minutes", () -> "0");
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
                System.out.println("✅ Redis flushed");
            } catch (Exception e) {
                System.err.println("❌ Redis flush failed: " + e.getMessage());
            }
        }
    }
}