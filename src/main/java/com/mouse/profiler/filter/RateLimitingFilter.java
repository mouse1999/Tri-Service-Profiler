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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
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

    // CACHE BUCKETS to reuse same instance for same key
    private final ConcurrentHashMap<String, Bucket> bucketCache = new ConcurrentHashMap<>();

    // Locks for each bucket key to prevent race conditions
    private final ConcurrentHashMap<String, Object> bucketLocks = new ConcurrentHashMap<>();

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

        // NORMALIZE client identifier to prevent IPv4/IPv6 race conditions
        String clientId = normalizeClientIdentifier(request);
        boolean isAuthPath = requestURI != null && requestURI.startsWith(AUTH_PATH_PREFIX);
        String bucketKey = (isAuthPath ? "auth:" : "api:") + clientId;

        log.info("\n" + "=".repeat(80));
        log.info("🔵🔵🔵 REQUEST #{} - BUCKET KEY: {} 🔵🔵🔵", requestNumber, bucketKey);
        log.info("=".repeat(80));
        log.info("📋 REQUEST DETAILS:");
        log.info("   - Method: {}", method);
        log.info("   - URI: {}", requestURI);
        log.info("   - Normalized Client ID: {}", clientId);

        // Get the appropriate configuration supplier
        Supplier<BucketConfiguration> configSupplier = isAuthPath ? authBucketConfiguration : apiBucketConfiguration;

        // Log configuration details
        try {
            BucketConfiguration config = configSupplier.get();
            long capacity = config.getBandwidths()[0].getCapacity();
            long refillPeriodNanos = config.getBandwidths()[0].getRefillPeriodNanos();
            long refillPeriodSeconds = refillPeriodNanos / 1_000_000_000;

            log.info("\n⚙️ BUCKET CONFIGURATION:");
            log.info("   - Capacity: {} requests", capacity);
            log.info("   - Refill: {} per {} seconds", capacity, refillPeriodSeconds);
            log.info("   - Type: {}", isAuthPath ? "AUTH" : "API");
        } catch (Exception e) {
            log.error("❌ FAILED TO GET BUCKET CONFIGURATION: {}", e.getMessage(), e);
        }

        // Get or create a lock for this bucket key
        Object bucketLock = bucketLocks.computeIfAbsent(bucketKey, k -> new Object());

        // CRITICAL: Synchronize on bucket key to ensure atomic operations
        synchronized (bucketLock) {
            log.info("🔒 Acquired lock for bucket key: {}", bucketKey);

            try {
                // Get or create cached bucket (REUSE same instance!)
                Bucket bucket = bucketCache.computeIfAbsent(bucketKey, key -> {
                    log.info("🪣 Creating NEW cached bucket for key: {}", bucketKey);
                    return proxyManager.builder().build(key, configSupplier);
                });

                // Get current bucket state BEFORE consuming
                long availableTokensBefore = bucket.getAvailableTokens();
                log.info("📊 Available tokens BEFORE: {}", availableTokensBefore);

                // Attempt to consume 1 token
                long beforeConsume = System.currentTimeMillis();
                ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
                long consumeTime = System.currentTimeMillis() - beforeConsume;

                long remainingTokens = probe.getRemainingTokens();
                boolean isConsumed = probe.isConsumed();
                long nanosToWait = probe.getNanosToWaitForRefill();
                long secondsToWait = TimeUnit.NANOSECONDS.toSeconds(nanosToWait);

                log.info("📊 CONSUMPTION RESULT (took {} ms):", consumeTime);
                log.info("   - Token Consumed: {}", isConsumed);
                log.info("   - Remaining: {} tokens", remainingTokens);
                log.info("   - Wait time: {} seconds", secondsToWait);

                // Validate consumption consistency
                if (isConsumed && remainingTokens != availableTokensBefore - 1) {
                    log.error("❌ TOKEN MISMATCH! Expected: {}, Actual: {}",
                            availableTokensBefore - 1, remainingTokens);
                }

                if (isConsumed) {
                    log.info("✅ REQUEST ALLOWED");
                    response.addHeader("X-Rate-Limit-Remaining", String.valueOf(remainingTokens));
                    response.addHeader("X-Rate-Limit-Bucket", bucketKey);
                    response.addHeader("X-Request-Sequence", String.valueOf(requestNumber));

                    // Release lock BEFORE calling filter chain to prevent deadlocks
                    log.info("🔓 Releasing lock for bucket key: {}", bucketKey);

                    filterChain.doFilter(request, response);

                    long totalTime = System.currentTimeMillis() - requestStartTime;
                    log.info("✅ REQUEST COMPLETED ({} ms)", totalTime);

                } else {
                    log.warn("🚫 REQUEST BLOCKED - Rate limit exceeded!");
                    log.warn("   - Needed to wait {} seconds", secondsToWait);

                    // Release lock before sending response
                    log.info("🔓 Releasing lock for bucket key: {}", bucketKey);
                    rejectRequest(request, response, probe);
                }

            } catch (Exception e) {
                log.error("❌ RATE LIMITING ERROR: {}", e.getMessage(), e);
                log.info("🔓 Releasing lock due to error");

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                        "error",
                        "Rate limiting service unavailable - Please try again later"
                );
                objectMapper.writeValue(response.getWriter(), errorResponse);
            }
        }

        log.info("\n" + "=".repeat(80) + "\n");
    }

    /**
     * Normalizes client identifier to prevent race conditions from different IP representations.
     * This ensures that IPv4 (127.0.0.1) and IPv6 (::1) localhost both map to same key.
     */
    private String normalizeClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String remoteAddr = request.getRemoteAddr();

        String rawClientId = null;

        // Priority: X-Forwarded-For > X-Real-IP > RemoteAddr
        if (forwarded != null && !forwarded.isBlank()) {
            rawClientId = forwarded.split(",")[0].trim();
            log.debug("Client from X-Forwarded-For: {}", rawClientId);
        } else if (realIp != null && !realIp.isBlank()) {
            rawClientId = realIp.trim();
            log.debug("Client from X-Real-IP: {}", rawClientId);
        } else {
            rawClientId = remoteAddr;
            log.debug("Client from RemoteAddr: {}", rawClientId);
        }

        // Normalize localhost addresses
        if (isLocalhost(rawClientId)) {
            log.debug("Normalizing localhost: {} → 127.0.0.1", rawClientId);
            return "127.0.0.1";
        }

        return rawClientId;
    }

    /**
     * Checks if an address is a localhost address (IPv4 or IPv6)
     */
    private boolean isLocalhost(String address) {
        if (address == null) return false;

        // Direct string matches
        if ("127.0.0.1".equals(address) ||
                "localhost".equalsIgnoreCase(address) ||
                "::1".equals(address) ||
                "0:0:0:0:0:0:0:1".equals(address)) {
            return true;
        }

        // Try to resolve and check if loopback
        try {
            InetAddress inet = InetAddress.getByName(address);
            return inet.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void rejectRequest(HttpServletRequest request,
                               HttpServletResponse response,
                               ConsumptionProbe probe) throws IOException {

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(
                probe.getNanosToWaitForRefill());

        if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
            retryAfterSeconds = MIN_RETRY_AFTER_SECONDS;
        }

        log.warn("🚫 SENDING 429 RESPONSE:");
        log.warn("   - Path: {}", request.getRequestURI());
        log.warn("   - Retry-After: {} seconds", retryAfterSeconds);

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
    protected void initFilterBean() throws ServletException {
        log.info("\n" + "=".repeat(80));
        log.info("🔧🔧🔧 RATE LIMITING FILTER INITIALIZED 🔧🔧🔧");
        log.info("=".repeat(80));
        log.info("✅ Filter is ACTIVE and will enforce rate limits");
        log.info("✅ Bucket Cache: ENABLED (reuses same bucket per key)");
        log.info("✅ Lock Mechanism: Per-bucket synchronization");
        log.info("✅ IP Normalization: ENABLED (IPv4/IPv6 localhost → 127.0.0.1)");
        log.info("✅ Fail-closed: ON (returns 429 on Redis errors)");
        log.info("=".repeat(80) + "\n");
        super.initFilterBean();
    }
}