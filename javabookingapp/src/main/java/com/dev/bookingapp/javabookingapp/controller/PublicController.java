package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRescheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.request.EnquiryRequest;
import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingResendRequest;
import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingStartRequest;
import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingVerifyRequest;
import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.dto.response.GuestBookingStartResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ServiceResponse;
import com.dev.bookingapp.javabookingapp.dto.response.TimeSlotResponse;
import com.dev.bookingapp.javabookingapp.service.AvailabilityService;
import com.dev.bookingapp.javabookingapp.service.BookingService;
import com.dev.bookingapp.javabookingapp.service.BusinessService;
import com.dev.bookingapp.javabookingapp.service.EnquiryService;
import com.dev.bookingapp.javabookingapp.service.GuestBookingService;
import com.dev.bookingapp.javabookingapp.service.ManageBookingService;
import com.dev.bookingapp.javabookingapp.service.ServiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.dev.bookingapp.javabookingapp.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicController {

    private final BusinessService businessService;
    private final ServiceService serviceService;
    private final BookingService bookingService;
    private final AvailabilityService availabilityService;
    private final EnquiryService enquiryService;
    private final GuestBookingService guestBookingService;
    private final ManageBookingService manageBookingService;

    @PostMapping("/enquiry")
    public ResponseEntity<Void> submitEnquiry(@Valid @RequestBody EnquiryRequest request) {
        enquiryService.sendEnquiry(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/businesses")
    public ResponseEntity<List<BusinessResponse>> listBusinesses() {
        return ResponseEntity.ok(businessService.listActive());
    }

    @GetMapping("/businesses/slug/{slug}")
    public ResponseEntity<BusinessResponse> getBusinessBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(businessService.getActiveBySlug(slug));
    }

    @GetMapping("/businesses/{businessId}/availability/days")
    public ResponseEntity<List<LocalDate>> getAvailableDays(
            @PathVariable UUID businessId,
            @RequestParam UUID serviceId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(availabilityService.getAvailableDays(businessId, serviceId, month));
    }

    @GetMapping("/businesses/{businessId}/availability")
    public ResponseEntity<List<TimeSlotResponse>> getAvailability(
            @PathVariable UUID businessId,
            @RequestParam UUID serviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(availabilityService.getAvailableSlots(businessId, serviceId, date));
    }

    @GetMapping("/businesses/{businessId}/services")
    public ResponseEntity<List<ServiceResponse>> getActiveServices(@PathVariable UUID businessId) {
        return ResponseEntity.ok(serviceService.getActiveByBusinessId(businessId));
    }

    @PostMapping("/businesses/{businessId}/bookings")
    @PreAuthorize("hasRole('CUSTOMER') and principal.emailVerified == true")
    public ResponseEntity<BookingResponse> createBooking(
            @PathVariable UUID businessId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PublicBookingRequest request) {
        BookingResponse created = bookingService.createPublicBooking(
                businessId, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/bookings/start")
    public ResponseEntity<GuestBookingStartResponse> startGuestBooking(
            @Valid @RequestBody GuestBookingStartRequest request) {
        return ResponseEntity.ok(guestBookingService.start(request));
    }

    @PostMapping("/bookings/verify")
    public ResponseEntity<BookingResponse> verifyGuestBooking(
            @Valid @RequestBody GuestBookingVerifyRequest request) {
        BookingResponse created = guestBookingService.verify(
                request.getBookingSessionId(), request.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/bookings/resend")
    public ResponseEntity<Void> resendGuestBookingCode(
            @Valid @RequestBody GuestBookingResendRequest request) {
        guestBookingService.resend(request.getBookingSessionId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/manage/{token}")
    public ResponseEntity<ManageBookingResponse> getManagedBooking(@PathVariable String token) {
        return ResponseEntity.ok(manageBookingService.getByToken(token));
    }

    @PostMapping("/manage/{token}/cancel")
    public ResponseEntity<ManageBookingResponse> cancelManagedBooking(@PathVariable String token) {
        return ResponseEntity.ok(manageBookingService.cancelByToken(token));
    }

    @PostMapping("/manage/{token}/reschedule")
    public ResponseEntity<ManageBookingResponse> rescheduleManagedBooking(
            @PathVariable String token,
            @Valid @RequestBody BookingRescheduleRequest request) {
        return ResponseEntity.ok(
                manageBookingService.rescheduleByToken(token, request.getStartDatetime()));
    }
}
