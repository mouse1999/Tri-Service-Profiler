package com.mouse.profiler.filter;

import com.mouse.profiler.service.JwtService;
import com.mouse.profiler.service.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * Stateless JWT filter — runs once per request.
 *
 * Token resolution order (first valid token wins):
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} header — CLI and direct API consumers.</li>
 *   <li>{@code access_token} HTTP-only cookie — browser flow set by OAuth callback.</li>
 * </ol>
 *
 * This dual-source approach means:
 * <ul>
 *   <li>Browser: tokens live in HTTP-only cookies, never accessible to JS.</li>
 *   <li>CLI / API clients: standard Bearer header, no cookies needed.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX   = "Bearer ";
    private static final String COOKIE_NAME     = "access_token";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        // Already authenticated in this request — nothing to do
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String token = resolveToken(request);

        if (token == null) {
            // No token found — let Spring Security enforce access control downstream
            chain.doFilter(request, response);
            return;
        }

        if (!jwtService.isValid(token)) {
            log.debug("Invalid JWT received from {}", request.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }

        String username = jwtService.extractUsername(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        if (userDetails.isEnabled()) {
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } else {
            // isActive == false — do not authenticate; Security returns 403
            log.warn("Blocked inactive user '{}' attempting JWT access", username);
        }

        chain.doFilter(request, response);
    }

    /**
     * Resolves the JWT from the request.
     * Checks the Authorization header first (CLI/API), then falls back to the
     * HTTP-only cookie (browser flow).
     *
     * @return raw JWT string, or {@code null} if not present in either source
     */
    private String resolveToken(HttpServletRequest request) {
        // 1. Authorization: Bearer <token>  (CLI / direct API consumers)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }

        // 2. HTTP-only cookie  (browser flow — set by /auth/github/callback redirect)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            return Arrays.stream(cookies)
                    .filter(c -> COOKIE_NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }
}