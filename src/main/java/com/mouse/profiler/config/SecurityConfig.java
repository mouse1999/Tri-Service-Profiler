package com.mouse.profiler.config;

import com.mouse.profiler.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration for Insighta.
 *
 * Authentication model: GitHub OAuth — no username/password login.
 * The OAuth2 success handler (separate) issues the first JWT pair.
 * Subsequent requests are authenticated stateless via Bearer tokens.
 *
 * Rules:
 * <ul>
 *   <li>{@code /auth/**} and {@code /public/**} are open (no token required).</li>
 *   <li>Everything else requires a valid, non-expired JWT for an active user.</li>
 *   <li>Stateless — no HTTP session, no CSRF.</li>
 *   <li>Inactive accounts get 403 via the Inactive Guard in
 *       {@link JwtAuthenticationFilter}.</li>
 * </ul>
 *
 * <b>RBAC (Role-Based Access Control):</b>
 * <ul>
 *   <li>ADMIN: Full access - can create, read, delete profiles</li>
 *   <li>ANALYST: Read-only - can only read and search profiles</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize or @Secured on controllers
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String[] PUBLIC_PATHS = {
            "/auth/**",
            "/public/**",
            "/oauth2/**",           // GitHub OAuth callback
            "/login/oauth2/**",
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No CSRF — stateless JWT API
                .csrf(AbstractHttpConfigurer::disable)

                // Stateless — no sessions
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization with RBAC
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (no authentication required)
                        .requestMatchers(PUBLIC_PATHS).permitAll()


                        // CREATE PROFILE - ADMIN only (POST /api/profiles)
                        .requestMatchers(HttpMethod.POST, "/api/profiles").hasRole("ADMIN")

                        // DELETE PROFILE - ADMIN only (DELETE /api/profiles/{id})
                        .requestMatchers(HttpMethod.DELETE, "/api/profiles/**").hasRole("ADMIN")

                        // READ PROFILES - Both ADMIN and ANALYST (GET endpoints)
                        .requestMatchers(HttpMethod.GET, "/api/profiles/**").hasAnyRole("ADMIN", "ANALYST")

                        // SEARCH PROFILES - Both ADMIN and ANALYST
                        .requestMatchers(HttpMethod.GET, "/api/profiles/search").hasAnyRole("ADMIN", "ANALYST")

                        // EXPORT PROFILES - Both ADMIN and ANALYST
                        .requestMatchers(HttpMethod.GET, "/api/profiles/export").hasAnyRole("ADMIN", "ANALYST")

                        // GET single profile - Both ADMIN and ANALYST
                        .requestMatchers(HttpMethod.GET, "/api/profiles/{id}").hasAnyRole("ADMIN", "ANALYST")

                        // GET all profiles - Both ADMIN and ANALYST
                        .requestMatchers(HttpMethod.GET, "/api/profiles/all").hasAnyRole("ADMIN", "ANALYST")

                        // All other endpoints require authentication (any role)
                        .anyRequest().authenticated()
                )

                // 401 for missing/invalid/expired token (no redirect to login form)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // 403 for inactive accounts or insufficient role
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json");
                            res.getWriter().write("{\"status\":\"error\",\"message\":\"Access Denied\"}");
                        })
                )

                // JWT filter runs before standard auth filter
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}