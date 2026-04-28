package com.mouse.profiler.base;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Abstract base class for all integration tests in this project.
 *
 * <p><b>Why @DynamicPropertySource instead of @ServiceConnection:</b></p>
 * <p>{@code @ServiceConnection} was introduced in Spring Boot 3.1 and requires
 * the {@code spring-boot-testcontainers} dependency. {@code @DynamicPropertySource}
 * is the universal approach that works with any Spring Boot 3.x version and
 * requires no extra dependencies beyond the core Testcontainers modules.</p>
 *
 * <p>How {@code @DynamicPropertySource} works:</p>
 * <ol>
 *   <li>Testcontainers starts the Redis container on a <b>random available port</b>
 *       on your machine — no port conflicts ever.</li>
 *   <li>Spring calls the {@code @DynamicPropertySource} method BEFORE it builds
 *       the application context.</li>
 *   <li>The method reads the actual host and port from the running container
 *       and writes them into the Spring {@code Environment} as
 *       {@code spring.data.redis.host} and {@code spring.data.redis.port}.</li>
 *   <li>Spring then builds the {@code RedisConnectionFactory} using those
 *       injected values — pointing at the Testcontainers Redis, not any
 *       locally installed Redis.</li>
 * </ol>
 *
 * <p><b>Singleton Container Pattern:</b></p>
 * <p>The {@code static} field combined with the {@code static} initializer
 * block means one Redis container starts for the entire JVM session.
 * Every subclass that extends this base shares the same container instance.
 * Testcontainers will not start a second container per test class.
 * This is dramatically faster than per-class container lifecycle.</p>
 *
 * <p><b>State isolation without @DirtiesContext:</b></p>
 * <p>The {@code @AfterEach} method calls {@code FLUSHALL} on Redis after
 * every test method. This resets all Bucket4j token buckets to their initial
 * state in under 1ms. {@code @DirtiesContext} would restart the entire Spring
 * context which takes 5-15 seconds per test class — never use it for this.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class BaseIntegrationTest {

    /**
     * The shared Redis container.
     *
     * <p>{@code static} — shared across ALL subclasses (Singleton Pattern).
     * {@code @Container} — Testcontainers manages its start/stop lifecycle.
     * The container is started in the static block below, not by the
     * {@code @Container} annotation, because static {@code @Container} fields
     * are only managed automatically when declared on a concrete test class.
     * Since this is an abstract base class, we manage the start manually.</p>
     */
    @Container
    static final RedisContainer redis =
            new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));

    /**
     * Start the container exactly once for the entire test suite.
     *
     * <p>This static block runs when the JVM first loads this class — before
     * any test method in any subclass executes. Subsequent subclasses that
     * extend {@code BaseIntegrationTest} will find the container already
     * running and reuse it.</p>
     *
     * <p>Testcontainers tracks the container and registers a JVM shutdown
     * hook to stop it automatically when the test suite completes.</p>
     */
    static {
        redis.start();
    }

    /**
     * Injects the randomly assigned Redis host and port from the running
     * Testcontainers instance into Spring's {@code Environment} BEFORE
     * the application context is created.
     *
     * <p>This is the replacement for {@code @ServiceConnection}. It gives
     * you full manual control over exactly which Spring properties are set
     * and from which container values they are read.</p>
     *
     * <p>Why {@code static}? Spring calls this method before instantiating
     * any beans, so it must be a static method. The registry is populated
     * once and shared across the entire context for this test run.</p>
     *
     * <p>What {@code redis.getHost()} returns: the Docker host reachable
     * from your machine — typically {@code localhost} locally and the
     * Docker bridge IP on CI.</p>
     *
     * <p>What {@code redis.getMappedPort(6379)} returns: the random host
     * port that Docker mapped to the container's internal port 6379 —
     * e.g. {@code 54321}. This is why there are never port conflicts.</p>
     *
     * @param registry the Spring property registry to write values into
     */
    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    /**
     * TestRestTemplate pre-configured by Spring Boot to point at the
     * random port chosen for this test run's embedded server.
     *
     * <p>This is not a mock — it fires real HTTP requests through the full
     * filter chain, interceptors, and controllers. Every subclass inherits
     * this field and uses it to send requests in tests.</p>
     */
    @Autowired
    protected TestRestTemplate restTemplate;

    /**
     * The application's Lettuce RedisClient bean.
     *
     * <p>Injected here so persistence tests can inspect Redis state directly
     * after HTTP requests — verifying that Bucket4j is writing bucket state
     * to Redis rather than keeping it in memory.</p>
     */
    @Autowired
    protected RedisClient redisClient;

    /**
     * Flushes ALL keys from Redis after every single test method.
     *
     * <p><b>Why FLUSHALL:</b> Bucket4j writes one Redis key per unique
     * rate limit bucket (one per IP per route type). After a test that
     * exhausts a bucket, that key remains in Redis with zero tokens.
     * If the next test does not flush it, the next test starts with an
     * empty bucket and immediately gets 429s — a false failure caused by
     * test pollution, not a real bug.</p>
     *
     * <p><b>Why not @DirtiesContext:</b> Restarting the Spring context
     * after every test takes 5-15 seconds. FLUSHALL takes under 1ms.
     * For rate limit state, flushing Redis is all the cleanup needed.</p>
     *
     * <p><b>Safety:</b> The Redis container is dedicated to this test suite.
     * There is no production data or shared state at risk. FLUSHALL is
     * safe here in a way it would never be against a real Redis instance.</p>
     */
    @AfterEach
    void flushRedis() {
        try (var connection = redisClient.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            commands.flushall();
        }
    }
}