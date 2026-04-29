package com.mouse.profiler.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted Refresh Token.
 *
 * Single-use rotation: when this token is consumed to issue a new Access Token,
 * the row is deleted immediately (see RefreshTokenService#rotate).
 *
 * Expiry is enforced both at DB level (scheduled cleanup) and in service logic.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Opaque random token string stored in the DB.
     * Never derived from the Access Token — independent random UUID.
     */
    @Column(name = "token", unique = true, nullable = false)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Expiry instant. Refresh tokens are short-lived (5 minutes per spec).
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Soft-revoked marker — set true on logout / family compromise. */
    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private boolean revoked = false;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
