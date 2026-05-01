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

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiVersionInterceptor implements HandlerInterceptor {

    private static final String VERSION_HEADER = "X-API-Version";
    private static final String LATEST_VERSION = "1";

    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1", "2", "3");

    private static final Map<String, VersionInfo> VERSION_INFO = Map.of(
            "1", new VersionInfo(
                    null,
                    null,
                    "Latest - Recommended"
            ),
            "2", new VersionInfo(
                    LocalDate.of(2025, 12, 31),
                    LocalDate.of(2026, 6, 30),
                    "Stable - Will be deprecated soon"
            ),
            "3", new VersionInfo(
                    LocalDate.of(2024, 12, 31),
                    LocalDate.of(2025, 6, 30),
                    "Deprecated - Please migrate to v1"
            )
    );

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String version = request.getHeader(VERSION_HEADER);

        if (isBlank(version)) {
            return reject(response, "API version header required");
        }

        if (!version.matches("\\d+")) {
            return reject(response, "Invalid API version format. Use numeric versions: " + SUPPORTED_VERSIONS);
        }

        if (!SUPPORTED_VERSIONS.contains(version)) {
            return reject(response, "Unsupported API version: " + version + ". Supported: " + SUPPORTED_VERSIONS);
        }

        VersionInfo info = VERSION_INFO.get(version);
        if (info != null && info.isDeprecated()) {
            response.setHeader("Warning", String.format(
                    "299 - \"Version %s is deprecated. Support ends on %s. Migrate to %s.\"",
                    version, info.getSunsetDate(), LATEST_VERSION
            ));
            log.warn("Deprecated API version {} used from IP: {}", version, request.getRemoteAddr());
        }

        if (info != null && info.isSunset()) {
            return reject(response, "Version " + version + " is no longer supported. Upgrade to " + LATEST_VERSION);
        }

        request.setAttribute("apiVersion", version);
        request.setAttribute("isLatestVersion", LATEST_VERSION.equals(version));

        log.debug("API version: {} for path: {}", version, request.getRequestURI());
        return true;
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), new ErrorResponseDTO("error", message));
        return false;
    }

    public static boolean isSupported(String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    public static Set<String> getSupportedVersions() {
        return SUPPORTED_VERSIONS;
    }

    public static String getLatestVersion() {
        return LATEST_VERSION;
    }

    @lombok.Value
    private static class VersionInfo {
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