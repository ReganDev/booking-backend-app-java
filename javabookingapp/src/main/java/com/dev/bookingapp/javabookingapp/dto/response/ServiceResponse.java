package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class ServiceResponse {
    private UUID id;
    private UUID businessId;
    private String name;
    private String description;
    private Integer durationMinutes;
    private BigDecimal price;
    private String color;
    private Integer displayOrder;
    private Boolean isActive;
}
