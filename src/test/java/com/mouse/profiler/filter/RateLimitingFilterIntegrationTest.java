package com.mouse.profiler.filter;

import com.mouse.profiler.base.BaseIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RateLimitingFilter}.
 *
 * <p>These tests fire real HTTP requests in rapid succession and verify that
 * Bucket4j correctly tracks and enforces the rate limits configured in
 * {@code application-test.properties}.</p>
 *
 * <p><b>Test environment rate limits (from application-test.properties):</b></p>
 * <ul>
 *   <li>Auth routes ({@code /auth/**}): 10 requests per 5 seconds</li>
 *   <li>API routes ({@code /api/**}): 60 requests per 5 seconds</li>
 * </ul>
 *
 * <p><b>State isolation:</b> {@code BaseIntegrationTest.flushRedis()} clears
 * all Bucket4j state from Redis after every test method, ensuring each test
 * starts with full buckets.</p>
 */
@TestPropertySource(properties = {
        "rate.limiting.enabled=true",
        "rate.limit.auth.requests=10",
        "rate.limit.auth.duration.seconds=5",
        "rate.limit.api.requests=60",
        "rate.limit.api.duration.seconds=5"
})
public class RateLimitingFilterIntegrationTest extends BaseIntegrationTest {

    private static final String API_PROFILES_ENDPOINT = "/api/v1/profiles";
    private static final String AUTH_LOGIN_ENDPOINT = "/auth/login";
    private static final String CORRECT_VERSION_HEADER = "X-API-Version";
    private static final String CORRECT_VERSION_VALUE = "1";

    private static final int AUTH_RATE_LIMIT = 10;
    private static final int API_RATE_LIMIT = 60;

    /**
     * Fires {@code count} sequential GET requests to the API endpoint.
     *
     * @param count how many requests to fire
     * @return ordered list of HTTP status codes, one per request
     */
    private List<HttpStatus> fireApiRequests(int count) {
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            HttpHeaders headers = createHeaders();

            ResponseEntity<String> response = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            statuses.add((HttpStatus) response.getStatusCode());
        }

        return statuses;
    }

    /**
     * Fires {@code count} sequential POST requests to the auth endpoint.
     *
     * @param count how many requests to fire
     * @return ordered list of HTTP status codes, one per request
     */
    private List<HttpStatus> fireAuthRequests(int count) {
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            HttpHeaders headers = createHeaders();

            // Create a simple login request body (your auth endpoint might expect this)
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            statuses.add((HttpStatus) response.getStatusCode());
        }

        return statuses;
    }

    /**
     * Creates HTTP headers with the required API version.
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Nested
    @DisplayName("When requests are within the rate limit")
    class WithinRateLimit {

        @Test
        @DisplayName("10 requests to /auth/** within limit are never rate limited")
        void authEndpoint_tenRequests_noneBlocked() {
            List<HttpStatus> statuses = fireAuthRequests(AUTH_RATE_LIMIT);

            assertThat(statuses)
                    .as("None of the first %d requests should be rate limited", AUTH_RATE_LIMIT)
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("5 requests to /api/v1/profiles within limit are never rate limited")
        void apiEndpoint_fiveRequests_noneBlocked() {
            List<HttpStatus> statuses = fireApiRequests(5);

            assertThat(statuses)
                    .as("5 requests well within limit should never receive 429")
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Nested
    @DisplayName("When requests exceed the rate limit")
    class ExceedingRateLimit {

        @Test
        @DisplayName("11th request to /auth/** returns 429 Too Many Requests")
        void authEndpoint_eleventhRequest_isRateLimited() {
            // Exhaust the auth bucket completely
            fireAuthRequests(AUTH_RATE_LIMIT);

            // The very next request must be blocked
            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> blockedResponse = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            assertThat(blockedResponse.getStatusCode())
                    .as("11th request to auth endpoint must return 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("61st request to /api/v1/profiles returns 429 Too Many Requests")
        void apiEndpoint_sixtyFirstRequest_isRateLimited() {
            // Exhaust the API bucket completely
            fireApiRequests(API_RATE_LIMIT);

            // The very next request must be blocked
            HttpHeaders headers = createHeaders();

            ResponseEntity<String> blockedResponse = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            assertThat(blockedResponse.getStatusCode())
                    .as("61st request to API endpoint must return 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("Auth rate limit boundary: requests 1-10 pass, request 11 fails")
        void authEndpoint_exactBoundary() {
            List<HttpStatus> statuses = fireAuthRequests(AUTH_RATE_LIMIT + 1);

            // First 10 must all pass the rate limiter
            List<HttpStatus> first10 = statuses.subList(0, AUTH_RATE_LIMIT);
            assertThat(first10)
                    .as("Requests 1-10 must not be rate limited")
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);

            // The 11th must be 429
            HttpStatus eleventhStatus = statuses.get(AUTH_RATE_LIMIT);
            assertThat(eleventhStatus)
                    .as("Request 11 must be rate limited with 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @Disabled()
        @DisplayName("429 response body contains structured JSON error body")
        void rateLimited_responseBody_hasCorrectShape() {
            // Exhaust auth bucket
            fireAuthRequests(AUTH_RATE_LIMIT);

            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> blocked = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            String body = blocked.getBody();

            // Your ErrorResponseDTO has "status" and "error" fields
            assertThat(body).as("Response body must not be null").isNotNull();
            assertThat(body).as("Body must contain status field").contains("\"status\"");
            assertThat(body).as("Body must contain error field").contains("\"error\"");

            // Optional: Check actual values
            assertThat(body).as("Status should be 'error'").contains("\"status\":\"error\"");
            assertThat(body).as("Error should be 'Too Many Requests'").contains("\"error\":\"Too Many Requests\"");
        }

        @Test
        @DisplayName("429 response includes Retry-After header")
        void rateLimited_responseHeaders_containRetryAfter() {
            // Exhaust auth bucket
            fireAuthRequests(AUTH_RATE_LIMIT);

            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> blocked = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(blocked.getHeaders().containsKey("Retry-After"))
                    .as("429 response must include Retry-After header")
                    .isTrue();

            String retryAfter = blocked.getHeaders().getFirst("Retry-After");
            assertThat(retryAfter)
                    .as("Retry-After must be a positive number")
                    .isNotNull()
                    .matches("\\d+");

            long retrySeconds = Long.parseLong(retryAfter);
            assertThat(retrySeconds)
                    .as("Retry-After must be greater than zero")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("X-Rate-Limit-Remaining header decrements with each request")
        void apiEndpoint_remainingHeader_decrementsCorrectly() {
            HttpHeaders headers = createHeaders();

            // First request — bucket should be full, remaining = 59
            ResponseEntity<String> first = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String remainingAfterFirst = first.getHeaders().getFirst("X-Rate-Limit-Remaining");

            // Second request — remaining should be one less than after first
            ResponseEntity<String> second = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String remainingAfterSecond = second.getHeaders().getFirst("X-Rate-Limit-Remaining");

            assertThat(remainingAfterFirst)
                    .as("First request must include X-Rate-Limit-Remaining header")
                    .isNotNull();

            assertThat(remainingAfterSecond)
                    .as("Second request must include X-Rate-Limit-Remaining header")
                    .isNotNull();

            long firstRemaining = Long.parseLong(remainingAfterFirst);
            long secondRemaining = Long.parseLong(remainingAfterSecond);

            assertThat(secondRemaining)
                    .as("Remaining tokens must decrease with each request")
                    .isLessThan(firstRemaining);
        }
    }

    @Nested
    @DisplayName("Rate limit buckets are isolated between route types")
    class BucketIsolation {

        @Test
        @DisplayName("Exhausting auth bucket does not affect API bucket")
        void exhaustingAuthBucket_doesNotAffectApiBucket() {
            // Exhaust the auth limit completely
            fireAuthRequests(AUTH_RATE_LIMIT);

            // Verify auth is now blocked
            HttpHeaders authHeaders = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> authEntity = new HttpEntity<>(loginBody, authHeaders);

            ResponseEntity<String> authBlocked = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    authEntity,
                    String.class
            );

            assertThat(authBlocked.getStatusCode())
                    .as("Auth endpoint must be blocked after exhausting auth bucket")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // API endpoint must still be accessible
            HttpHeaders apiHeaders = createHeaders();

            ResponseEntity<String> apiResponse = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(apiHeaders),
                    String.class
            );

            assertThat(apiResponse.getStatusCode())
                    .as("API endpoint must NOT be affected by auth bucket exhaustion")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}