package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    private Boolean emailReminder;
    private Boolean smsReminder;

    // Customer address for mobile-visit services (optional on the owner path)
    @Size(max = 255, message = "Address line 1 must be less than 255 characters")
    private String addressLine1;

    @Size(max = 255, message = "Address line 2 must be less than 255 characters")
    private String addressLine2;

    @Size(max = 100, message = "City must be less than 100 characters")
    private String addressCity;

    @Size(max = 10, message = "Postcode must be less than 10 characters")
    private String addressPostcode;
}
