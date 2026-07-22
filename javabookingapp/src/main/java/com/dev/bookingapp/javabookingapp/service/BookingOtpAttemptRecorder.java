package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.repository.BookingOtpSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Persists failed-attempt increments in their own transaction so the count
 *  survives the rollback caused by the wrong-code BadRequestException. */
@Component
@RequiredArgsConstructor
public class BookingOtpAttemptRecorder {

    private final BookingOtpSessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID sessionId) {
        sessionRepository.incrementAttempts(sessionId);
    }
}
