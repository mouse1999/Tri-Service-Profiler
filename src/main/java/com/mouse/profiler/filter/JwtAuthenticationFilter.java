package com.mouse.profiler.filter;

import com.mouse.profiler.service.JwtService;
import com.mouse.profiler.service.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

/**
 * Stateless JWT filter — runs once per request.
 *
 * Flow:
 * <ol>
 *   <li>Extract {@code Authorization: Bearer <token>} header.</li>
 *   <li>Validate signature + expiry via {@link JwtService}.</li>
 *   <li>Load full {@link UserDetails} (triggers isEnabled / Inactive Guard).</li>
 *   <li>Set {@link SecurityContextHolder} so downstream code sees the principal.</li>
 * </ol>
 *
 * CLI compatibility: CLI tools just need to send the standard
 * {@code Authorization: Bearer <accessToken>} header — no cookies required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Bearer token, skip Spring Security will enforce access control
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // Already authenticated in this request — nothing to do
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
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
                            null,                           // credentials null,already verified via JWT
                            userDetails.getAuthorities()    // Role, GrantedAuthority mapping applied here
                    );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        } else {
            // isActive == false do not authenticate; let Security return 403
            log.warn("Blocked inactive user '{}' attempting JWT access", username);
        }

        chain.doFilter(request, response);
    }
}