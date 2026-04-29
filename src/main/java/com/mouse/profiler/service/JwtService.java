package com.mouse.profiler.service;

import com.mouse.profiler.jwt.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Stateless JWT Service — handles only ACCESS tokens.
 *
 * Token layout:
 * <pre>
 * {
 *   "sub"  : "username",
 *   "uid"  : "uuid-of-user",
 *   "roles": ["ROLE_USER", "ROLE_ADMIN"],
 *   "iat"  : ...,
 *   "exp"  : ... (now + 3 min)
 * }
 * </pre>
 *
 * Refresh tokens are opaque random UUIDs managed by RefreshTokenService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLES   = "roles";

    private final JwtProperties props;

    // ── Token Generation

    /**
     * Issues a signed Access Token for the given principal.
     *
     * @param principal authenticated {@link UserDetailsImpl}
     * @return compact JWT string — embed directly in Authorization: Bearer <token>
     */
    public String generateAccessToken(UserDetailsImpl principal) {
        Instant now    = Instant.now();
        Instant expiry = now.plus(props.getAccessTokenTtl());

        List<String> roles = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .subject(principal.getUsername())
                .claim(CLAIM_USER_ID, principal.getUserId().toString())
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    // ── Token Validation

    /**
     * Validates signature and expiry.
     *
     * @return true if valid; false on any error (expired, tampered, malformed)
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            return false;
        }
    }

    // ── Claim Extraction

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        String raw = parseClaims(token).get(CLAIM_USER_ID, String.class);
        return UUID.fromString(raw);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return parseClaims(token).get(CLAIM_ROLES, List.class);
    }


    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        // Derive a 256-bit HMAC key from the configured secret string.
        byte[] keyBytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
