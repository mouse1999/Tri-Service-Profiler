package com.mouse.profiler.filter;

import com.mouse.profiler.base.BaseIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.*;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RequestLoggingFilter}.
 *
 * <p>These tests verify that the logging filter produces structured log output
 * for every request, including rejected requests (rate limited or version
 * rejected). The filter must run before all other filters so nothing escapes
 * the log.</p>
 *
 * <p>Uses {@link OutputCaptureExtension} from Spring Boot Test to capture
 * stdout and stderr during the test so we can assert on log output without
 * mocking the logger.</p>
 */
@DisplayName("Request Logging Filter — Integration Tests")
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterIntegrationTest extends BaseIntegrationTest {

    private static final String API_PROFILES_ENDPOINT = "/api/v1/profiles";
    private static final String VERSION_HEADER        = "X-API-Version";
    private static final String VERSION_VALUE         = "1";

    /**
     * Verifies that a normal request produces a log line with all four
     * required fields: method, endpoint, status, duration.
     */
    @Test
    @DisplayName("Every request produces a structured log line with method, endpoint, status, duration")
    void everyRequest_producesStructuredLogLine(CapturedOutput output) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(VERSION_HEADER, VERSION_VALUE);

        restTemplate.exchange(
                API_PROFILES_ENDPOINT,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(output.getAll())
                .as("Log must contain method field")
                .contains("method=GET");

        assertThat(output.getAll())
                .as("Log must contain endpoint field")
                .contains("endpoint=" + API_PROFILES_ENDPOINT);

        assertThat(output.getAll())
                .as("Log must contain status field")
                .contains("status=");

        assertThat(output.getAll())
                .as("Log must contain duration field in milliseconds")
                .contains("duration=")
                .contains("ms");
    }

    /**
     * Verifies that even requests rejected by the version interceptor (400)
     * are still logged. The logging filter runs at Order(1) — before everything
     * else — so rejections must appear in the log.
     */
    @Test
    @DisplayName("Requests rejected with 400 are still logged")
    void rejectedRequest_400_isStillLogged(CapturedOutput output) {
        // Fire a request with no version header — will be rejected with 400
        restTemplate.getForEntity(API_PROFILES_ENDPOINT, String.class);

        assertThat(output.getAll())
                .as("Rejected 400 request must still appear in the request log")
                .contains("status=400");
    }

    /**
     * Verifies that rate-limited requests (429) are also logged.
     * Important for security auditing — we need to see flood attempts.
     */
    @Test
    @Disabled()
    @DisplayName("Rate limited requests returning 429 are still logged")
    void rateLimitedRequest_429_isStillLogged(CapturedOutput output) {
        // Exhaust auth limit
        for (int i = 0; i < 10; i++) {
            restTemplate.postForEntity("/auth/login", null, String.class);
        }

        // Fire the blocking request
        restTemplate.postForEntity("/auth/login", null, String.class);

        assertThat(output.getAll())
                .as("Rate limited 429 request must appear in the log")
                .contains("status=429");
    }
}
