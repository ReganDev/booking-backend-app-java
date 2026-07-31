package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class PublicBookingRequest {

    // Retained for wire compatibility; authenticated account identity is authoritative.
    private CustomerRequest customer;

    @NotNull(message = "Service ID is required")
    private UUID serviceId;

    @NotNull(message = "Start date/time is required")
    private OffsetDateTime startDatetime;

    private String customerNotes;

    private Boolean emailReminder;

    private Boolean smsReminder;

    // Required when the service has requiresCustomerAddress (checked in service layer)
    @Size(max = 255, message = "Address line 1 must be less than 255 characters")
    private String addressLine1;

    @Size(max = 255, message = "Address line 2 must be less than 255 characters")
    private String addressLine2;

    @Size(max = 100, message = "City must be less than 100 characters")
    private String addressCity;

    @Size(max = 10, message = "Postcode must be less than 10 characters")
    private String addressPostcode;
}
