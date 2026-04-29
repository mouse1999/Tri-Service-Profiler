package com.mouse.profiler.config;


import com.mouse.profiler.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

                // Authorization
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )

                // 401 for missing/invalid/expired token (no redirect to login form)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // 403 for inactive accounts or insufficient role
                        .accessDeniedHandler((req, res, e) ->
                                res.sendError(HttpStatus.FORBIDDEN.value(), "Access Denied"))
                )

                // JWT filter runs before standard auth filter
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
