package com.mouse.profiler.controller;

import com.mouse.profiler.dto.RefreshRequest;
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

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;

    /**
     * Validates the incoming refresh token, DELETES it (single-use), and
     * returns a brand-new access + refresh token pair.
     *
     * Web:  reads refresh token from HTTP-only cookie, sets new cookies
     * CLI:  reads refresh token from request body, returns full JSON TokenResponse
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String cookieRefreshToken,
            @RequestBody(required = false) RefreshRequest body,
            HttpServletResponse response
    ) {
        boolean isCli = cookieRefreshToken == null && body != null && body.refreshToken() != null;
        String refreshToken = isCli ? body.refreshToken() : cookieRefreshToken;

        if (refreshToken == null)
            return ResponseEntity
                    .status(401).build();

        User user = refreshTokenService.rotate(refreshToken);
        UserDetailsImpl principal = new UserDetailsImpl(user);

        if (!principal.isEnabled())
            return ResponseEntity
                    .status(403).build();

        String newAccessToken = jwtService.generateAccessToken(principal);
        RefreshToken newRt = refreshTokenService.create(user);

        if (isCli) {
            // CLI — return full token pair as JSON, no cookies
            return ResponseEntity
                    .ok(new TokenResponse("success",
                            newAccessToken, newRt.getToken()));
        }

        // Web — set HTTP-only cookies, omit tokens from body
        ResponseCookie newRefreshCookie = ResponseCookie.from("refresh_token", newRt.getToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/auth/refresh")
                .maxAge(Duration.ofDays(7))
                .build();

        ResponseCookie newAccessCookie = ResponseCookie.from("access_token", newAccessToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(Duration.ofMinutes(15))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                .header(HttpHeaders.SET_COOKIE, newAccessCookie.toString())
                .body(new TokenResponse("success", newAccessToken, null));
    }

    /**
     * Invalidates the refresh token stored in the HTTP-only cookie.
     * Access tokens are short-lived and expire naturally.
     * Clears both cookies so they can never be used again.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        if (refreshToken != null) {
            refreshTokenService.invalidate(refreshToken);
        }

        ResponseCookie clearedRefresh = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/auth/refresh")
                .maxAge(0)
                .build();

        ResponseCookie clearedAccess = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefresh.toString())
                .header(HttpHeaders.SET_COOKIE, clearedAccess.toString())
                .build();
    }

    /**
     * Returns the currently authenticated user's information.
     * Tries access token cookie first, then silently rotates via refresh token cookie.
     */
    @GetMapping("/auth/me")
    public ResponseEntity<?> getCurrentUser(
            @CookieValue(name = "access_token", required = false) String accessToken,
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response
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

        // Access token expired — try silent refresh via cookie
        if (refreshToken != null) {
            try {
                User user = refreshTokenService.rotate(refreshToken);
                UserDetailsImpl principal = new UserDetailsImpl(user);
                String newAccessToken = jwtService.generateAccessToken(principal);
                RefreshToken newRt = refreshTokenService.create(user);

                // Set new cookies silently — no tokens in response body
                setAuthCookies(response, newAccessToken, newRt.getToken());

                return ResponseEntity.ok(Map.of(
                        "username", user.getUsername(),
                        "avatarUrl", user.getAvatarUrl(),
                        "role", user.getRoles()
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
     * Used in GitHubAuthController after successful OAuth and in silent refresh.
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
        refreshCookie.setPath("/auth/refresh");
        refreshCookie.setMaxAge(7 * 24 * 60 * 60);
        refreshCookie.setAttribute("SameSite", "None");
        response.addCookie(refreshCookie);
    }
}