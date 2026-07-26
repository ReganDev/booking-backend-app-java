package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.request.BookingStatusRequest;
import com.dev.bookingapp.javabookingapp.dto.request.CancelScope;
import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.entity.*;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final BusinessService businessService;
    private final CustomerService customerService;
    private final ServiceService serviceService;
    private final UserService userService;
    private final AvailabilityService availabilityService;
    private final BookingNotificationService bookingNotificationService;
    private final BookingManageTokenService manageTokenService;

    @Transactional(readOnly = true)
    public BookingResponse getById(UUID businessId, UUID bookingId) {
        Booking booking = bookingRepository.findByBusinessIdAndId(businessId, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));
        return bookingMapper.toResponse(booking);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> getAllByBusinessId(UUID businessId, Pageable pageable) {
        return bookingRepository.findByBusinessId(businessId, pageable)
                .map(bookingMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getByDateRange(UUID businessId, OffsetDateTime start, OffsetDateTime end) {
        return bookingRepository.findByBusinessIdAndStartDatetimeBetween(businessId, start, end)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getByStaffAndDateRange(UUID businessId, UUID staffId,
                                                         OffsetDateTime start, OffsetDateTime end) {
        return bookingRepository.findByBusinessIdAndStaffIdAndStartDatetimeBetween(businessId, staffId, start, end)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    @Transactional
    public BookingResponse createPublicBooking(UUID businessId, PublicBookingRequest request,
                                               UUID authenticatedUserId) {
        User account = userService.getEntityById(authenticatedUserId);
        if (account.getRole() != com.dev.bookingapp.javabookingapp.entity.enums.UserRole.CUSTOMER
                || !Boolean.TRUE.equals(account.getIsActive())
                || !Boolean.TRUE.equals(account.getEmailVerified())) {
            throw new com.dev.bookingapp.javabookingapp.exception.ForbiddenException(
                    "A verified customer account is required");
        }

        Business business = businessService.getEntityById(businessId);
        if (!Boolean.TRUE.equals(business.getIsActive())) {
            throw new BadRequestException("This business is not currently accepting bookings");
        }

        Service service = serviceService.getEntityById(request.getServiceId());
        if (!service.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Service does not belong to this business");
        }
        if (!Boolean.TRUE.equals(service.getIsActive())) {
            throw new BadRequestException("This service is not available to book");
        }

        // Reject anything that isn't an open slot on the business schedule
        // (covers opening hours, breaks, blocked times, notice/advance limits and clashes)
        availabilityService.ensureSlotAvailable(business, service, request.getStartDatetime());

        CustomerResponse customer = customerService.getOrCreateFromUser(businessId, account);

        BookingRequest bookingRequest = new BookingRequest();
        bookingRequest.setCustomerId(customer.getId());
        bookingRequest.setServiceId(request.getServiceId());
        bookingRequest.setStartDatetime(request.getStartDatetime());
        bookingRequest.setCustomerNotes(request.getCustomerNotes());
        bookingRequest.setEmailReminder(Boolean.TRUE.equals(request.getEmailReminder()));
        bookingRequest.setSmsReminder(Boolean.TRUE.equals(request.getSmsReminder()));

        BookingResponse created = create(businessId, bookingRequest);

        // Always-on: the details email carries the customer's manage link.
        // Both are best-effort — the booking never fails because of them.
        String manageLink = null;
        try {
            manageLink = manageTokenService.issueLink(
                    bookingRepository.getReferenceById(created.getId()));
        } catch (RuntimeException ex) {
            log.error("Could not issue manage link for booking {}", created.getId(), ex);
        }
        bookingNotificationService.sendBookingDetails(
                business, created, account.getEmail(), manageLink);

        return created;
    }

    @Transactional
    public BookingResponse create(UUID businessId, BookingRequest request) {
        Business business = businessService.getEntityById(businessId);
        Customer customer = customerService.getEntityById(request.getCustomerId());
        Service service = serviceService.getEntityById(request.getServiceId());

        // Validate customer belongs to business
        if (!customer.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Customer does not belong to this business");
        }

        // Validate service belongs to business
        if (!service.getBusiness().getId().equals(businessId)) {
            throw new BadRequestException("Service does not belong to this business");
        }

        // Calculate end time
        OffsetDateTime endDatetime = request.getStartDatetime()
                .plusMinutes(service.getDurationMinutes() + business.getBufferMinutes());

        // Validate booking time
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

        if (hasConflict(businessId, staff, request.getStartDatetime(), endDatetime, null)) {
            throw new ConflictException(conflictMessage(staff));
        }

        // The reminder flags are optional on the request but NOT NULL in the
        // database; MapStruct passes nulls straight through to the builder,
        // which bypasses the entity's @Builder.Default.
        request.setEmailReminder(Boolean.TRUE.equals(request.getEmailReminder()));
        request.setSmsReminder(Boolean.TRUE.equals(request.getSmsReminder()));

        Booking booking = bookingMapper.toEntity(request);
        booking.setBusiness(business);
        booking.setCustomer(customer);
        booking.setService(service);
        booking.setStaff(staff);
        booking.setEndDatetime(endDatetime);
        // Auto-confirm (default ON) books instantly; opted-out businesses
        // keep the request/approve flow. Null falls back to PENDING.
        booking.setStatus(Boolean.TRUE.equals(business.getAutoConfirmBookings())
                ? BookingStatus.CONFIRMED
                : BookingStatus.PENDING);
        booking.setPrice(service.getPrice());

        Booking saved = bookingRepository.save(booking);
        return bookingMapper.toResponse(saved);
    }

    @Transactional
    public BookingResponse updateStatus(UUID businessId, UUID bookingId, BookingStatusRequest request) {
        Booking booking = bookingRepository.findByBusinessIdAndId(businessId, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        Booking saved = applyStatus(booking, request);

        if (request.getScope() == CancelScope.THIS_AND_FUTURE && booking.getSeries() != null) {
            // Later occurrences only, and only ones still standing: an
            // appointment that already happened is history, not a plan.
            bookingRepository.findBySeriesIdAndStartDatetimeGreaterThanEqualAndStatusIn(
                            booking.getSeries().getId(),
                            booking.getStartDatetime(),
                            List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED))
                    .stream()
                    .filter(sibling -> !sibling.getId().equals(booking.getId()))
                    .forEach(sibling -> applyStatus(sibling, request));
        }

        return bookingMapper.toResponse(saved);
    }

    private Booking applyStatus(Booking booking, BookingStatusRequest request) {
        booking.setStatus(request.getStatus());

        if (request.getStatus() == BookingStatus.CANCELLED) {
            booking.setCancelledAt(OffsetDateTime.now());
            booking.setCancellationReason(request.getCancellationReason());
        }

        return bookingRepository.save(booking);
    }

    @Transactional
    public BookingResponse reschedule(UUID businessId, UUID bookingId, OffsetDateTime newStartTime) {
        Booking booking = bookingRepository.findByBusinessIdAndId(businessId, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED ||
            booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BadRequestException("Cannot reschedule a cancelled or completed booking");
        }

        if (newStartTime.isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("Cannot reschedule to a past time");
        }

        int durationMinutes = booking.getService().getDurationMinutes() +
                              booking.getBusiness().getBufferMinutes();
        OffsetDateTime newEndTime = newStartTime.plusMinutes(durationMinutes);

        if (hasConflict(booking.getBusiness().getId(), booking.getStaff(),
                newStartTime, newEndTime, booking.getId())) {
            throw new ConflictException(conflictMessage(booking.getStaff()));
        }

        booking.setStartDatetime(newStartTime);
        booking.setEndDatetime(newEndTime);

        Booking saved = bookingRepository.save(booking);
        return bookingMapper.toResponse(saved);
    }

    /**
     * Whether the window is already taken. With a staff member assigned the
     * clash is against that person's diary; without one the whole business is
     * treated as a single resource.
     *
     * <p>Package-private so {@link BookingSeriesService} can ask the same
     * question per occurrence without having to catch an exception.
     */
    boolean hasConflict(UUID businessId, User staff,
                        OffsetDateTime start, OffsetDateTime end, UUID excludeBookingId) {
        List<Booking> conflicts = staff != null
                ? bookingRepository.findConflictingBookings(staff.getId(), start, end, excludeBookingId)
                : bookingRepository.findConflictingBusinessBookings(businessId, start, end, excludeBookingId);
        return !conflicts.isEmpty();
    }

    static String conflictMessage(User staff) {
        return staff != null
                ? "Staff member has a conflicting booking at this time"
                : "There is already a booking at this time";
    }
}
