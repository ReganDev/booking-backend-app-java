package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

@Data
public class GuestBookingVerifyRequest {

    @NotNull(message = "Booking session ID is required")
    private UUID bookingSessionId;

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
    private String code;
}
