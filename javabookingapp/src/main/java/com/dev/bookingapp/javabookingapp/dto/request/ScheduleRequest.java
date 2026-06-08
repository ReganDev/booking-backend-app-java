package com.dev.bookingapp.javabookingapp.dto.request;

import com.dev.bookingapp.javabookingapp.entity.enums.DayOfWeek;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class ScheduleRequest {

    private UUID userId;  // NULL for business-wide schedule

    @NotNull(message = "Day of week is required")
    private DayOfWeek dayOfWeek;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private Boolean isActive;

    @Valid
    private List<ScheduleBreakRequest> breaks;
}
