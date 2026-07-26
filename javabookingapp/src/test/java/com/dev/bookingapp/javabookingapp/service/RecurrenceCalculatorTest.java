package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceCalculatorTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final RecurrenceCalculator calculator = new RecurrenceCalculator();

    private static OffsetDateTime london(String date, String time) {
        return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), LONDON)
                .toOffsetDateTime();
    }

    private static List<LocalDate> datesIn(List<OffsetDateTime> occurrences) {
        return occurrences.stream()
                .map(occurrence -> occurrence.atZoneSameInstant(LONDON).toLocalDate())
                .toList();
    }

    private static List<LocalTime> wallClockTimesIn(List<OffsetDateTime> occurrences) {
        return occurrences.stream()
                .map(occurrence -> occurrence.atZoneSameInstant(LONDON).toLocalTime())
                .toList();
    }

    @Test
    void weeklyKeepsTheSameWeekdayAndReturnsExactlyTheCountAsked() {
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-08-04", "10:00"), RecurrenceFrequency.WEEKLY, 4, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 8, 25));
    }

    @Test
    void fortnightlySkipsEveryOtherWeek() {
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-08-04", "10:00"), RecurrenceFrequency.FORTNIGHTLY, 3, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 8, 18),
                LocalDate.of(2026, 9, 1));
    }

    @Test
    void weeklyKeepsWallClockTimeWhenTheClocksGoBack() {
        // 25 October 2026: UK clocks go back, BST (+01:00) to GMT (+00:00).
        // Adding weeks to an OffsetDateTime directly would carry +01:00 across
        // the boundary and turn a 14:00 appointment into 13:00.
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-10-20", "14:00"), RecurrenceFrequency.WEEKLY, 3, LONDON);

        assertThat(wallClockTimesIn(occurrences))
                .containsOnly(LocalTime.of(14, 0));
        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 27),
                LocalDate.of(2026, 11, 3));
        assertThat(occurrences.get(0).getOffset().getTotalSeconds()).isEqualTo(3600);
        assertThat(occurrences.get(1).getOffset().getTotalSeconds()).isZero();
    }

    @Test
    void weeklyKeepsWallClockTimeWhenTheClocksGoForward() {
        // 29 March 2026: GMT to BST.
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-03-24", "14:00"), RecurrenceFrequency.WEEKLY, 3, LONDON);

        assertThat(wallClockTimesIn(occurrences))
                .containsOnly(LocalTime.of(14, 0));
        assertThat(occurrences.get(0).getOffset().getTotalSeconds()).isZero();
        assertThat(occurrences.get(1).getOffset().getTotalSeconds()).isEqualTo(3600);
    }

    @Test
    void monthlyRepeatsTheSameWeekdayOfTheMonthNotTheSameDate() {
        // Tue 11 Aug 2026 is the second Tuesday.
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-08-11", "14:00"), RecurrenceFrequency.MONTHLY, 5, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 12, 8));
        assertThat(datesIn(occurrences))
                .allMatch(date -> date.getDayOfWeek() == java.time.DayOfWeek.TUESDAY);
    }

    @Test
    void monthlyTreatsAFifthWeekdayAsTheLastOfTheMonth() {
        // Wed 30 Sep 2026 is the fifth Wednesday. Most months have only four,
        // and dayOfWeekInMonth(5, ...) would roll silently into the next month.
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-09-30", "09:00"), RecurrenceFrequency.MONTHLY, 4, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 12, 30));
        assertThat(datesIn(occurrences))
                .allMatch(date -> date.getDayOfWeek() == java.time.DayOfWeek.WEDNESDAY);
    }

    @Test
    void monthlyKeepsWallClockTimeAcrossADstBoundary() {
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-10-13", "16:30"), RecurrenceFrequency.MONTHLY, 3, LONDON);

        assertThat(wallClockTimesIn(occurrences)).containsOnly(LocalTime.of(16, 30));
    }

    @Test
    void weeklySupportsCustomWeekIntervals() {
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-08-04", "10:00"), RecurrenceFrequency.WEEKLY, 3, 6, 1, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 8, 4),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 27));
    }

    @Test
    void monthlySupportsCustomMonthIntervals() {
        List<OffsetDateTime> occurrences = calculator.occurrences(
                london("2026-08-11", "14:00"), RecurrenceFrequency.MONTHLY, 3, 1, 2, LONDON);

        assertThat(datesIn(occurrences)).containsExactly(
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 12, 8));
    }

    @Test
    void firstOccurrenceIsAlwaysTheSlotThatWasPicked() {
        OffsetDateTime first = london("2026-08-04", "10:00");

        assertThat(calculator.occurrences(first, RecurrenceFrequency.MONTHLY, 3, LONDON))
                .first()
                .isEqualTo(first);
    }
}
