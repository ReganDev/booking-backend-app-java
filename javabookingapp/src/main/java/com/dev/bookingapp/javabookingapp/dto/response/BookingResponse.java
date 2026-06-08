package com.dev.bookingapp.javabookingapp.dto.response;

import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class BookingResponse {
    private UUID id;
    private UUID businessId;
    private BookingStatus status;
    private OffsetDateTime startDatetime;
    private OffsetDateTime endDatetime;
    private BigDecimal price;
    private String customerNotes;
    private String internalNotes;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;
    private OffsetDateTime createdAt;

    // Nested info
    private CustomerInfo customer;
    private ServiceInfo service;
    private StaffInfo staff;

    @Data
    @Builder
    public static class CustomerInfo {
        private UUID id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
    }

    @Data
    @Builder
    public static class ServiceInfo {
        private UUID id;
        private String name;
        private Integer durationMinutes;
        private BigDecimal price;
        private String color;
    }

    @Data
    @Builder
    public static class StaffInfo {
        private UUID id;
        private String firstName;
        private String lastName;
        private String fullName;
    }
}
