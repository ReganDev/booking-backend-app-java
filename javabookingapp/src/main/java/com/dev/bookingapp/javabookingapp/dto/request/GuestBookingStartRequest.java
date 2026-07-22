package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class GuestBookingStartRequest {

    @NotNull(message = "Business ID is required")
    private UUID businessId;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    private String phone;

    @NotNull(message = "Service ID is required")
    private UUID serviceId;

    @NotNull(message = "Start date/time is required")
    private OffsetDateTime startDatetime;

    private String customerNotes;

    private Boolean emailReminder;

    private Boolean smsReminder;
}
