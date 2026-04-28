package com.mouse.profiler.filter;

import com.mouse.profiler.base.BaseIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

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
 * starts with full buckets. No {@code @DirtiesContext} needed.</p>
 */
@DisplayName("Rate Limiting Filter — Integration Tests")
class RateLimitingFilterIntegrationTest extends BaseIntegrationTest {

    private static final String API_PROFILES_ENDPOINT  = "/api/v1/profiles";
    private static final String AUTH_LOGIN_ENDPOINT    = "/auth/login";
    private static final String CORRECT_VERSION_HEADER = "X-API-Version";
    private static final String CORRECT_VERSION_VALUE  = "1";

    private static final int AUTH_RATE_LIMIT = 10;
    private static final int API_RATE_LIMIT  = 60;

    /**
     * Fires {@code count} sequential GET requests to the given endpoint,
     * attaching the X-API-Version header when {@code withVersionHeader} is true.
     *
     * <p>Returns every response status code in order so tests can inspect
     * exactly which request in the sequence was the first to be blocked.</p>
     *
     * @param count how many requests to fire
     * @return ordered list of HTTP status codes, one per request
     */
    private List<HttpStatus> fireRequests(int count) {
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            HttpHeaders headers = new HttpHeaders();
            if (true) {
                headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);
            }

            ResponseEntity<String> response = restTemplate.exchange(
                    RateLimitingFilterIntegrationTest.API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            statuses.add((HttpStatus) response.getStatusCode());
        }

