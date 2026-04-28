//package com.mouse.profiler.interceptor;
//
//import com.mouse.profiler.base.BaseIntegrationTest;
//import org.junit.jupiter.api.Disabled;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.springframework.http.*;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
///**
// * Integration tests for {@link ApiVersionInterceptor}.
// *
// * <p>These tests verify that the interceptor correctly enforces the
// * {@code X-API-Version: 1} header on all {@code /api/**} routes and
// * that non-API routes are completely unaffected.</p>
// *
// * <p>Uses the full Spring Boot context started in {@link BaseIntegrationTest}
// * and fires real HTTP requests through TestRestTemplate. No mocking.
// * The interceptor, filter chain, and Redis are all live.</p>
// */
//@DisplayName("API Version Interceptor — Integration Tests")
//class ApiVersionInterceptorIntegrationTest extends BaseIntegrationTest {
//
//    private static final String API_PROFILES_ENDPOINT = "/api/v1/profiles";
//    private static final String AUTH_ENDPOINT         = "/auth/login";
//    private static final String CORRECT_VERSION       = "1";
//    private static final String WRONG_VERSION         = "99";
//
//
//
//    @Nested
//    @DisplayName("When X-API-Version header is correct")
//    class CorrectVersionHeader {
//
//        /**
//         * A request with X-API-Version: 1 must NOT be rejected by the
//         * interceptor. It should pass through and reach the controller.
//         * We assert anything other than 400 — the controller may return
//         * 200, 401, or 403 depending on auth, but never 400 from versioning.
//         */
//        @Test
//        @DisplayName("GET /api/v1/profiles with correct header passes the interceptor")
//        void correctHeader_shouldPassInterceptor() {
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("X-API-Version", CORRECT_VERSION);
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    API_PROFILES_ENDPOINT,
//                    HttpMethod.GET,
//                    new HttpEntity<>(headers),
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("Request with correct version header must not be rejected by interceptor")
//                    .isNotEqualTo(HttpStatus.BAD_REQUEST);
//        }
//
//        /**
//         * Verifies that the correct header works for a POST request too,
//         * not just GET. The interceptor applies to all HTTP methods.
//         */
//        @Test
//        @DisplayName("POST /api/v1/profiles with correct header passes the interceptor")
//        void correctHeader_onPost_shouldPassInterceptor() {
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("X-API-Version", CORRECT_VERSION);
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    API_PROFILES_ENDPOINT,
//                    HttpMethod.POST,
//                    new HttpEntity<>("{}", headers),
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("POST with correct version header must not be blocked by interceptor")
//                    .isNotEqualTo(HttpStatus.BAD_REQUEST);
//        }
//    }
//
//
//    @Nested
//    @DisplayName("When X-API-Version header is missing or wrong")
//    class MissingOrWrongVersionHeader {
//
//        /**
//         * The core requirement: a missing header must return 400.
//         * This proves the interceptor is active and enforcing the contract.
//         */
//        @Test
//        @DisplayName("Missing X-API-Version header returns 400 Bad Request")
//        void missingHeader_shouldReturn400() {
//            ResponseEntity<String> response = restTemplate.getForEntity(
//                    API_PROFILES_ENDPOINT,
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("Missing version header must return 400")
//                    .isEqualTo(HttpStatus.BAD_REQUEST);
//        }
//
//        /**
//         * A wrong version value (e.g. "99") must also return 400.
//         * This verifies the interceptor checks the VALUE not just presence.
//         */
//        @Test
//        @DisplayName("Wrong X-API-Version value returns 400 Bad Request")
//        void wrongVersionValue_shouldReturn400() {
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("X-API-Version", WRONG_VERSION);
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    API_PROFILES_ENDPOINT,
//                    HttpMethod.GET,
//                    new HttpEntity<>(headers),
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("Wrong version value must return 400")
//                    .isEqualTo(HttpStatus.BAD_REQUEST);
//        }
//
//        /**
//         * An empty string version value must be rejected just like a missing header.
//         */
//        @Test
//        @DisplayName("Empty X-API-Version value returns 400 Bad Request")
//        void emptyVersionValue_shouldReturn400() {
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("X-API-Version", "");
//
//            ResponseEntity<String> response = restTemplate.exchange(
//                    API_PROFILES_ENDPOINT,
//                    HttpMethod.GET,
//                    new HttpEntity<>(headers),
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("Empty version value must return 400")
//                    .isEqualTo(HttpStatus.BAD_REQUEST);
//        }
//
//        /**
//         * Verifies the 400 response body follows the ApiErrorResponse shape.
//         * The client needs a structured error to handle it programmatically.
//         */
//        @Test
//        @DisplayName("400 response body contains structured error JSON")
//        void missingHeader_responseBody_hasCorrectShape() {
//            ResponseEntity<String> response = restTemplate.getForEntity(
//                    API_PROFILES_ENDPOINT,
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .isEqualTo(HttpStatus.BAD_REQUEST);
//
//            String body = response.getBody();
//            assertThat(body)
//                    .as("Error body must contain status field")
//                    .contains("\"status\"");
//            assertThat(body)
//                    .as("Error body must contain error field")
//                    .contains("\"error\"");
//            assertThat(body)
//                    .as("Error body must contain message field")
//                    .contains("\"message\"");
//            assertThat(body)
//                    .as("Error body must contain path field")
//                    .contains("\"path\"");
//        }
//    }
//
//
//    @Nested
//    @DisplayName("When request targets a non-API route")
//    class NonApiRoutes {
//
//        /**
//         * Auth routes do not go through the versioning interceptor.
//         * A request to /auth/** without the version header must NOT return 400.
//         *
//         * This is critical — if auth routes required the version header, the
//         * login flow would break before a user could even authenticate.
//         */
//        @Test
//        @Disabled()
//        @DisplayName("/auth/** routes are exempt from version header requirement")
//        void authRoute_withoutVersionHeader_shouldNotReturn400() {
//            ResponseEntity<String> response = restTemplate.postForEntity(
//                    AUTH_ENDPOINT,
//                    null,
//                    String.class
//            );
//
//            assertThat(response.getStatusCode())
//                    .as("Auth routes must not require X-API-Version header")
//                    .isNotEqualTo(HttpStatus.BAD_REQUEST);
//        }
//    }
//}
