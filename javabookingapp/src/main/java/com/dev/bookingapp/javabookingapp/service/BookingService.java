package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.request.BookingStatusRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.entity.*;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final BusinessService businessService;
    private final CustomerService customerService;
    private final ServiceService serviceService;
    private final UserService userService;

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

            // Check for conflicts
            List<Booking> conflicts = bookingRepository.findConflictingBookings(
                    staff.getId(), request.getStartDatetime(), endDatetime, null);
            if (!conflicts.isEmpty()) {
                throw new ConflictException("Staff member has a conflicting booking at this time");
            }
        }

        Booking booking = bookingMapper.toEntity(request);
        booking.setBusiness(business);
        booking.setCustomer(customer);
        booking.setService(service);
        booking.setStaff(staff);
        booking.setEndDatetime(endDatetime);
        booking.setStatus(BookingStatus.PENDING);
        booking.setPrice(service.getPrice());

        Booking saved = bookingRepository.save(booking);
        return bookingMapper.toResponse(saved);
    }

    @Transactional
    public BookingResponse updateStatus(UUID businessId, UUID bookingId, BookingStatusRequest request) {
        Booking booking = bookingRepository.findByBusinessIdAndId(businessId, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        booking.setStatus(request.getStatus());

        if (request.getStatus() == BookingStatus.CANCELLED) {
            booking.setCancelledAt(OffsetDateTime.now());
            booking.setCancellationReason(request.getCancellationReason());
        }

        Booking saved = bookingRepository.save(booking);
        return bookingMapper.toResponse(saved);
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

        // Check for conflicts if staff is assigned
        if (booking.getStaff() != null) {
            List<Booking> conflicts = bookingRepository.findConflictingBookings(
                    booking.getStaff().getId(), newStartTime, newEndTime, booking.getId());
            if (!conflicts.isEmpty()) {
                throw new ConflictException("Staff member has a conflicting booking at this time");
            }
        }

        booking.setStartDatetime(newStartTime);
        booking.setEndDatetime(newEndTime);

        Booking saved = bookingRepository.save(booking);
        return bookingMapper.toResponse(saved);
    }
}
