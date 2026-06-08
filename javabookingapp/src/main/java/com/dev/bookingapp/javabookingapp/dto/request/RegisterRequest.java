package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    // Business info
    @NotBlank(message = "Business name is required")
    @Size(max = 255, message = "Business name must be less than 255 characters")
    private String businessName;

    // User info
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    private String password;

    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must be less than 100 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must be less than 100 characters")
    private String lastName;

    @Size(max = 50, message = "Phone must be less than 50 characters")
    private String phone;

    // Optional business settings
    private String timezone;
    private String currency;
}
