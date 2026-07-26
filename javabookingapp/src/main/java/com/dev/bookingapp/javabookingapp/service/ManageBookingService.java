package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Customer self-service cancel/reschedule. Two entry paths share the same
 * rules: a manage-link token (guests) or the signed-in customer's email
 * (ownership is by case-insensitive email match — Customer rows have no FK
 * to User accounts). Changes are allowed until cancellation_notice_hours
 * before the start; inside the cutoff customers are told to contact the
 * business directly.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ManageBookingService {

    private final BookingManageTokenService tokenService;
    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final BookingNotificationService notificationService;

    // ---- token path -------------------------------------------------------

    @Transactional(readOnly = true)
    public ManageBookingResponse getByToken(String rawToken) {
        return toResponse(tokenService.resolve(rawToken));
    }

    @Transactional
    public ManageBookingResponse cancelByToken(String rawToken) {
        return cancel(tokenService.resolve(rawToken));
    }

    @Transactional
    public ManageBookingResponse rescheduleByToken(String rawToken, OffsetDateTime newStart) {
        return reschedule(tokenService.resolve(rawToken), newStart);
    }

    // ---- signed-in customer path ------------------------------------------

    @Transactional(readOnly = true)
    public List<ManageBookingResponse> listForCustomer(String email) {
        return bookingRepository.findByCustomerEmail(email).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ManageBookingResponse cancelForCustomer(String email, UUID bookingId) {
        return cancel(owned(email, bookingId));
    }

    @Transactional
    public ManageBookingResponse rescheduleForCustomer(String email, UUID bookingId,
                                                       OffsetDateTime newStart) {
        return reschedule(owned(email, bookingId), newStart);
    }

    /** Loads a booking only if it belongs to this customer's email; otherwise
     *  404 — never reveal whether someone else's booking id exists. */
    private Booking owned(String email, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));
        String customerEmail = booking.getCustomer() != null
                ? booking.getCustomer().getEmail() : null;
        if (customerEmail == null || !customerEmail.equalsIgnoreCase(email)) {
            throw new ResourceNotFoundException("Booking", "id", bookingId);
        }
        return booking;
    }

    // ---- shared rules -----------------------------------------------------

    private ManageBookingResponse cancel(Booking booking) {
        OffsetDateTime now = OffsetDateTime.now();
        requireModifiable(booking, now);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setCancellationReason("Cancelled by customer");
        Booking saved = bookingRepository.save(booking);

        notificationService.sendBusinessCancelledNotice(
                saved.getBusiness(), bookingMapper.toResponse(saved));
        return toResponse(saved);
    }

    private ManageBookingResponse reschedule(Booking booking, OffsetDateTime newStart) {
        OffsetDateTime now = OffsetDateTime.now();
        requireModifiable(booking, now);

        OffsetDateTime oldStart = booking.getStartDatetime();
        // Full availability check (opening hours, breaks, notice/advance
        // limits, clashes) before the conflict-checked reschedule itself.
        availabilityService.ensureSlotAvailable(
                booking.getBusiness(), booking.getService(), newStart);
        BookingResponse updated = bookingService.reschedule(
                booking.getBusiness().getId(), booking.getId(), newStart);

        notificationService.sendBusinessRescheduledNotice(
                booking.getBusiness(), updated, oldStart);
        return toResponse(booking, updated);
    }

    private void requireModifiable(Booking booking, OffsetDateTime now) {
        if (!canModifyAt(booking, now)) {
            throw new BadRequestException(
                    "This booking can no longer be changed online. Please contact "
                            + booking.getBusiness().getName() + " to make changes.");
        }
    }

    private boolean canModifyAt(Booking booking, OffsetDateTime now) {
        if (booking.getStatus() != BookingStatus.PENDING
                && booking.getStatus() != BookingStatus.CONFIRMED) {
            return false;
        }
        int noticeHours = booking.getBusiness().getCancellationNoticeHours() != null
                ? booking.getBusiness().getCancellationNoticeHours() : 0;
        return booking.getStartDatetime().isAfter(now.plusHours(noticeHours));
    }

    private ManageBookingResponse toResponse(Booking booking) {
        return toResponse(booking, bookingMapper.toResponse(booking));
    }

    private ManageBookingResponse toResponse(Booking booking, BookingResponse payload) {
        Business business = booking.getBusiness();
        boolean modifiable = canModifyAt(booking, OffsetDateTime.now());
        return ManageBookingResponse.builder()
                .booking(payload)
                .businessName(business.getName())
                .businessSlug(business.getSlug())
                .businessEmail(business.getEmail())
                .businessPhone(business.getPhone())
                .cancellationNoticeHours(business.getCancellationNoticeHours())
                .bookingAdvanceDays(business.getBookingAdvanceDays())
                .canCancel(modifiable)
                .canReschedule(modifiable)
                .build();
    }
}
