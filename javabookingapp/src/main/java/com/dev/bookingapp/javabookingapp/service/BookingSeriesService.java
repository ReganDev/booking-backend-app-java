package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.RecurringBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.RecurringBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingSeries;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.BookingSeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates standing appointments: one {@link BookingSeries} row plus a real
 * {@link Booking} per occurrence.
 *
 * <p>Deliberately does not call {@link AvailabilityService#ensureSlotAvailable},
 * because {@link BookingService#create} doesn't either. Owner-made bookings
 * bypass opening hours, the notice window and the advance-days cap by design —
 * and that cap defaults to 30 days, so routing a year-long series through
 * availability would reject every occurrence past the first month.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class BookingSeriesService {

    private static final String ALREADY_BOOKED = "Already booked";

    private final BookingRepository bookingRepository;
    private final BookingSeriesRepository bookingSeriesRepository;
    private final BookingMapper bookingMapper;
    private final BookingService bookingService;
    private final BusinessService businessService;
    private final CustomerService customerService;
    private final ServiceService serviceService;
    private final UserService userService;
    private final RecurrenceCalculator recurrenceCalculator;

    @Transactional
    public RecurringBookingResponse create(UUID businessId, RecurringBookingRequest request) {
        Business business = businessService.getEntityById(businessId);
        Customer customer = customerService.getEntityById(request.getCustomerId());
        Service service = serviceService.getEntityById(request.getServiceId());

        if (!customer.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Customer does not belong to this business");
        }

        if (!service.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Service does not belong to this business");
        }

        if (request.getStartDatetime().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("Cannot book appointments in the past");
        }

        User staff = null;
        if (request.getStaffId() != null) {
            staff = userService.getEntityById(request.getStaffId());
            if (!staff.getBusiness().getId().equals(businessId)) {
                throw new BadRequestException("Staff does not belong to this business");
            }
        }

        int intervalWeeks = resolveIntervalWeeks(request);
        int intervalMonths = resolveIntervalMonths(request);

        List<OffsetDateTime> starts = recurrenceCalculator.occurrences(
                request.getStartDatetime(),
                request.getFrequency(),
                request.getOccurrenceCount(),
                intervalWeeks,
                intervalMonths,
                AvailabilityService.resolveZone(business.getTimezone()));

        // The stored end carries the buffer, exactly as BookingService.create does.
        long blockMinutes = service.getDurationMinutes() + business.getBufferMinutes();
        BookingStatus status = Boolean.TRUE.equals(business.getAutoConfirmBookings())
                ? BookingStatus.CONFIRMED
                : BookingStatus.PENDING;

        List<Booking> toCreate = new ArrayList<>();
        List<RecurringBookingResponse.SkippedOccurrence> skipped = new ArrayList<>();

        for (OffsetDateTime start : starts) {
            OffsetDateTime end = start.plusMinutes(blockMinutes);

            // Occurrences already staged in this loop aren't in the database yet,
            // so a series that overlaps itself would slip past the repository
            // check. That only happens if duration + buffer exceeds the gap
            // between occurrences, but it costs nothing to rule out.
            boolean clashesWithEarlierOccurrence = toCreate.stream().anyMatch(pending ->
                    start.isBefore(pending.getEndDatetime()) && end.isAfter(pending.getStartDatetime()));

            if (clashesWithEarlierOccurrence
                    || bookingService.hasConflict(businessId, staff, start, end, null)) {
                skipped.add(RecurringBookingResponse.SkippedOccurrence.builder()
                        .startDatetime(start)
                        .reason(ALREADY_BOOKED)
                        .build());
                continue;
            }

            toCreate.add(Booking.builder()
                    .business(business)
                    .customer(customer)
                    .service(service)
                    .staff(staff)
                    .startDatetime(start)
                    .endDatetime(end)
                    .status(status)
                    .price(service.getPrice())
                    .customerNotes(request.getCustomerNotes())
                    .internalNotes(request.getInternalNotes())
                    .emailReminder(Boolean.TRUE.equals(request.getEmailReminder()))
                    .smsReminder(Boolean.TRUE.equals(request.getSmsReminder()))
                    .build());
        }

        if (toCreate.isEmpty()) {
            throw new ConflictException(
                    "Every occurrence of this repeating booking clashes with an existing booking");
        }

        BookingSeries series = bookingSeriesRepository.save(BookingSeries.builder()
                .business(business)
                .customer(customer)
                .service(service)
                .staff(staff)
                .frequency(request.getFrequency())
                .occurrenceCount(request.getOccurrenceCount())
                .intervalWeeks(intervalWeeks)
                .intervalMonths(intervalMonths)
                .firstStartDatetime(request.getStartDatetime())
                .build());

        toCreate.forEach(booking -> booking.setSeries(series));

        List<BookingResponse> created = bookingRepository.saveAll(toCreate)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();

        return RecurringBookingResponse.builder()
                .seriesId(series.getId())
                .created(created)
                .skipped(skipped)
                .build();
    }

    private int resolveIntervalWeeks(RecurringBookingRequest request) {
        if (request.getFrequency() == RecurrenceFrequency.FORTNIGHTLY) {
            return 2;
        }
        if (request.getFrequency() == RecurrenceFrequency.MONTHLY) {
            return 1;
        }
        return request.getIntervalWeeks() != null ? request.getIntervalWeeks() : 1;
    }

    private int resolveIntervalMonths(RecurringBookingRequest request) {
        if (request.getFrequency() != RecurrenceFrequency.MONTHLY) {
            return 1;
        }
        return request.getIntervalMonths() != null ? request.getIntervalMonths() : 1;
    }
}
