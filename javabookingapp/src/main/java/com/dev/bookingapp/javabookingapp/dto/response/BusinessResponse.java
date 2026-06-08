package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class BusinessResponse {
    private UUID id;
    private String name;
    private String slug;
    private String description;
    private String email;
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
    private Boolean isActive;
}
