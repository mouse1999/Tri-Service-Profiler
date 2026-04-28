package com.mouse.profiler.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.ErrorResponseDTO;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
@ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/auth/";
    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    private final ProxyManager<String> proxyManager;
    private final Supplier<BucketConfiguration> authBucketConfiguration;
    private final Supplier<BucketConfiguration> apiBucketConfiguration;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientId = resolveClientIdentifier(request);
        boolean isAuthPath = request.getRequestURI() != null &&
                request.getRequestURI().startsWith(AUTH_PATH_PREFIX);

        String bucketKey = (isAuthPath ? "auth:" : "api:") + clientId;
        Supplier<BucketConfiguration> configSupplier =
                isAuthPath ? authBucketConfiguration : apiBucketConfiguration;

        try {
            Bucket bucket = proxyManager.builder()
                    .build(bucketKey, configSupplier);

            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                response.addHeader("X-Rate-Limit-Remaining",
                        String.valueOf(probe.getRemainingTokens()));
                filterChain.doFilter(request, response);
            } else {
                rejectRequest(request, response, probe);
            }
        } catch (Exception e) {
            log.error("Rate limiting error for bucket key: {}, allowing request (fail-open)", bucketKey, e);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                    "error",
                    "Rate limiting service unavailable"
            );
            objectMapper.writeValue(response.getWriter(), errorResponse);
        }
    }

    private String resolveClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void rejectRequest(HttpServletRequest request,
                               HttpServletResponse response,
                               ConsumptionProbe probe) throws IOException {

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(
                probe.getNanosToWaitForRefill());

        if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
            retryAfterSeconds = MIN_RETRY_AFTER_SECONDS;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.addHeader("X-Rate-Limit-Remaining", "0");

        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                "error",
                "Too Many Requests"
        );

        objectMapper.writeValue(response.getWriter(), errorResponse);
        log.warn("Rate limit exceeded for path: {} from IP: {}", request.getRequestURI(), resolveClientIdentifier(request));
    }
}