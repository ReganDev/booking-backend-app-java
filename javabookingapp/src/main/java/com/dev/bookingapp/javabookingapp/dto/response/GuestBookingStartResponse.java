package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
public class GuestBookingStartResponse {
    private UUID bookingSessionId;
    private OffsetDateTime expiresAt;
}
