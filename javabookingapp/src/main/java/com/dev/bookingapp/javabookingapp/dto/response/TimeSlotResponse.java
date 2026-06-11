package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSlotResponse {

    private OffsetDateTime startDatetime;

    private OffsetDateTime endDatetime;
}
