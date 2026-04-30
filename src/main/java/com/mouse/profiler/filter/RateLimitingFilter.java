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
        boolean isAuthPath = path != null && path.startsWith(AUTH_PATH_PREFIX);

        String bucketKey;
        Supplier<BucketConfiguration> configSupplier;

        if (isAuthPath) {
            String clientIp = getClientIp(request);
            bucketKey = "auth:" + clientIp;
            configSupplier = authBucketConfiguration;
            log.debug("Auth endpoint - rate limiting by IP: {}", clientIp);
        } else {
            String username = extractUsernameFromRequest(request);
            if (username == null) {
                filterChain.doFilter(request, response);
                return;
            }
            bucketKey = "api:" + username;
            configSupplier = apiBucketConfiguration;
            log.debug("API endpoint - rate limiting by user: {}", username);
        }

        Bucket bucket = bucketCache.computeIfAbsent(bucketKey,
                key -> proxyManager.builder().build(key, configSupplier));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
                retryAfterSeconds = MIN_RETRY_AFTER_SECONDS;
            }
            rejectRequest(response, retryAfterSeconds);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String extractUsernameFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            if (!jwtService.isValid(token)) {
                log.debug("Invalid JWT token");
                return null;
            }
            return jwtService.extractUsername(token);
        } catch (Exception e) {
            log.debug("Failed to extract username from token: {}", e.getMessage());
            return null;
        }
    }

    private void rejectRequest(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.addHeader("X-Rate-Limit-Remaining", "0");

        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                "error",
                "Too Many Requests - Rate limit exceeded. Please wait " + retryAfterSeconds + " seconds."
        );

        objectMapper.writeValue(response.getWriter(), errorResponse);
    }

    @Override
    protected void initFilterBean() {
        log.info("Rate limiting filter initialized - Auth: IP based, API: Username based");
    }
}