package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ManageBookingResponse {
    private BookingResponse booking;
    private String businessName;
    private String businessSlug;
    private String businessEmail;
    private String businessPhone;
    private Integer cancellationNoticeHours;
    private Integer bookingAdvanceDays;
    private boolean canCancel;
    private boolean canReschedule;
}
