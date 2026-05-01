package com.mouse.profiler.service;


import com.fasterxml.uuid.Generators;
import com.mouse.profiler.entity.RefreshToken;
import com.mouse.profiler.entity.User;
import com.mouse.profiler.exception.TokenException;
import com.mouse.profiler.securityprop.JwtProperties;
import com.mouse.profiler.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages the Refresh Token lifecycle with SINGLE-USE ROTATION.
 *
 * Rotation contract:
 * <ol>
 *   <li>Client presents an opaque refresh token string.</li>
 *   <li>We look it up in the DB and validate (not expired, not revoked).</li>
 *   <li>We DELETE the old row immediately — it is now invalid.</li>
 *   <li>We create a NEW refresh token and return it alongside the new access token.</li>
 * </ol>
 *
 * If a refresh token is replayed (already deleted), the lookup will fail → 401.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repo;
    private final JwtProperties props;

    // ── Create

    /**
     * Creates and persists a new single-use refresh token for the given user.
     */
    @Transactional
    public RefreshToken create(User user) {
        RefreshToken rt = RefreshToken.builder()
                .token(Generators.timeBasedEpochGenerator().generate().toString())   // opaque random value
                .user(user)
                .expiresAt(Instant.now().plus(props.getRefreshTokenTtl()))
                .build();
        return repo.save(rt);
    }

    // ── Rotate (single-use)

    /**
     * Validates the incoming refresh token, deletes it (single-use), and returns
     * the associated {@link User} so the caller can issue a new token pair.
     *
     * @param rawToken opaque refresh token string from the client
     * @return the User associated with the token
     * @throws TokenException if token is unknown, expired, or revoked
     */
    @Transactional
    public User rotate(String rawToken) {
        RefreshToken rt = repo.findByToken(rawToken)
                .orElseThrow(() -> new TokenException("Refresh token not found or already used"));

        if (rt.isRevoked()) {
            // Token was explicitly revoked could be a possible replay attack. Wipe all tokens for this user.
            log.warn("Revoked refresh token replayed for user {}. Wiping all tokens.", rt.getUser().getId());
            repo.deleteByUser(rt.getUser());
            throw new TokenException("Refresh token has been revoked");
        }

        if (rt.isExpired()) {
            repo.delete(rt);
            throw new TokenException("Refresh token has expired");
        }

        User owner = rt.getUser();

        // delete the old token immediately
        repo.delete(rt);

        return owner;
    }

    // ── Revoke

    /** Revokes all refresh tokens for a user (logout). */
    @Transactional
    public void revokeAll(User user) {
        repo.deleteByUser(user);
    }

    /**
     * Invalidates a single refresh token for logout.
     * Unlike rotate(), this does not return a new token pair — it simply
     * deletes the token so it can never be used again.
     *
     * @param rawToken the refresh token string from the client
     * @throws TokenException if the token is not found or already expired/revoked
     */
    @Transactional
    public void invalidate(String rawToken) {
        RefreshToken rt = repo.findByToken(rawToken)
                .orElseThrow(() -> new TokenException("Refresh token not found or already used"));
        repo.delete(rt);
        log.debug("Refresh token invalidated for user {}", rt.getUser().getId());
    }

    // ── Scheduled Cleanup

    /** Hourly sweep to remove expired rows — keeps the table from growing unbounded. */
    @Scheduled(fixedRateString = "PT1H")
    @Transactional
    public void purgeExpiredTokens() {
        repo.deleteAllExpiredBefore(Instant.now());
        log.debug("Purged expired refresh tokens");
    }
}