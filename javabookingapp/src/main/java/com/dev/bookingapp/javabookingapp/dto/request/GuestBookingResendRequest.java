package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class GuestBookingResendRequest {

    @NotNull(message = "Booking session ID is required")
    private UUID bookingSessionId;
}
