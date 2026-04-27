package com.mouse.profiler.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.profiler.dto.ErrorResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A Spring MVC {@link HandlerInterceptor} that enforces API versioning
 * via the {@code X-API-Version} request header.
 *
 * <p>Every request to {@code /api/**} must include the header:</p>
 * <pre>
 *   X-API-Version: 1
 * </pre>
 *
 * <p>If the header is missing or contains any value other than "1",
 * this interceptor short-circuits the request and returns a 400 Bad Request
 * before the request ever reaches a controller.</p>
 * <p>Registration: This interceptor is registered in  WebMvcConfig
 * and applied only to {@code /api/**} paths.</p>
 */
@Component
@RequiredArgsConstructor
public class ApiVersionInterceptor implements HandlerInterceptor {

    private static final String VERSION_HEADER = "X-API-Version";
    private static final String SUPPORTED_VERSION = "1";

    private final ObjectMapper objectMapper;


    /**
     * Intercepts every request before it reaches a controller method.
     *
     * <p>This method reads the {@code X-API-Version} header and decides
     * whether to allow the request through or reject it immediately.</p>
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

        // If header is missing or doesn't match the supported version
        if (!SUPPORTED_VERSION.equals(headerValue)) {

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");

            ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                    "error",
                    "API version header required"

            );

            objectMapper.writeValue(response.getWriter(), errorResponse);

            return false; // Block the request
        }

        return true; // Proceed with the request
    }
}
