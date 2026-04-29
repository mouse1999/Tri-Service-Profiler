package com.mouse.profiler.repository;

import com.mouse.profiler.entity.RefreshToken;
import com.mouse.profiler.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    /** Delete all tokens for a user (logout / deactivation). */
    void deleteByUser(User user);

    /** Scheduled cleanup — removes expired rows to keep the table lean. */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    void deleteAllExpiredBefore(Instant now);
}
