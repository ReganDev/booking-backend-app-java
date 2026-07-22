package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BookingOtpSessionRepository extends JpaRepository<BookingOtpSession, UUID> {

    Optional<BookingOtpSession> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE BookingOtpSession s SET s.attempts = s.attempts + 1 WHERE s.id = :id")
    void incrementAttempts(@Param("id") UUID id);
}
