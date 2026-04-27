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
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A servlet filter that enforces rate limiting using Bucket4j with a
 * Redis backend via Lettuce.
 *
 * <p>Rate limiting strategy:</p>
 * <ul>
 *   <li>Auth routes ({@code /auth/**}): 10 requests per minute per IP</li>
 *   <li>All other routes: 60 requests per minute per IP</li>
 * </ul>
 *
 * <p>How Bucket4j works in this filter:</p>
 * <ol>
 *   <li>Each incoming IP address gets its own token bucket stored in Redis.</li>
 *   <li>The filter calls {@code tryConsumeAndReturnRemaining(1)} which
 *       atomically tries to take one token from the bucket.</li>
 *   <li>If the bucket has tokens, the request is allowed and the token
 *       is deducted.</li>
 *   <li>If the bucket is empty, the request is rejected with 429 and
 *       the response includes a {@code Retry-After} header telling the
 *       client how many seconds to wait.</li>
 * </ol>
 *
 * <p>{@code @Order(2)} means this runs after the logging filter ({@code @Order(1)})
 * so that even rejected requests are logged before being blocked.</p>
 */
@Component
@RequiredArgsConstructor
@Order(2)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/auth/";

    private final ProxyManager<String> proxyManager;
    private final Supplier<BucketConfiguration> authBucketConfiguration;
    private final Supplier<BucketConfiguration> apiBucketConfiguration;
    private final ObjectMapper objectMapper;


    /**
     * Core filter method. Resolves which bucket to use, attempts to
     * consume a token, and either allows or rejects the request.
     * @param request  the incoming HTTP request
     * @param response  the HTTP response to write to on rejection
     * @param filterChain the remaining filter chain
     * @throws ServletException if a downstream servlet error occurs
     * @throws IOException      if writing the response body fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Get client identifier (IP)
        String clientId = resolveClientIdentifier(request);

        // Determine if this is an auth route
        boolean isAuthPath = request.getRequestURI() != null &&
                request.getRequestURI().startsWith(AUTH_PATH_PREFIX);

        // Step 3: Build bucket key (separate buckets for auth and api per IP)
        String bucketKey = (isAuthPath ? "auth:" : "api:") + clientId;

        // Choose the appropriate bucket configuration
        Supplier<BucketConfiguration> configSupplier =
                isAuthPath ? authBucketConfiguration : apiBucketConfiguration;

        // Get or create bucket from Redis
        Bucket bucket = proxyManager.builder()
                .build(bucketKey, configSupplier);

        // Try to consume 1 token
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Request allowed
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));

            filterChain.doFilter(request, response);
        } else {
            // Rate limit exceeded
            rejectRequest(request, response, probe);
        }
    }

    /**
     * Resolves the best available identifier for the incoming request.
     *
     * <p>Why not always use the raw IP?</p>
     * <ul>
     *   <li>When your backend sits behind a reverse proxy (Nginx, a load
     *       balancer, Cloudflare), the IP seen by your server is the proxy's
     *       IP — every user appears to come from the same address.</li>
     *   <li>The real client IP is forwarded in the {@code X-Forwarded-For}
     *       header by the proxy.</li>
     * </ul>
     * @param request the incoming HTTP request
     * @return a string identifying the client, used as the Redis bucket key
     */
    private String resolveClientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");

        if (forwarded != null && !forwarded.isBlank()) {
            // Take the first IP if multiple are present (leftmost = original client)
            return forwarded.split(",")[0].trim();
        }
        // Fallback to remote address
        return request.getRemoteAddr();
    }

    /**
     * Writes a {@code 429 Too Many Requests} response with a JSON body
     * and a {@code Retry-After} header.
     *
     * <p>The {@code Retry-After} header tells the client exactly how many
     * seconds to wait before trying again. This is calculated from
     * {@code probe.getNanosToWaitForRefill()} which Bucket4j provides
     * automatically based on the bucket's refill schedule.</p>
     * @param request  the original request (for the path)
     * @param response the response to write the 429 body into
     * @param probe the Bucket4j probe containing refill timing information
     * @throws IOException if writing to the response output stream fails
     */
    private void rejectRequest(HttpServletRequest request,
                               HttpServletResponse response,
                               ConsumptionProbe probe) throws IOException {

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(
                probe.getNanosToWaitForRefill());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));

        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                "error",
                "Too Many Requests"
        );

        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
