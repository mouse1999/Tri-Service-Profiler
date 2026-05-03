package com.mouse.profiler.controller;

import com.mouse.profiler.dto.jwt.TokenResponse;
import com.mouse.profiler.entity.RefreshToken;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.service.JwtService;
import com.mouse.profiler.service.RefreshTokenService;
import com.mouse.profiler.service.UserDetailsImpl;
import com.mouse.profiler.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Auth endpoints — all under /auth/** (open, no JWT required).
 *
 * This application uses GitHub OAuth for initial login.
 * The OAuth callback (handled by GitHubAuthController) issues the first token pair.
 * This controller handles:
 *
 *   POST /auth/refresh → single-use refresh token rotation
 *   POST /auth/logout  → revoke all refresh tokens for the user
 *   GET  /api/me  → get current authenticated user info (from cookie)
 *
 * There is intentionally NO /auth/login with username+password — credentials
 * are never held server-side.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    // ── Refresh (single-use rotation) ─────────────────────────────────────────

    /**
     * Validates the incoming refresh token, DELETES it (single-use), and
     * returns a brand-new access + refresh token pair.
     *
     * Replay protection: if the same token is presented twice the second
     * call gets 401 — the row was already deleted on first use.
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        if (refreshToken == null) return ResponseEntity.status(401).build();

        User user = refreshTokenService.rotate(refreshToken);
        UserDetailsImpl principal = new UserDetailsImpl(user);

        if (!principal.isEnabled()) return ResponseEntity.status(403).build();

        String newAccessToken = jwtService.generateAccessToken(principal);
        RefreshToken newRt = refreshTokenService.create(user);

        ResponseCookie newRefreshCookie = ResponseCookie.from("refresh_token", newRt.getToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth/refresh")
                .maxAge(Duration.ofDays(7))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                .body(new TokenResponse("success", newAccessToken, null));
    }


    /**
     * Invalidates the refresh token stored in the HTTP-only cookie.
     * Access tokens are short-lived (3 min) and expire naturally.
     * Clears the refresh_token cookie so it can never be used again.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        if (refreshToken != null) {
            refreshTokenService.invalidate(refreshToken);
        }

        ResponseCookie cleared = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth/refresh")
                .maxAge(0)  // immediately expire the cookie
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }



    /**
     * Returns the currently authenticated user's information.
     * The access token is read from the HTTP-only cookie.
     */
    @GetMapping("/auth/me")
    public ResponseEntity<?> getCurrentUser(
            @CookieValue(name = "access_token", required = false) String accessToken,
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        log.debug("Getting current user info");

        // Try access token first
        if (accessToken != null && jwtService.isValid(accessToken)) {
            String username = jwtService.extractUsername(accessToken);
            return userService.findByUsername(username)
                    .map(user -> ResponseEntity.ok(Map.of(
                            "username", user.getUsername(),
                            "avatarUrl", user.getAvatarUrl(),
                            "role", user.getRoles()
                    )))
                    .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // Try refresh token if access token is invalid
        if (refreshToken != null) {
            try {
                User user = refreshTokenService.rotate(refreshToken);
                UserDetailsImpl principal = new UserDetailsImpl(user);
                String newAccessToken = jwtService.generateAccessToken(principal);
                RefreshToken newRt = refreshTokenService.create(user);

                return ResponseEntity.ok(Map.of(
                        "username", user.getUsername(),
                        "avatarUrl", user.getAvatarUrl(),
                        "role", user.getRoles(),
                        "accessToken", newAccessToken,
                        "refreshToken", newRt.getToken()
                ));
            } catch (Exception e) {
                log.debug("Refresh token invalid: {}", e.getMessage());
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "error",
                "message", "Not authenticated"
        ));
    }


    /**
     * Creates HTTP-only cookies for access and refresh tokens.
     * this is used in GitHubAuthController after successful OAuth.
     */
    public static void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        Cookie accessCookie = new Cookie("access_token", accessToken);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(true);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(15 * 60);
        accessCookie.setAttribute("SameSite", "None");
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(7 * 24 * 60 * 60);
        refreshCookie.setAttribute("SameSite", "None");
        response.addCookie(refreshCookie);
    }
}
