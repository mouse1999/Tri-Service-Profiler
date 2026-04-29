package com.mouse.profiler.controller;

import com.mouse.profiler.dto.jwt.RefreshRequest;
import com.mouse.profiler.dto.jwt.TokenResponse;
import com.mouse.profiler.entity.RefreshToken;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.service.JwtService;
import com.mouse.profiler.service.RefreshTokenService;
import com.mouse.profiler.service.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Auth endpoints — all under /auth/** (open, no JWT required).
 *
 * This application uses GitHub OAuth for initial login.
 * The OAuth callback (handled separately by the OAuth2 success handler)
 * issues the first token pair. This controller handles:
 *
 *   POST /auth/refresh  → single-use refresh token rotation
 *   POST /auth/logout   → revoke all refresh tokens for the user
 *
 * There is intentionally NO /auth/login with username+password — credentials
 * are never held server-side.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;


    // ── Refresh (single-use rotation)

    /**
     * Validates the incoming refresh token, DELETES it (single-use), and
     * returns a brand-new access + refresh token pair.
     *
     * Replay protection: if the same token is presented twice the second
     * call gets 401 — the row was already deleted on first use.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        User user = refreshTokenService.rotate(req.refreshToken());

        UserDetailsImpl principal = new UserDetailsImpl(user);

        // Inactive Guard, user may have been deactivated between calls
        if (!principal.isEnabled()) {
            return ResponseEntity
                    .status(403)
                    .build();
        }

        String newAccessToken = jwtService.generateAccessToken(principal);
        RefreshToken newRt = refreshTokenService.create(user);

        return ResponseEntity
                .ok(new TokenResponse(newAccessToken, newRt.getToken()));
    }

    // ── Logout

    /**
     * Revokes ALL refresh tokens for the user identified by the presented token.
     * Access tokens are short-lived (3 min) and expire naturally.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest req) {
        User user = refreshTokenService.rotate(req.refreshToken());
        refreshTokenService.revokeAll(user);
        return ResponseEntity.noContent().build();
    }
}
