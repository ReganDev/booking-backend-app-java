package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class BookingRescheduleRequest {

    @NotNull(message = "New start time is required")
    private OffsetDateTime startDatetime;
}
