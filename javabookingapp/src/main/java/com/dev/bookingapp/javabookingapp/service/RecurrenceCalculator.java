package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns "every Tuesday at 2pm, 12 times" into the actual instants to book.
 *
 * <p>All arithmetic happens on the local wall-clock in the business timezone,
 * never on the {@link OffsetDateTime} directly. {@code OffsetDateTime.plusWeeks}
 * preserves the <em>offset</em>, so once the clocks change a 14:00 appointment
 * would silently become 13:00 for the rest of the series.
 */
@Component
public class RecurrenceCalculator {

    /**
     * @param first the slot the owner picked; always returned as occurrence one
     * @param zone  the business timezone, from {@link AvailabilityService#resolveZone(String)}
     * @return exactly {@code count} instants, in ascending order
     */
    public List<OffsetDateTime> occurrences(OffsetDateTime first,
                                            RecurrenceFrequency frequency,
                                            int count,
                                            ZoneId zone) {
        return occurrences(first, frequency, count, 1, 1, zone);
    }

    /**
     * @param intervalWeeks  gap between week-based occurrences (ignored for {@code MONTHLY})
     * @param intervalMonths gap between month-based occurrences (ignored for week-based frequencies)
     */
    public List<OffsetDateTime> occurrences(OffsetDateTime first,
                                            RecurrenceFrequency frequency,
                                            int count,
                                            int intervalWeeks,
                                            int intervalMonths,
                                            ZoneId zone) {
        ZonedDateTime start = first.atZoneSameInstant(zone);
        LocalTime timeOfDay = start.toLocalTime();
        int weeks = weeksBetween(frequency, intervalWeeks);

        List<OffsetDateTime> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            LocalDate date = dateFor(start.toLocalDate(), frequency, index, weeks, intervalMonths);
            result.add(date.atTime(timeOfDay).atZone(zone).toOffsetDateTime());
        }
        return result;
    }

    private int weeksBetween(RecurrenceFrequency frequency, int intervalWeeks) {
        if (frequency == RecurrenceFrequency.FORTNIGHTLY) {
            return 2;
        }
        return Math.max(1, intervalWeeks);
    }

    private LocalDate dateFor(LocalDate first,
                              RecurrenceFrequency frequency,
                              int index,
                              int intervalWeeks,
                              int intervalMonths) {
        return switch (frequency) {
            case WEEKLY, FORTNIGHTLY -> first.plusWeeks((long) index * intervalWeeks);
            case MONTHLY -> nthWeekdayOfMonth(first, index * Math.max(1, intervalMonths));
        };
    }

    /**
     * "2nd Tuesday of the month", carried forward {@code monthsAhead} months.
     */
    private LocalDate nthWeekdayOfMonth(LocalDate first, int monthsAhead) {
        int ordinal = (first.getDayOfMonth() - 1) / 7 + 1;
        LocalDate month = first.plusMonths(monthsAhead);

        return ordinal >= 5
                ? month.with(TemporalAdjusters.lastInMonth(first.getDayOfWeek()))
                : month.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, first.getDayOfWeek()));
    }
}
