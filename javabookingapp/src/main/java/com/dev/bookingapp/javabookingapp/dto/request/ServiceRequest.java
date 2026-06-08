package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ServiceRequest {

    @NotBlank(message = "Service name is required")
    @Size(max = 255, message = "Service name must be less than 255 characters")
    private String name;

    private String description;

    @NotNull(message = "Duration is required")
    @Min(value = 5, message = "Duration must be at least 5 minutes")
    @Max(value = 480, message = "Duration must be less than 8 hours")
    private Integer durationMinutes;

    @DecimalMin(value = "0.00", message = "Price must be positive")
    private BigDecimal price;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color")
    private String color;

    private Integer displayOrder;

    private Boolean isActive;
}
