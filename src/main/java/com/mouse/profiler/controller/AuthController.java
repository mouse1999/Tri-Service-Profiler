package com.mouse.profiler.controller;

import com.mouse.profiler.dto.jwt.RefreshRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth endpoints — all under /auth/** (open, no JWT required).
 *
 * This application uses GitHub OAuth for initial login.
 * The OAuth callback (handled by GitHubAuthController) issues the first token pair.
 * This controller handles:
 *
 *   POST /auth/refresh  → single-use refresh token rotation
 *   POST /auth/logout   → revoke all refresh tokens for the user
 *   GET  /api/me        → get current authenticated user info (from cookie)
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
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        User user = refreshTokenService.rotate(req.refreshToken());

        UserDetailsImpl principal = new UserDetailsImpl(user);

        if (!principal.isEnabled()) {
            return ResponseEntity.status(403).build();
        }

        String newAccessToken = jwtService.generateAccessToken(principal);
        RefreshToken newRt = refreshTokenService.create(user);

        return ResponseEntity.ok(new TokenResponse(newAccessToken, newRt.getToken()));
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Revokes ALL refresh tokens for the user identified by the presented token.
     * Access tokens are short-lived (3 min) and expire naturally.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest req) {
        User user = refreshTokenService.rotate(req.refreshToken());
        refreshTokenService.revokeAll(user);
        return ResponseEntity.noContent().build();
    }

    // ── Get Current User (from cookie) ────────────────────────────────────────

    /**
     * Returns the currently authenticated user's information.
     * The access token is read from the HTTP-only cookie.
     */
    @GetMapping("/api/me")
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

    // ── Set Cookies Helper (for GitHubAuthController to use) ─────────────────

    /**
     * Creates HTTP-only cookies for access and refresh tokens.
     * Use this in your GitHubAuthController after successful OAuth.
     */
    public static void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        Cookie accessCookie = new Cookie("access_token", accessToken);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(true);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(15 * 60); // 15 minutes
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(7 * 24 * 60 * 60); // 7 days
        response.addCookie(refreshCookie);
    }
}