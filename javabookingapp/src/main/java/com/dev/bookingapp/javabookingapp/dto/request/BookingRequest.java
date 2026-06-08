package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class BookingRequest {

    @NotNull(message = "Customer ID is required")
    private UUID customerId;

    @NotNull(message = "Service ID is required")
    private UUID serviceId;

    private UUID staffId;

    @NotNull(message = "Start date/time is required")
    private OffsetDateTime startDatetime;

    private String customerNotes;
    private String internalNotes;
}
