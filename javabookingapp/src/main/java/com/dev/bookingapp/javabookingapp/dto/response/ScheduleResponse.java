package com.dev.bookingapp.javabookingapp.dto.response;

import com.dev.bookingapp.javabookingapp.entity.enums.DayOfWeek;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ScheduleResponse {
    private UUID id;
    private UUID businessId;
    private UUID userId;
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean isActive;
    private List<ScheduleBreakResponse> breaks;
}
