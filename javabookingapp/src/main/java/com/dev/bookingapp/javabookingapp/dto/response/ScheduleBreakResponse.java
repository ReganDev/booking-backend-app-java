package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
public class ScheduleBreakResponse {
    private UUID id;
    private LocalTime startTime;
    private LocalTime endTime;
    private String label;
}
