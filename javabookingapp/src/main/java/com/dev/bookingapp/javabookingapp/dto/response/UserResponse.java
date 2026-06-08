package com.dev.bookingapp.javabookingapp.dto.response;

import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class UserResponse {
    private UUID id;
    private UUID businessId;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private String avatarUrl;
    private UserRole role;
    private Boolean acceptsBookings;
    private Boolean isActive;
    private Boolean emailVerified;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime createdAt;
}
