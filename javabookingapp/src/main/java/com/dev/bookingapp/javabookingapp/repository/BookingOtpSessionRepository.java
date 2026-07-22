package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingOtpSessionRepository extends JpaRepository<BookingOtpSession, UUID> {

    Optional<BookingOtpSession> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
