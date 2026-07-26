package com.dev.bookingapp.javabookingapp.dto.request;

import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A standing appointment. {@code startDatetime} is the first occurrence; the
 * rest are derived from it.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RecurringBookingRequest extends BookingRequest {

    @NotNull(message = "Frequency is required")
    private RecurrenceFrequency frequency;

    /** Spacing between occurrences when {@code frequency} is {@code WEEKLY}. */
    @Min(value = 1, message = "Interval must be at least 1 week")
    @Max(value = 52, message = "Interval cannot exceed 52 weeks")
    private Integer intervalWeeks;

    /** Spacing between occurrences when {@code frequency} is {@code MONTHLY}. */
    @Min(value = 1, message = "Interval must be at least 1 month")
    @Max(value = 12, message = "Interval cannot exceed 12 months")
    private Integer intervalMonths;

    // Capped so a mis-typed number cannot generate hundreds of rows. 52 is a
    // year of weekly appointments, which is as far ahead as anyone plans.
    @Min(value = 2, message = "A repeating booking needs at least 2 occurrences")
    @Max(value = 52, message = "A repeating booking cannot exceed 52 occurrences")
    private int occurrenceCount;
}
