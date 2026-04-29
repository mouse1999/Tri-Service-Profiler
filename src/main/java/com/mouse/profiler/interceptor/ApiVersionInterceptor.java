package com.mouse.profiler.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.ErrorResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * A Spring MVC {@link HandlerInterceptor} that enforces API versioning
 * via the {@code X-API-Version} request header.
 *
 * <p>Every request to {@code /api/profiles/**} must include the header:</p>
 * <pre>
 *   X-API-Version: 1
 * </pre>
 *
 * <p>If the header is missing or contains an unsupported version,
 * this interceptor short-circuits the request and returns a 400 Bad Request
 * before the request ever reaches a controller.</p>
 *
 * <p>Supported versions: 1, 2, 3</p>
 *
 * <p>Registration: This interceptor is registered in WebMvcConfig
 * and applied only to {@code /api/profiles/**} paths.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiVersionInterceptor implements HandlerInterceptor {

    private static final String VERSION_HEADER = "X-API-Version";

    // Core supported versions
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1", "2", "3");
    private static final String LATEST_VERSION = "3";

    // Version metadata for deprecation warnings
    private static final Map<String, VersionMetadata> VERSION_METADATA = Map.of(
            "1", new VersionMetadata(
                    "1",
                    true,  // still supported
                    LocalDate.of(2024, 12, 31),  // deprecated after this date
                    LocalDate.of(2025, 6, 30),   // sunset after this date
                    "Basic profile data without statistics"
            ),
            "2", new VersionMetadata(
                    "2",
                    true,
                    null,  // not deprecated yet
                    null,  // not sunset
                    "Includes profile statistics and enhanced metadata"
            ),
            "3", new VersionMetadata(
                    "3",
                    true,
                    null,
                    null,
                    "Latest: Includes real-time analytics and predictions"
            )
    );

    private final ObjectMapper objectMapper;

    /**
     * Intercepts every request before it reaches a controller method.
     *
     * <p>This method reads the {@code X-API-Version} header and decides
     * whether to allow the request through or reject it immediately.</p>
     *
     * @param request  the incoming HTTP request
     * @param response the outgoing HTTP response (written to on rejection)
     * @param handler  the matched handler method (not used here)
     * @return {@code true} to continue the chain, {@code false} to abort
     * @throws Exception if writing the JSON error body fails
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String headerValue = request.getHeader(VERSION_HEADER);

        // Check if header is missing
        if (headerValue == null || headerValue.trim().isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "API version header required. Supported versions: " +
                            String.join(", ", SUPPORTED_VERSIONS));
            return false;
        }

        // Validate version format (must be numeric)
        if (!headerValue.matches("\\d+")) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid API version format. Use numeric versions like: " +
                            String.join(", ", SUPPORTED_VERSIONS));
            return false;
        }

        // Check if version is supported
        if (!SUPPORTED_VERSIONS.contains(headerValue)) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    String.format("Unsupported API version '%s'. Supported versions: %s",
                            headerValue, String.join(", ", SUPPORTED_VERSIONS)));
            return false;
        }

        // Check for deprecated versions (add warning header)
        VersionMetadata metadata = VERSION_METADATA.get(headerValue);
        if (metadata != null && metadata.isDeprecated()) {
            String warningMessage = String.format(
                    "299 - \"Version %s is deprecated. Please migrate to version %s or later. " +
                            "Support ends on %s\"",
                    headerValue, LATEST_VERSION, metadata.getSunsetDate()
            );
            response.setHeader("Warning", warningMessage);
            log.warn("Deprecated API version used: {} from IP: {}",
                    headerValue, request.getRemoteAddr());
        }

        // Check for sunset versions (completely removed)
        if (metadata != null && metadata.isSunset()) {
            sendErrorResponse(response, HttpServletResponse.SC_GONE,
                    String.format("Version %s is no longer supported as of %s. " +
                                    "Please upgrade to version %s or later.",
                            headerValue, metadata.getSunsetDate(), LATEST_VERSION));
            return false;
        }

        // Store version in request attribute for controller use
        request.setAttribute("apiVersion", headerValue);
        request.setAttribute("isLatestVersion", LATEST_VERSION.equals(headerValue));

        // Log API version usage (useful for analytics)
        log.debug("API request with version: {} for path: {}",
                headerValue, request.getRequestURI());

        return true; // Proceed with the request
    }

    /**
     * Sends a standardized error response.
     *
     * @param response the HTTP response
     * @param statusCode the HTTP status code
     * @param message the error message
     * @throws Exception if writing the response fails
     */
    private void sendErrorResponse(HttpServletResponse response,
                                   int statusCode,
                                   String message) throws Exception {
        response.setStatus(statusCode);
        response.setContentType("application/json");

        ErrorResponseDTO errorResponse = new ErrorResponseDTO("error", message);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }

    /**
     * Helper method to check if a version is supported.
     *
     * @param version the version to check
     * @return true if supported
     */
    public static boolean isSupportedVersion(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    /**
     * Helper method to get all supported versions.
     *
     * @return set of supported versions
     */
    public static Set<String> getSupportedVersions() {
        return SUPPORTED_VERSIONS;
    }

    /**
     * Helper method to get the latest version.
     *
     * @return the latest API version
     */
    public static String getLatestVersion() {
        return LATEST_VERSION;
    }

    /**
     * Inner class to hold version metadata.
     */
    @lombok.Value
    private static class VersionMetadata {
        String version;
        boolean supported;
        LocalDate deprecationDate;
        LocalDate sunsetDate;
        String description;

        public boolean isDeprecated() {
            return deprecationDate != null && LocalDate.now().isAfter(deprecationDate);
        }

        public boolean isSunset() {
            return sunsetDate != null && LocalDate.now().isAfter(sunsetDate);
        }
    }
}