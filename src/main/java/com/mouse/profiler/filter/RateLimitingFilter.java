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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
@ConditionalOnProperty(name = "rate.limiting.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/auth/";
    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    // Counter to track request sequence across all requests
    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    private final ProxyManager<String> proxyManager;
    private final Supplier<BucketConfiguration> authBucketConfiguration;
    private final Supplier<BucketConfiguration> apiBucketConfiguration;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        int requestNumber = requestCounter.incrementAndGet();
        long requestStartTime = System.currentTimeMillis();
        String method = request.getMethod();
        String requestURI = request.getRequestURI();
        String clientId = resolveClientIdentifier(request);

        // Get Session ID if available
        String sessionId = request.getSession(false) != null ? request.getSession().getId() : "no-session";

        log.info("\n" + "=".repeat(80));
        log.info("🔵🔵🔵 REQUEST #{} 🔵🔵🔵", requestNumber);
        log.info("=".repeat(80));
        log.info("📋 REQUEST DETAILS:");
        log.info("   - Method: {}", method);
        log.info("   - URI: {}", requestURI);
        log.info("   - Client IP (resolved): {}", clientId);
        log.info("   - Session ID: {}", sessionId);
        log.info("   - Remote Addr: {}", request.getRemoteAddr());
        log.info("   - X-Forwarded-For: {}", request.getHeader("X-Forwarded-For"));
        log.info("   - X-Real-IP: {}", request.getHeader("X-Real-IP"));

        boolean isAuthPath = requestURI != null && requestURI.startsWith(AUTH_PATH_PREFIX);
        String bucketKey = (isAuthPath ? "auth:" : "api:") + clientId;

        log.info("\n📊 BUCKET DECISION:");
        log.info("   - isAuthPath: {}", isAuthPath);
        log.info("   - bucketKey: {}", bucketKey);
        log.info("   - rate limit type: {}", isAuthPath ? "AUTH" : "API");

        // Get the appropriate configuration supplier
        Supplier<BucketConfiguration> configSupplier = isAuthPath ? authBucketConfiguration : apiBucketConfiguration;

        // Log configuration details
        BucketConfiguration config = null;
        try {
            config = configSupplier.get();
            long capacity = config.getBandwidths()[0].getCapacity();
            long refillTokens = config.getBandwidths()[0].getRefillTokens();
            long refillPeriodNanos = config.getBandwidths()[0].getRefillPeriodNanos();
            long refillPeriodSeconds = refillPeriodNanos / 1_000_000_000;

            log.info("\n⚙️ BUCKET CONFIGURATION:");
            log.info("   - Capacity: {} requests", capacity);
            log.info("   - Refill Tokens: {} per {} seconds", refillTokens, refillPeriodSeconds);
            log.info("   - Config Source: {}", isAuthPath ? "authBucketConfiguration" : "apiBucketConfiguration");
        } catch (Exception e) {
            log.error("❌ FAILED TO GET BUCKET CONFIGURATION: {}", e.getMessage(), e);
        }

        try {
            log.info("\n🔨 BUILDING BUCKET for key: '{}'", bucketKey);

            long beforeBuild = System.currentTimeMillis();
            Bucket bucket = proxyManager.builder()
                    .build(bucketKey, configSupplier);
            long buildTime = System.currentTimeMillis() - beforeBuild;

            log.info("✅ BUCKET CREATED for key: '{}' (took {} ms)", bucketKey, buildTime);

            // Get current bucket state BEFORE consuming
            long availableTokensBefore = bucket.getAvailableTokens();
            log.info("📊 BUCKET STATE BEFORE CONSUMPTION:");
            log.info("   - Available tokens before: {}", availableTokensBefore);
            log.info("   - Bucket key: {}", bucketKey);
            log.info("   - Request #: {}", requestNumber);

            log.info("\n🎫 TRYING TO CONSUME 1 TOKEN...");
            long beforeConsume = System.currentTimeMillis();
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            long consumeTime = System.currentTimeMillis() - beforeConsume;

            long remainingTokens = probe.getRemainingTokens();
            boolean isConsumed = probe.isConsumed();
            long nanosToWait = probe.getNanosToWaitForRefill();
            long secondsToWait = TimeUnit.NANOSECONDS.toSeconds(nanosToWait);

            log.info("\n📊 CONSUMPTION RESULT (took {} ms):", consumeTime);
            log.info("   - Token Consumed: {}", isConsumed);
            log.info("   - Remaining Tokens: {}", remainingTokens);
            log.info("   - Time to wait for refill: {} nanoseconds ({} seconds)", nanosToWait, secondsToWait);

            // Calculate expected remaining
            long expectedRemaining = availableTokensBefore - (isConsumed ? 1 : 0);
            log.info("   - Expected remaining after: {}", expectedRemaining);

            if (remainingTokens != expectedRemaining) {
                log.warn("⚠️ REMAINING TOKENS MISMATCH! Expected: {}, Actual: {}", expectedRemaining, remainingTokens);
            }

            if (isConsumed) {
                log.info("\n✅ REQUEST ALLOWED - Token consumed successfully");
                log.info("   - Before: {} tokens", availableTokensBefore);
                log.info("   - After: {} tokens", remainingTokens);
                log.info("   - Decrease: {} tokens", availableTokensBefore - remainingTokens);

                response.addHeader("X-Rate-Limit-Remaining", String.valueOf(remainingTokens));
                response.addHeader("X-Rate-Limit-Bucket", bucketKey);
                response.addHeader("X-Request-Sequence", String.valueOf(requestNumber));

                long processingTime = System.currentTimeMillis() - requestStartTime;
                log.info("   - Request processing time: {} ms", processingTime);

                filterChain.doFilter(request, response);

                log.info("✅ REQUEST COMPLETED SUCCESSFULLY: {} {} (Total time: {} ms)",
                        method, requestURI, System.currentTimeMillis() - requestStartTime);
            } else {
                log.warn("\n🚫 REQUEST BLOCKED - Rate limit exceeded!");
                log.warn("   - Bucket exhausted for key: {}", bucketKey);
                log.warn("   - Available before: {} tokens", availableTokensBefore);
                log.warn("   - Need to wait {} seconds before next request", secondsToWait);
                log.warn("   - Request #{} was blocked (would have been request #{} for this bucket)",
                        requestNumber, (config != null ? config.getBandwidths()[0].getCapacity() - availableTokensBefore + 1 : "?"));
                rejectRequest(request, response, probe);
            }

        } catch (Exception e) {
            log.error("\n❌ RATE LIMITING ERROR - Exception occurred", e);
            log.error("   - Bucket Key: {}", bucketKey);
            log.error("   - Error Type: {}", e.getClass().getSimpleName());
            log.error("   - Error Message: {}", e.getMessage());
            log.error("   - Stack trace: ", e);

            // Fail-closed: Return 429 when Redis fails
            log.warn("⚠️ FALLING BACK TO FAIL-CLOSED - Returning 429");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.addHeader("X-Rate-Limit-Error", "service-unavailable");
            ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                    "error",
                    "Rate limiting service unavailable - Please try again later"
            );
            objectMapper.writeValue(response.getWriter(), errorResponse);
            log.info("❌ REQUEST BLOCKED due to rate limiting service error: {} {}", method, requestURI);
        }

        log.info("\n" + "=".repeat(80) + "\n");
    }

    private String resolveClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");

        log.debug("🔍 RESOLVING CLIENT IDENTIFIER:");
        log.debug("   - X-Forwarded-For: {}", forwarded);
        log.debug("   - X-Real-IP: {}", realIp);
        log.debug("   - Remote Addr: {}", request.getRemoteAddr());

        if (forwarded != null && !forwarded.isBlank()) {
            String clientIp = forwarded.split(",")[0].trim();
            log.debug("   - Resolved from X-Forwarded-For: {}", clientIp);
            return clientIp;
        }

        if (realIp != null && !realIp.isBlank()) {
            log.debug("   - Resolved from X-Real-IP: {}", realIp);
            return realIp;
        }

        String remoteAddr = request.getRemoteAddr();
        log.debug("   - Resolved from Remote Addr: {}", remoteAddr);
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

        log.warn("\n🚫 SENDING 429 RATE LIMIT RESPONSE:");
        log.warn("   - Path: {}", request.getRequestURI());
        log.warn("   - IP: {}", resolveClientIdentifier(request));
        log.warn("   - Retry-After: {} seconds", retryAfterSeconds);
        log.warn("   - Rate limit type: {}",
                request.getRequestURI().startsWith(AUTH_PATH_PREFIX) ? "AUTH" : "API");
        log.warn("   - Remaining tokens before block: {}", probe.getRemainingTokens());

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
        log.info("\n" + "=".repeat(80));
        log.info("🔧🔧🔧 RATE LIMITING FILTER INITIALIZED 🔧🔧🔧");
        log.info("=".repeat(80));
        log.info("Filter is ACTIVE and will enforce rate limits");
        log.info("Auth bucket configuration: {}", authBucketConfiguration != null ? "LOADED" : "NULL");
        log.info("API bucket configuration: {}", apiBucketConfiguration != null ? "LOADED" : "NULL");
        log.info("ProxyManager: {}", proxyManager != null ? "LOADED" : "NULL");
        log.info("ObjectMapper: {}", objectMapper != null ? "LOADED" : "NULL");
        log.info("=".repeat(80) + "\n");
        super.initFilterBean();
    }
}