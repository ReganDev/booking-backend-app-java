package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class CustomerResponse {
    private UUID id;
    private UUID businessId;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private String notes;
    private OffsetDateTime createdAt;
}
