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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/London");

    private final BusinessService businessService;
    private final ServiceService serviceService;
    private final ScheduleRepository scheduleRepository;
    private final BookingRepository bookingRepository;
    private final BlockedTimeRepository blockedTimeRepository;

    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getAvailableSlots(UUID businessId, UUID serviceId, LocalDate date) {
        Business business = businessService.getEntityById(businessId);
        com.dev.bookingapp.javabookingapp.entity.Service service = serviceService.getEntityById(serviceId);

        if (!service.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Service does not belong to this business");
        }

        if (!Boolean.TRUE.equals(business.getIsActive()) || !Boolean.TRUE.equals(service.getIsActive())) {
            return List.of();
        }

        return computeSlots(business, service, date);
    }

    public void ensureSlotAvailable(Business business,
                                    com.dev.bookingapp.javabookingapp.entity.Service service,
                                    OffsetDateTime startDatetime) {
        ZoneId zone = resolveZone(business.getTimezone());
        LocalDate date = startDatetime.atZoneSameInstant(zone).toLocalDate();

        boolean available = computeSlots(business, service, date).stream()
                .anyMatch(slot -> slot.getStartDatetime().toInstant().equals(startDatetime.toInstant()));

        if (!available) {
            throw new ConflictException("This time slot is not available. Please choose another time.");
        }
    }

    List<TimeSlotResponse> computeSlots(Business business,
                                        com.dev.bookingapp.javabookingapp.entity.Service service,
                                        LocalDate date) {
        ZoneId zone = resolveZone(business.getTimezone());
        LocalDate today = LocalDate.now(zone);

        int advanceDays = valueOrDefault(business.getBookingAdvanceDays(), 30);
        if (date.isBefore(today) || date.isAfter(today.plusDays(advanceDays))) {
            return List.of();
        }

        Schedule schedule = scheduleRepository
                .findByBusinessIdAndUserIsNullAndDayOfWeek(business.getId(), DayOfWeek.valueOf(date.getDayOfWeek().name()))
                .filter(s -> Boolean.TRUE.equals(s.getIsActive()))
                .orElse(null);
        if (schedule == null) {
            return List.of();
        }

        int stepMinutes = Math.max(5, valueOrDefault(business.getSlotDurationMinutes(), 30));
        int blockMinutes = service.getDurationMinutes() + valueOrDefault(business.getBufferMinutes(), 0);

        OffsetDateTime open = date.atTime(schedule.getStartTime()).atZone(zone).toOffsetDateTime();
        OffsetDateTime close = date.atTime(schedule.getEndTime()).atZone(zone).toOffsetDateTime();
        OffsetDateTime earliestStart = OffsetDateTime.now()
                .plusHours(valueOrDefault(business.getBookingNoticeHours(), 0));

        List<Booking> bookings = bookingRepository
                .findConflictingBusinessBookings(business.getId(), open, close, null);
        List<BlockedTime> blockedTimes = blockedTimeRepository
                .findBusinessBlockedTimesInRange(business.getId(), open, close);

        List<TimeSlotResponse> slots = new ArrayList<>();
        for (OffsetDateTime start = open;
             !start.plusMinutes(blockMinutes).isAfter(close);
             start = start.plusMinutes(stepMinutes)) {

            OffsetDateTime end = start.plusMinutes(blockMinutes);

            if (start.isBefore(earliestStart)) continue;
            if (overlapsBreak(schedule.getBreaks(), date, zone, start, end)) continue;
            if (overlapsBlockedTime(blockedTimes, start, end)) continue;
            if (overlapsBooking(bookings, start, end)) continue;

            slots.add(new TimeSlotResponse(start, start.plusMinutes(service.getDurationMinutes())));
        }
        return slots;
    }

    private boolean overlapsBreak(List<ScheduleBreak> breaks, LocalDate date, ZoneId zone,
                                  OffsetDateTime start, OffsetDateTime end) {
        return breaks.stream().anyMatch(scheduleBreak -> {
            OffsetDateTime breakStart = date.atTime(scheduleBreak.getStartTime()).atZone(zone).toOffsetDateTime();
            OffsetDateTime breakEnd = date.atTime(scheduleBreak.getEndTime()).atZone(zone).toOffsetDateTime();
            return start.isBefore(breakEnd) && end.isAfter(breakStart);
        });
    }

    private boolean overlapsBlockedTime(List<BlockedTime> blockedTimes,
                                        OffsetDateTime start, OffsetDateTime end) {
        return blockedTimes.stream().anyMatch(blocked ->
                start.isBefore(blocked.getEndDatetime()) && end.isAfter(blocked.getStartDatetime()));
    }

    private boolean overlapsBooking(List<Booking> bookings,
                                    OffsetDateTime start, OffsetDateTime end) {
        return bookings.stream().anyMatch(booking ->
                start.isBefore(booking.getEndDatetime()) && end.isAfter(booking.getStartDatetime()));
    }

    static ZoneId resolveZone(String timezone) {
        if (timezone != null) {
            try {
                return ZoneId.of(timezone);
            } catch (DateTimeException ignored) {
                // fall through to the default zone
            }
        }
        return FALLBACK_ZONE;
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
