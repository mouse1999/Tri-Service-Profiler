package com.mouse.profiler.filter;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A servlet filter that logs structured information about every HTTP request
 * that passes through the application.
 *
 * <p>Extends {@link OncePerRequestFilter} which guarantees this filter runs
 * exactly once per request — important because in some servlet container
 * configurations a filter can be invoked multiple times for the same request
 * (e.g. during async dispatch). OncePerRequestFilter prevents that.</p>
 *
 * <p>This filter runs before controllers and after the request is received.
 * It wraps the entire filter chain inside a try-finally block so that the
 * log entry is always written, even if the request throws an exception.</p>
 *
 * <p>Logged fields for every request:</p>
 * <ul>
 *   <li>{@code method}   — HTTP verb (GET, POST, etc.)</li>
 *   <li>{@code endpoint} — request URI (e.g. /api/v1/users)</li>
 *   <li>{@code status}   — HTTP response status code</li>
 *   <li>{@code duration} — total time in milliseconds from filter entry
 *                          to filter exit</li>
 * </ul>
 *
 * <p>Example log output:</p>
 * <pre>
 *   INFO  RequestLoggingFilter - method=POST endpoint=/auth/login status=200 duration=143ms
 * </pre>
 *
 * <p>{@code @Order(1)} ensures this filter runs first — before rate limiting
 * or any other filter — so every request is logged regardless of whether it
 * is later rejected by another filter.</p>
 */
@Component
@Slf4j
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {


    /**
     * Core filter method. Called exactly once per request.
     * @param request     the incoming HTTP request
     * @param response    the outgoing HTTP response
     * @param filterChain the remaining filter chain to invoke
     * @throws ServletException if a servlet error occurs downstream
     * @throws IOException      if an I/O error occurs downstream
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startTime = System.currentTimeMillis();
        String method = request.getMethod();
        String endpoint = request.getRequestURI();

        try {
            // Pass the request down the filter chain
            filterChain.doFilter(request, response);
        } finally {
            // Always log the request, even if an exception occurred
            long duration = System.currentTimeMillis() - startTime;
            int status = response.getStatus();

            log.info("method={} endpoint={} status={} duration={}ms",
                    method, endpoint, status, duration);
        }
    }
}
