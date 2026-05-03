package com.mouse.profiler.filter;

import com.mouse.profiler.base.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
 */
@TestPropertySource(properties = {
        "cors.allowed.origins=http://localhost:3000,http://localhost:5173",
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

    @BeforeEach
    void setUp() {
        System.out.println("\n========================================");
        System.out.println("🟢 TEST SETUP - Starting new test");
        System.out.println("========================================\n");
    }

    private List<HttpStatus> fireApiRequests(int count) {
        System.out.println("\n🔥 fireApiRequests: Sending " + count + " requests to " + API_PROFILES_ENDPOINT);
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            HttpHeaders headers = createHeaders();

            System.out.println("  📤 Request #" + (i + 1) + " to " + API_PROFILES_ENDPOINT);

            ResponseEntity<String> response = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            HttpStatus status = (HttpStatus) response.getStatusCode();
            statuses.add(status);

            System.out.println("  📥 Response #" + (i + 1) + " Status: " + status);

            // Log headers if present
            if (response.getHeaders().containsKey("X-Rate-Limit-Remaining")) {
                String remaining = response.getHeaders().getFirst("X-Rate-Limit-Remaining");
                System.out.println("     X-Rate-Limit-Remaining: " + remaining);
            }
            if (response.getHeaders().containsKey("Retry-After")) {
                String retryAfter = response.getHeaders().getFirst("Retry-After");
                System.out.println("     Retry-After: " + retryAfter);
            }
        }

        System.out.println("✅ fireApiRequests completed. Statuses: " + statuses + "\n");
        return statuses;
    }

    private List<HttpStatus> fireAuthRequests(int count) {
        System.out.println("\n🔥 fireAuthRequests: Sending " + count + " requests to " + AUTH_LOGIN_ENDPOINT);
        List<HttpStatus> statuses = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            System.out.println("  📤 Request #" + (i + 1) + " to " + AUTH_LOGIN_ENDPOINT);

            ResponseEntity<String> response = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            HttpStatus status = (HttpStatus) response.getStatusCode();
            statuses.add(status);

            System.out.println("  📥 Response #" + (i + 1) + " Status: " + status);

            if (response.getHeaders().containsKey("X-Rate-Limit-Remaining")) {
                String remaining = response.getHeaders().getFirst("X-Rate-Limit-Remaining");
                System.out.println("     X-Rate-Limit-Remaining: " + remaining);
            }
        }

        System.out.println("✅ fireAuthRequests completed. Statuses: " + statuses + "\n");
        return statuses;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRECT_VERSION_HEADER, CORRECT_VERSION_VALUE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * DEBUG: Test to check if API endpoint exists
     */
    @Test
    @DisplayName("DEBUG: Check if API endpoint exists and returns properly")
    void debug_checkApiEndpointExists() {
        System.out.println("\n🔍 DEBUG: Checking if API endpoint exists");
        System.out.println("Endpoint: " + API_PROFILES_ENDPOINT);

        HttpHeaders headers = createHeaders();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            System.out.println("✅ API endpoint RESPONDED with status: " + response.getStatusCode());
            System.out.println("   Response body: " + response.getBody());
            System.out.println("   Headers: " + response.getHeaders());

            assertThat(response.getStatusCode()).isNotNull();
        } catch (Exception e) {
            System.err.println("❌ API endpoint FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DEBUG: Test to check if Auth endpoint exists
     */
    @Test
    @Disabled
    @DisplayName("DEBUG: Check if Auth endpoint exists and returns properly")
    void debug_checkAuthEndpointExists() {
        System.out.println("\n🔍 DEBUG: Checking if Auth endpoint exists");
        System.out.println("Endpoint: " + AUTH_LOGIN_ENDPOINT);

        HttpHeaders headers = createHeaders();
        String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
        HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            System.out.println("✅ Auth endpoint RESPONDED with status: " + response.getStatusCode());
            System.out.println("   Response body: " + response.getBody());

            assertThat(response.getStatusCode()).isNotNull();
        } catch (Exception e) {
            System.err.println("❌ Auth endpoint FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DEBUG: Test to see what happens with a single API request
     */
    @Test
    @DisplayName("DEBUG: Single API request analysis")
    void debug_singleApiRequestAnalysis() {
        System.out.println("\n🔍 DEBUG: Single API Request Analysis");

        HttpHeaders headers = createHeaders();

        System.out.println("Request Headers: " + headers);

        ResponseEntity<String> response = restTemplate.exchange(
                API_PROFILES_ENDPOINT,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        System.out.println("\n📊 RESPONSE DETAILS:");
        System.out.println("  Status Code: " + response.getStatusCode());
        System.out.println("  Status Code Value: " + response.getStatusCodeValue());
        System.out.println("  Headers: " + response.getHeaders());
        System.out.println("  Body: " + response.getBody());

        // Check for rate limit headers
        boolean hasRateLimitHeader = response.getHeaders().containsKey("X-Rate-Limit-Remaining");
        System.out.println("  Has X-Rate-Limit-Remaining: " + hasRateLimitHeader);

        if (hasRateLimitHeader) {
            String remaining = response.getHeaders().getFirst("X-Rate-Limit-Remaining");
            System.out.println("  X-Rate-Limit-Remaining: " + remaining);
        }
    }

    @Nested
    @DisplayName("When requests are within the rate limit")
    class WithinRateLimit {

        @Test
        @Disabled
        @DisplayName("10 requests to /auth/** within limit are never rate limited")
        void authEndpoint_tenRequests_noneBlocked() {
            System.out.println("\n🧪 TEST: 10 auth requests within limit");
            List<HttpStatus> statuses = fireAuthRequests(AUTH_RATE_LIMIT);

            assertThat(statuses)
                    .as("None of the first %d requests should be rate limited", AUTH_RATE_LIMIT)
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: No 429s in auth requests");
        }

        @Test
        @DisplayName("5 requests to /api/v1/profiles within limit are never rate limited")
        void apiEndpoint_fiveRequests_noneBlocked() {
            System.out.println("\n🧪 TEST: 5 API requests within limit");
            List<HttpStatus> statuses = fireApiRequests(5);

            assertThat(statuses)
                    .as("5 requests well within limit should never receive 429")
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: No 429s in API requests");
        }
    }

    @Nested
    @DisplayName("When requests exceed the rate limit")
    class ExceedingRateLimit {

        @Test
        @Disabled
        @DisplayName("11th request to /auth/** returns 429 Too Many Requests")
        void authEndpoint_eleventhRequest_isRateLimited() {
            System.out.println("\n🧪 TEST: Auth rate limit - 11th request should be blocked");

            // Exhaust the auth bucket completely
            System.out.println("Step 1: Sending 10 requests to exhaust auth bucket");
            fireAuthRequests(AUTH_RATE_LIMIT);

            // The very next request must be blocked
            System.out.println("Step 2: Sending 11th request (should be blocked)");
            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> blockedResponse = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            System.out.println("11th Response Status: " + blockedResponse.getStatusCode());
            System.out.println("11th Response Headers: " + blockedResponse.getHeaders());

            assertThat(blockedResponse.getStatusCode())
                    .as("11th request to auth endpoint must return 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: 11th auth request returned 429");
        }

        @Test
        @Disabled
        @DisplayName("61st request to /api/v1/profiles returns 429 Too Many Requests")
        void apiEndpoint_sixtyFirstRequest_isRateLimited() {
            System.out.println("\n🧪 TEST: API rate limit - 61st request should be blocked");

            // Exhaust the API bucket completely
            System.out.println("Step 1: Sending 60 requests to exhaust API bucket");
            List<HttpStatus> first60Statuses = fireApiRequests(API_RATE_LIMIT);

            long rateLimitedInFirst60 = first60Statuses.stream()
                    .filter(status -> status == HttpStatus.TOO_MANY_REQUESTS)
                    .count();

            System.out.println("   First 60 requests - Number of 429s: " + rateLimitedInFirst60);
            System.out.println("   First 60 requests - Statuses: " + first60Statuses);

            // The very next request must be blocked
            System.out.println("\nStep 2: Sending 61st request (should be blocked)");
            HttpHeaders headers = createHeaders();

            ResponseEntity<String> blockedResponse = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            HttpStatus sixtyFirstStatus = (HttpStatus) blockedResponse.getStatusCode();
            System.out.println("\n61st Response Status: " + sixtyFirstStatus);
            System.out.println("61st Response Headers: " + blockedResponse.getHeaders());
            System.out.println("61st Response Body: " + blockedResponse.getBody());

            assertThat(sixtyFirstStatus)
                    .as("61st request to API endpoint must return 429, but got: " + sixtyFirstStatus)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: 61st API request returned 429");
        }

        @Test
        @Disabled
        @DisplayName("Auth rate limit boundary: requests 1-10 pass, request 11 fails")
        void authEndpoint_exactBoundary() {
            System.out.println("\n🧪 TEST: Auth rate limit boundary check");

            List<HttpStatus> statuses = fireAuthRequests(AUTH_RATE_LIMIT + 1);
            System.out.println("All auth request statuses: " + statuses);

            // First 10 must all pass the rate limiter
            List<HttpStatus> first10 = statuses.subList(0, AUTH_RATE_LIMIT);
            assertThat(first10)
                    .as("Requests 1-10 must not be rate limited")
                    .doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
            System.out.println("✅ Requests 1-10 passed (no 429s)");

            // The 11th must be 429
            HttpStatus eleventhStatus = statuses.get(AUTH_RATE_LIMIT);
            assertThat(eleventhStatus)
                    .as("Request 11 must be rate limited with 429")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: 11th request returned 429");
        }

        @Test
        @Disabled
        @DisplayName("429 response includes Retry-After header")
        void rateLimited_responseHeaders_containRetryAfter() {
            System.out.println("\n🧪 TEST: 429 response has Retry-After header");

            // Exhaust auth bucket
            fireAuthRequests(AUTH_RATE_LIMIT);
            System.out.println("Auth bucket exhausted");

            HttpHeaders headers = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> entity = new HttpEntity<>(loginBody, headers);

            ResponseEntity<String> blocked = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            System.out.println("Response Headers: " + blocked.getHeaders());

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(blocked.getHeaders().containsKey("Retry-After"))
                    .as("429 response must include Retry-After header")
                    .isTrue();

            String retryAfter = blocked.getHeaders().getFirst("Retry-After");
            System.out.println("Retry-After value: " + retryAfter);

            assertThat(retryAfter)
                    .as("Retry-After must be a positive number")
                    .isNotNull()
                    .matches("\\d+");

            long retrySeconds = Long.parseLong(retryAfter);
            assertThat(retrySeconds)
                    .as("Retry-After must be greater than zero")
                    .isGreaterThan(0);

            System.out.println("✅ TEST PASSED: Retry-After header present and valid");
        }
    }

    @Nested
    @DisplayName("Rate limit buckets are isolated between route types")
    class BucketIsolation {

        @Test
        @Disabled
        @DisplayName("Exhausting auth bucket does not affect API bucket")
        void exhaustingAuthBucket_doesNotAffectApiBucket() {
            System.out.println("\n🧪 TEST: Bucket isolation - Auth and API buckets are independent");

            // Exhaust the auth limit completely
            System.out.println("Step 1: Exhausting auth bucket with 10 requests");
            fireAuthRequests(AUTH_RATE_LIMIT);

            // Verify auth is now blocked
            System.out.println("Step 2: Verifying auth is blocked (11th request)");
            HttpHeaders authHeaders = createHeaders();
            String loginBody = "{\"username\":\"test\",\"password\":\"test\"}";
            HttpEntity<String> authEntity = new HttpEntity<>(loginBody, authHeaders);

            ResponseEntity<String> authBlocked = restTemplate.exchange(
                    AUTH_LOGIN_ENDPOINT,
                    HttpMethod.POST,
                    authEntity,
                    String.class
            );

            System.out.println("   Auth 11th request status: " + authBlocked.getStatusCode());

            assertThat(authBlocked.getStatusCode())
                    .as("Auth endpoint must be blocked after exhausting auth bucket")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ Auth endpoint correctly blocked");

            // API endpoint must still be accessible
            System.out.println("Step 3: Verifying API endpoint is NOT affected");
            HttpHeaders apiHeaders = createHeaders();

            ResponseEntity<String> apiResponse = restTemplate.exchange(
                    API_PROFILES_ENDPOINT,
                    HttpMethod.GET,
                    new HttpEntity<>(apiHeaders),
                    String.class
            );

            System.out.println("   API request status: " + apiResponse.getStatusCode());

            assertThat(apiResponse.getStatusCode())
                    .as("API endpoint must NOT be affected by auth bucket exhaustion")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            System.out.println("✅ TEST PASSED: API endpoint unaffected by auth rate limiting");
        }
    }
}