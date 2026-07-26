package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of creating a standing appointment. Occurrences that clashed with
 * an existing booking are reported rather than failing the whole series, so the
 * owner can see exactly which dates need sorting out by hand.
 */
@Data
@Builder
public class RecurringBookingResponse {
    private UUID seriesId;
    private List<BookingResponse> created;
    private List<SkippedOccurrence> skipped;

    @Data
    @Builder
    public static class SkippedOccurrence {
        private OffsetDateTime startDatetime;
        private String reason;
    }
}