        return statuses;
    }

    /**
     * Fires {@code count} sequential POST requests.
     * Used for auth endpoint tests since login is a POST.
     */
    private List<HttpStatus> firePostRequests(int count) {
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    RateLimitingFilterIntegrationTest.AUTH_LOGIN_ENDPOINT,
                    null,
                    String.class
            );
            statuses.add((HttpStatus) response.getStatusCode());
        }

        return statuses;
    }

    @Nested
    @DisplayName("When requests are within the rate limit")
    class WithinRateLimit {

        /**
         * 10 requests against an auth endpoint should all pass through
         * (none should receive 429). They may receive other codes like
         * 401 from missing auth, but not 429 from rate limiting.
         */
        @Test
        @Disabled()
        @DisplayName("10 requests to /auth/** within limit are never rate limited")
        void authEndpoint_tenRequests_noneBlocked() {
            List<HttpStatus> statuses = firePostRequests(
                    AUTH_RATE_LIMIT
            );

            assertThat(statuses)
                    .as("None of the first %d requests should be rate limited", AUTH_RATE_LIMIT)
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        }

        /**
         * 5 requests against the API endpoint — well within the 60/5s limit.
         * All should pass the rate limiter regardless of auth outcome.
         */
        @Test
        @DisplayName("5 requests to /api/v1/profiles within limit are never rate limited")
        void apiEndpoint_fiveRequests_noneBlocked() {
            List<HttpStatus> statuses = fireRequests(
                    5
            );

            assertThat(statuses)
                    .as("5 requests well within limit should never receive 429")
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        }
    }


    @Nested
    @DisplayName("When requests exceed the rate limit")
    class ExceedingRateLimit {

        /**
         * Core test for auth rate limiting.
         *
         * <p>The strategy:</p>
         * <ol>
         *   <li>Fire AUTH_RATE_LIMIT (10) requests to exhaust the bucket.</li>
         *   <li>Fire one more request — the 11th.</li>
         *   <li>Assert the 11th returns 429.</li>
         * </ol>
         */
        @Test
        @Disabled()
        @DisplayName("11th request to /auth/** returns 429 Too Many Requests")
        void authEndpoint_eleventhRequest_isRateLimited() {
            // Exhaust the auth bucket completely
            firePostRequests(AUTH_RATE_LIMIT);

            // The very next request must be blocked
            ResponseEntity<String> blockedResponse = restTemplate.postForEntity(
                    AUTH_LOGIN_ENDPOINT,
                    null,
                    String.class
            );

            assertThat(blockedResponse.getStatusCode())
                    .as("11th request to auth endpoint must return 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        /**
         * Core test for API rate limiting.
         *
         * <p>Fires API_RATE_LIMIT (60) requests to exhaust the bucket,
         * then asserts the 61st returns 429.</p>
         *
         * <p>Note: This fires 61 real HTTP requests which takes a few seconds.
         * This is intentional — integration tests verify real behavior.</p>
         */
        @Test
        @DisplayName("61st request to /api/v1/profiles returns 429 Too Many Requests")
        void apiEndpoint_sixtyFirstRequest_isRateLimited() {
            // Exhaust the API bucket completely
            fireRequests(API_RATE_LIMIT);

            // The very next request must be blocked
            HttpHeaders headers = new HttpHeaders();
            headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);

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

        /**
         * Verifies the exact transition point — requests 1 through 10
         * on an auth endpoint must ALL succeed, and request 11 must fail.
         *
         * <p>This is stricter than the previous test. It checks the boundary
         * precisely rather than just firing and checking the last one.</p>
         */
        @Test
        @Disabled()
        @DisplayName("Auth rate limit boundary: requests 1-10 pass, request 11 fails")
        void authEndpoint_exactBoundary() {
            List<HttpStatus> statuses = firePostRequests(
                    AUTH_RATE_LIMIT + 1   // fire 11 total
            );

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

        /**
         * Verifies that the 429 response body follows the ApiErrorResponse
         * JSON shape. The CLI needs to parse this to display a useful message.
         */
        @Test
        @DisplayName("429 response body contains structured JSON error body")
        void rateLimited_responseBody_hasCorrectShape() {
            // Exhaust auth bucket
            firePostRequests(AUTH_RATE_LIMIT);

            ResponseEntity<String> blocked = restTemplate.postForEntity(
                    AUTH_LOGIN_ENDPOINT,
                    null,
                    String.class
            );

            assertThat(blocked.getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            String body = blocked.getBody();
            assertThat(body).as("Body must contain status field")
                    .contains("\"status\"");
            assertThat(body).as("Body must contain error field")
                    .contains("\"error\"");
            assertThat(body).as("Body must contain message field")
                    .contains("\"message\"");
            assertThat(body).as("Body must contain path field")
                    .contains("\"path\"");
            assertThat(body).as("Body must contain 429 code")
                    .contains("429");
        }

        /**
         * Verifies the Retry-After header is present on a 429 response.
         * The CLI uses this header to tell the user how long to wait.
         */
        @Test
        @Disabled()
        @DisplayName("429 response includes Retry-After header")
        void rateLimited_responseHeaders_containRetryAfter() {
            // Exhaust auth bucket
            firePostRequests(AUTH_RATE_LIMIT);

            ResponseEntity<String> blocked = restTemplate.postForEntity(
                    AUTH_LOGIN_ENDPOINT,
                    null,
                    String.class
            );

            assertThat(blocked.getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

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

        /**
         * Verifies the X-Rate-Limit-Remaining header decrements correctly
         * as requests consume tokens from the bucket.
         */
        @Test
        @DisplayName("X-Rate-Limit-Remaining header decrements with each request")
        void apiEndpoint_remainingHeader_decrementsCorrectly() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);

            // First request — bucket should be full, remaining = 59
            ResponseEntity<String> first = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String remainingAfterFirst = first.getHeaders()
                    .getFirst("X-Rate-Limit-Remaining");

            // Second request — remaining should be one less than after first
            ResponseEntity<String> second = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            String remainingAfterSecond = second.getHeaders()
                    .getFirst("X-Rate-Limit-Remaining");

            assertThat(remainingAfterFirst)
                    .as("First request must include X-Rate-Limit-Remaining header")
                    .isNotNull();

            assertThat(remainingAfterSecond)
                    .as("Second request must include X-Rate-Limit-Remaining header")
                    .isNotNull();

            long firstRemaining  = Long.parseLong(remainingAfterFirst);
            long secondRemaining = Long.parseLong(remainingAfterSecond);

            assertThat(secondRemaining)
                    .as("Remaining tokens must decrease with each request")
                    .isLessThan(firstRemaining);
        }
    }



    @Nested
    @DisplayName("Rate limit buckets are isolated between route types")
    class BucketIsolation {

        /**
         * Exhausting the auth bucket must NOT affect the API bucket.
         * These are two independent Redis keys, not a shared counter.
         *
         * <p>If this test fails, it means the bucket key strategy is wrong —
         * both route types are sharing the same Redis key.</p>
         */
        @Test
        @Disabled()
        @DisplayName("Exhausting auth bucket does not affect API bucket")
        void exhaustingAuthBucket_doesNotAffectApiBucket() {
            // Exhaust the auth limit completely
            firePostRequests(AUTH_RATE_LIMIT);

            // Verify auth is now blocked
            ResponseEntity<String> authBlocked = restTemplate.postForEntity(
                    AUTH_LOGIN_ENDPOINT,
                    null,
                    String.class
            );
            assertThat(authBlocked.getStatusCode())
                    .as("Auth endpoint must be blocked after exhausting auth bucket")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // API endpoint must still be accessible
            HttpHeaders headers = new HttpHeaders();
            headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);

            ResponseEntity<String> apiResponse = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            assertThat(apiResponse.getStatusCode())
                    .as("API endpoint must NOT be affected by auth bucket exhaustion")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
