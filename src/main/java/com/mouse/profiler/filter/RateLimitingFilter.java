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

        long requestStartTime = System.currentTimeMillis();
        String method = request.getMethod();
        String requestURI = request.getRequestURI();
        String clientId = resolveClientIdentifier(request);

        log.info("========================================");
        log.info("🔵 REQUEST RECEIVED: {} {}", method, requestURI);
        log.info("Client IP: {}", clientId);
        log.info("========================================");

        boolean isAuthPath = requestURI != null && requestURI.startsWith(AUTH_PATH_PREFIX);
        String bucketKey = (isAuthPath ? "auth:" : "api:") + clientId;

        log.info("📊 BUCKET DECISION:");
        log.info("   - isAuthPath: {}", isAuthPath);
        log.info("   - bucketKey: {}", bucketKey);
        log.info("   - rate limit type: {}", isAuthPath ? "AUTH" : "API");

        // Get the appropriate configuration supplier
        Supplier<BucketConfiguration> configSupplier = isAuthPath ? authBucketConfiguration : apiBucketConfiguration;

        // Log configuration details
        try {
            BucketConfiguration config = configSupplier.get();
            long capacity = config.getBandwidths()[0].getCapacity();
            long refillTokens = config.getBandwidths()[0].getRefillTokens();
            long refillPeriodNanos = config.getBandwidths()[0].getRefillPeriodNanos();
            long refillPeriodSeconds = refillPeriodNanos / 1_000_000_000;

            log.info("⚙️ BUCKET CONFIGURATION:");
            log.info("   - Capacity: {} requests", capacity);
            log.info("   - Refill Tokens: {} per {} seconds", refillTokens, refillPeriodSeconds);
            log.info("   - Config Source: {}", isAuthPath ? "authBucketConfiguration" : "apiBucketConfiguration");
        } catch (Exception e) {
            log.error("❌ FAILED TO GET BUCKET CONFIGURATION: {}", e.getMessage(), e);
        }

        try {
            log.info("🔨 BUILDING BUCKET for key: {}", bucketKey);
            Bucket bucket = proxyManager.builder()
                    .build(bucketKey, configSupplier);
            log.info("✅ BUCKET CREATED successfully for key: {}", bucketKey);

            log.info("🎫 TRYING TO CONSUME 1 TOKEN...");
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            long remainingTokens = probe.getRemainingTokens();
            boolean isConsumed = probe.isConsumed();
            long nanosToWait = probe.getNanosToWaitForRefill();
            long secondsToWait = TimeUnit.NANOSECONDS.toSeconds(nanosToWait);

            log.info("📊 CONSUMPTION RESULT:");
            log.info("   - Token Consumed: {}", isConsumed);
            log.info("   - Remaining Tokens: {}", remainingTokens);
            log.info("   - Time to wait for refill: {} nanoseconds ({} seconds)", nanosToWait, secondsToWait);

            if (isConsumed) {
                log.info("✅ REQUEST ALLOWED - Token consumed successfully");
                log.info("   - Remaining tokens: {}", remainingTokens);
                response.addHeader("X-Rate-Limit-Remaining", String.valueOf(remainingTokens));

                long processingTime = System.currentTimeMillis() - requestStartTime;
                log.info("⏱️ Request processing time: {} ms", processingTime);

                filterChain.doFilter(request, response);

                log.info("✅ REQUEST COMPLETED SUCCESSFULLY: {} {}", method, requestURI);
            } else {
                log.warn("🚫 REQUEST BLOCKED - Rate limit exceeded!");
                log.warn("   - Bucket exhausted for key: {}", bucketKey);
                log.warn("   - Need to wait {} seconds before next request", secondsToWait);
                rejectRequest(request, response, probe);
            }

        } catch (Exception e) {
            log.error("❌ RATE LIMITING ERROR - Exception occurred", e);
            log.error("   - Bucket Key: {}", bucketKey);
            log.error("   - Error Type: {}", e.getClass().getSimpleName());
            log.error("   - Error Message: {}", e.getMessage());

            // Fail-closed: Return 429 when Redis fails
            log.warn("⚠️ FALLING BACK TO FAIL-CLOSED - Returning 429");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                    "error",
                    "Rate limiting service unavailable - Please try again later"
            );
            objectMapper.writeValue(response.getWriter(), errorResponse);
            log.info("❌ REQUEST BLOCKED due to rate limiting service error: {} {}", method, requestURI);
        }

        log.info("========================================\n");
    }

    private String resolveClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");

        if (forwarded != null && !forwarded.isBlank()) {
            String clientIp = forwarded.split(",")[0].trim();
            log.debug("Client IP resolved from X-Forwarded-For header: {}", clientIp);
            return clientIp;
        }

        String remoteAddr = request.getRemoteAddr();
        log.debug("Client IP resolved from remote address: {}", remoteAddr);
        return remoteAddr;
    }

    private void rejectRequest(HttpServletRequest request,
                               HttpServletResponse response,
                               ConsumptionProbe probe) throws IOException {

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(
                probe.getNanosToWaitForRefill());

        if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
            log.debug("Retry-After less than minimum ({}), setting to minimum: {} seconds",
                    retryAfterSeconds, MIN_RETRY_AFTER_SECONDS);
            retryAfterSeconds = MIN_RETRY_AFTER_SECONDS;
        }

        log.warn("🚫 SENDING 429 RATE LIMIT RESPONSE:");
        log.warn("   - Path: {}", request.getRequestURI());
        log.warn("   - IP: {}", resolveClientIdentifier(request));
        log.warn("   - Retry-After: {} seconds", retryAfterSeconds);
        log.warn("   - Rate limit type: {}",
                request.getRequestURI().startsWith(AUTH_PATH_PREFIX) ? "AUTH" : "API");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.addHeader("X-Rate-Limit-Remaining", "0");

        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                "error",
                "Too Many Requests - Rate limit exceeded. Please wait " + retryAfterSeconds + " seconds."
        );

        objectMapper.writeValue(response.getWriter(), errorResponse);

        log.warn("⚠️ RATE LIMIT EXCEEDED - 429 response sent to client");
    }

    @Override
    protected void initFilterBean() throws ServletException {
        log.info("========================================");
        log.info("🔧 RATE LIMITING FILTER INITIALIZED");
        log.info("========================================");
        log.info("Filter is ACTIVE and will enforce rate limits");
        log.info("Auth bucket configuration: {}", authBucketConfiguration != null ? "LOADED" : "NULL");
        log.info("API bucket configuration: {}", apiBucketConfiguration != null ? "LOADED" : "NULL");
        log.info("ProxyManager: {}", proxyManager != null ? "LOADED" : "NULL");
        log.info("ObjectMapper: {}", objectMapper != null ? "LOADED" : "NULL");
        log.info("========================================\n");
        super.initFilterBean();
    }
}