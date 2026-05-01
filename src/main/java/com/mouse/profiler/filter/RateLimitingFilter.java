package com.mouse.profiler.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.ErrorResponseDTO;
import com.mouse.profiler.service.JwtService;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/auth/";
    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    private final ProxyManager<String> proxyManager;
    private final Supplier<BucketConfiguration> authBucketConfiguration;
    private final Supplier<BucketConfiguration> apiBucketConfiguration;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;

    private final ConcurrentHashMap<String, Bucket> bucketCache = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean isAuthPath = path != null && path.startsWith(AUTH_PATH_PREFIX);

        log.info("========================================");
        log.info("RATE LIMITING FILTER - Processing request");
        log.info("Method: {}, Path: {}", method, path);
        log.info("Is Auth Path: {}", isAuthPath);
        log.info("========================================");

        String bucketKey;
        Supplier<BucketConfiguration> configSupplier;

        if (isAuthPath) {
            String clientIp = getClientIp(request);
            bucketKey = "auth:" + clientIp;
            configSupplier = authBucketConfiguration;
            log.info("AUTH endpoint - Rate limiting by IP: {}", clientIp);
            log.info("Bucket key: {}", bucketKey);
        } else {
            String username = extractUsernameFromRequest(request);
            if (username == null) {
                log.warn("No username found in token, skipping rate limiting");
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "api:" + username;
            configSupplier = apiBucketConfiguration;
            log.info("API endpoint - Rate limiting by user: {}", username);
            log.info("Bucket key: {}", bucketKey);
        }

        try {
            log.info("Getting or creating bucket for key: {}", bucketKey);
            Bucket bucket = bucketCache.computeIfAbsent(bucketKey,
                    key -> {
                        log.info("Creating new bucket for key: {}", key);
                        return proxyManager.builder().build(key, configSupplier);
                    });

            log.info("Attempting to consume 1 token from bucket");
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            log.info("Consumption result:");
            log.info("  - Token consumed: {}", probe.isConsumed());
            log.info("  - Remaining tokens: {}", probe.getRemainingTokens());
            log.info("  - Nanos to wait for refill: {}", probe.getNanosToWaitForRefill());

            if (probe.isConsumed()) {
                log.info("✅ Rate limit check PASSED - Request allowed");
                response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
                filterChain.doFilter(request, response);
            } else {
                long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
                if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
                    retryAfterSeconds = MIN_RETRY_AFTER_SECONDS;
                }
                log.warn("🚫 Rate limit EXCEEDED - Request rejected");
                log.warn("  - Bucket key: {}", bucketKey);
                log.warn("  - Retry after: {} seconds", retryAfterSeconds);
                rejectRequest(response, retryAfterSeconds);
            }
        } catch (Exception e) {
            log.error("❌ Rate limiting filter error: {}", e.getMessage(), e);
            log.warn("Falling through to allow request due to error");
            filterChain.doFilter(request, response);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String ip = xForwardedFor.split(",")[0].trim();
            log.debug("Client IP from X-Forwarded-For: {}", ip);
            return ip;
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            log.debug("Client IP from X-Real-IP: {}", xRealIp);
            return xRealIp;
        }
        String remoteAddr = request.getRemoteAddr();
        log.debug("Client IP from RemoteAddr: {}", remoteAddr);
        return remoteAddr;
    }

    private String extractUsernameFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found in Authorization header");
            return null;
        }
        String token = authHeader.substring(7);
        log.debug("Extracted token: {}...", token.substring(0, Math.min(20, token.length())));

        try {
            if (!jwtService.isValid(token)) {
                log.debug("Invalid JWT token");
                return null;
            }
            String username = jwtService.extractUsername(token);
            log.debug("Extracted username: {}", username);
            return username;
        } catch (Exception e) {
            log.debug("Failed to extract username from token: {}", e.getMessage());
            return null;
        }
    }

    private void rejectRequest(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        log.info("Sending 429 Too Many Requests response");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.addHeader("X-Rate-Limit-Remaining", "0");

        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                "error",
                "Too Many Requests - Rate limit exceeded. Please wait " + retryAfterSeconds + " seconds."
        );

        objectMapper.writeValue(response.getWriter(), errorResponse);
        log.info("429 response sent");
    }

    @Override
    protected void initFilterBean() {
        log.info("========================================");
        log.info("RATE LIMITING FILTER INITIALIZED");
        log.info("Auth endpoints: IP-based rate limiting");
        log.info("API endpoints: Username-based rate limiting");
        log.info("========================================");
    }
}