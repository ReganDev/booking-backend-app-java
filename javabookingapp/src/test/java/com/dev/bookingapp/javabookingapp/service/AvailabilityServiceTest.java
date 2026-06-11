package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.TimeSlotResponse;
import com.dev.bookingapp.javabookingapp.entity.BlockedTime;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Schedule;
import com.dev.bookingapp.javabookingapp.entity.ScheduleBreak;
import com.dev.bookingapp.javabookingapp.entity.enums.DayOfWeek;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.repository.BlockedTimeRepository;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/London");

    @Mock
    private BusinessService businessService;
    @Mock
    private ServiceService serviceService;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BlockedTimeRepository blockedTimeRepository;

    @InjectMocks
    private AvailabilityService availabilityService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .slug("absolutelyfabuloushairandbeauty")
                .email("salon@example.com")
                .timezone("Europe/London")
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .bookingNoticeHours(0)
                .bookingAdvanceDays(30)
                .isActive(true)
                .build();

        service = com.dev.bookingapp.javabookingapp.entity.Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(30)
                .isActive(true)
                .build();

        tomorrow = LocalDate.now(ZONE).plusDays(1);
    }

    private Schedule scheduleFor(LocalDate date, LocalTime start, LocalTime end) {
        return Schedule.builder()
                .id(UUID.randomUUID())
                .business(business)
                .dayOfWeek(DayOfWeek.valueOf(date.getDayOfWeek().name()))
                .startTime(start)
                .endTime(end)
                .isActive(true)
                .build();
    }

    private void stubLookups() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
    }

    private void stubSchedule(Schedule schedule) {
        when(scheduleRepository.findByBusinessIdAndUserIsNullAndDayOfWeek(
                business.getId(), DayOfWeek.valueOf(tomorrow.getDayOfWeek().name())))
                .thenReturn(Optional.ofNullable(schedule));
    }

    private void stubEmptyConflicts() {
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(blockedTimeRepository.findBusinessBlockedTimesInRange(any(), any(), any()))
                .thenReturn(List.of());
    }

    private OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }

    @Test
    void generatesSlotsAcrossTheScheduledDay() {
        stubLookups();
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        stubEmptyConflicts();

        List<TimeSlotResponse> slots =
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow);

        assertThat(slots).hasSize(6);
        assertThat(slots.getFirst().getStartDatetime()).isEqualTo(at(tomorrow, 9, 0));
        assertThat(slots.getLast().getStartDatetime()).isEqualTo(at(tomorrow, 11, 30));
        // last slot must still finish by closing time
        assertThat(slots.getLast().getEndDatetime()).isEqualTo(at(tomorrow, 12, 0));
    }

    @Test
    void excludesSlotsOverlappingExistingBookings() {
        stubLookups();
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        when(blockedTimeRepository.findBusinessBlockedTimesInRange(any(), any(), any()))
                .thenReturn(List.of());

        Booking existing = Booking.builder()
                .id(UUID.randomUUID())
                .startDatetime(at(tomorrow, 10, 0))
                .endDatetime(at(tomorrow, 10, 30))
                .build();
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of(existing));

        List<TimeSlotResponse> slots =
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow);

        assertThat(slots).hasSize(5);
        assertThat(slots).noneMatch(slot -> slot.getStartDatetime().equals(at(tomorrow, 10, 0)));
    }

    @Test
    void excludesSlotsOverlappingScheduleBreaks() {
        stubLookups();
        Schedule schedule = scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(14, 0));
        schedule.getBreaks().add(ScheduleBreak.builder()
                .schedule(schedule)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(13, 0))
                .build());
        stubSchedule(schedule);
        stubEmptyConflicts();

        List<TimeSlotResponse> slots =
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow);

        assertThat(slots).noneMatch(slot ->
                slot.getStartDatetime().equals(at(tomorrow, 12, 0)) ||
                slot.getStartDatetime().equals(at(tomorrow, 12, 30)));
        assertThat(slots).anyMatch(slot -> slot.getStartDatetime().equals(at(tomorrow, 13, 0)));
    }

    @Test
    void excludesSlotsOverlappingBlockedTimes() {
        stubLookups();
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(blockedTimeRepository.findBusinessBlockedTimesInRange(any(), any(), any()))
                .thenReturn(List.of(BlockedTime.builder()
                        .startDatetime(at(tomorrow, 9, 0))
                        .endDatetime(at(tomorrow, 10, 0))
                        .build()));

        List<TimeSlotResponse> slots =
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow);

        assertThat(slots).hasSize(4);
        assertThat(slots.getFirst().getStartDatetime()).isEqualTo(at(tomorrow, 10, 0));
    }

    @Test
    void returnsEmptyWhenNoScheduleForTheDay() {
        stubLookups();
        stubSchedule(null);

        assertThat(availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .isEmpty();
    }

    @Test
    void returnsEmptyBeyondBookingAdvanceWindow() {
        stubLookups();
        business.setBookingAdvanceDays(7);

        LocalDate tooFarAhead = LocalDate.now(ZONE).plusDays(8);
        assertThat(availabilityService.getAvailableSlots(business.getId(), service.getId(), tooFarAhead))
                .isEmpty();
    }

    @Test
    void returnsEmptyForPastDates() {
        stubLookups();

        assertThat(availabilityService.getAvailableSlots(
                business.getId(), service.getId(), LocalDate.now(ZONE).minusDays(1)))
                .isEmpty();
    }

    @Test
    void respectsBookingNoticeHours() {
        stubLookups();
        business.setBookingNoticeHours(48);
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        stubEmptyConflicts();

        assertThat(availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .isEmpty();
    }

    @Test
    void serviceDurationLongerThanRemainingWindowYieldsNoSlot() {
        stubLookups();
        service.setDurationMinutes(120);
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(10, 0)));
        stubEmptyConflicts();

        assertThat(availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .isEmpty();
    }

    @Test
    void bufferMinutesShortenTheBookableWindowButNotTheVisibleSlot() {
        stubLookups();
        business.setBufferMinutes(15);
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(10, 0)));
        stubEmptyConflicts();

        List<TimeSlotResponse> slots =
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow);

        // 30min service + 15min buffer = 45min block: only 09:00 fits in a 1h window
        assertThat(slots).hasSize(1);
        assertThat(slots.getFirst().getStartDatetime()).isEqualTo(at(tomorrow, 9, 0));
        assertThat(slots.getFirst().getEndDatetime()).isEqualTo(at(tomorrow, 9, 30));
    }

    @Test
    void invalidStoredTimezoneFallsBackInsteadOfThrowing() {
        stubLookups();
        business.setTimezone("United Kingdom/Belfast");
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(10, 0)));
        stubEmptyConflicts();

        assertThatCode(() ->
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsServiceFromAnotherBusiness() {
        Business other = Business.builder().id(UUID.randomUUID()).build();
        service.setBusiness(other);
        stubLookups();

        assertThatThrownBy(() ->
                availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void returnsEmptyForInactiveBusinessOrService() {
        stubLookups();
        business.setIsActive(false);

        assertThat(availabilityService.getAvailableSlots(business.getId(), service.getId(), tomorrow))
                .isEmpty();
    }

    @Test
    void ensureSlotAvailablePassesForAnOfferedSlot() {
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        stubEmptyConflicts();

        assertThatCode(() ->
                availabilityService.ensureSlotAvailable(business, service, at(tomorrow, 9, 30)))
                .doesNotThrowAnyException();
    }

    @Test
    void ensureSlotAvailableRejectsOffGridTimes() {
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        stubEmptyConflicts();

        assertThatThrownBy(() ->
                availabilityService.ensureSlotAvailable(business, service, at(tomorrow, 9, 10)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void ensureSlotAvailableRejectsTimesOutsideOpeningHours() {
        stubSchedule(scheduleFor(tomorrow, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        stubEmptyConflicts();

        assertThatThrownBy(() ->
                availabilityService.ensureSlotAvailable(business, service, at(tomorrow, 20, 0)))
                .isInstanceOf(ConflictException.class);
    }
}
