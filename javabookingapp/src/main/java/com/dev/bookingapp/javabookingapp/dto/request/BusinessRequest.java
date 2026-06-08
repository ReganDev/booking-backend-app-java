package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BusinessRequest {

    @NotBlank(message = "Business name is required")
    @Size(max = 255, message = "Business name must be less than 255 characters")
    private String name;

    @Size(max = 100, message = "Slug must be less than 100 characters")
    private String slug;

    private String description;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Size(max = 50, message = "Phone must be less than 50 characters")
    private String phone;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String timezone;
    private String currency;
    private String logoUrl;
    private Integer bookingAdvanceDays;
    private Integer bookingNoticeHours;
    private Integer cancellationNoticeHours;
    private Integer slotDurationMinutes;
    private Integer bufferMinutes;
}
