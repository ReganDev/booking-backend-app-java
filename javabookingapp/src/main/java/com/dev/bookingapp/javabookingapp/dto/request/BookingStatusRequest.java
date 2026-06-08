package com.dev.bookingapp.javabookingapp.dto.request;

import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BookingStatusRequest {

    @NotNull(message = "Status is required")
    private BookingStatus status;

    private String cancellationReason;
}
