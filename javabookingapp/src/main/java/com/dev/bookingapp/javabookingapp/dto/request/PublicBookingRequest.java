package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class PublicBookingRequest {

    @Valid
    @NotNull(message = "Customer details are required")
    private CustomerRequest customer;

    @NotNull(message = "Service ID is required")
    private UUID serviceId;

    @NotNull(message = "Start date/time is required")
    private OffsetDateTime startDatetime;

    private String customerNotes;
}
