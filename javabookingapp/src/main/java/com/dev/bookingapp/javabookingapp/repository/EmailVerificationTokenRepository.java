package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("""
            UPDATE EmailVerificationToken t
               SET t.revokedAt = :now
             WHERE t.user.id = :userId
               AND t.consumedAt IS NULL
               AND t.revokedAt IS NULL
            """)
    void revokeActiveByUserId(@Param("userId") UUID userId,
                              @Param("now") OffsetDateTime now);
}
