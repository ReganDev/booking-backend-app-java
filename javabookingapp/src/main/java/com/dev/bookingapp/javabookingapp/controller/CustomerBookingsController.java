package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRescheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.security.UserPrincipal;
import com.dev.bookingapp.javabookingapp.service.ManageBookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Signed-in customers manage their own bookings by session — no token. */
@RestController
@RequestMapping("/api/v1/customers/me/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER') and principal.emailVerified == true")
public class CustomerBookingsController {

    private final ManageBookingService manageBookingService;

    @GetMapping
    public ResponseEntity<List<ManageBookingResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(manageBookingService.listForCustomer(principal.getEmail()));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ManageBookingResponse> cancel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(
                manageBookingService.cancelForCustomer(principal.getEmail(), bookingId));
    }

    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<ManageBookingResponse> reschedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody BookingRescheduleRequest request) {
        return ResponseEntity.ok(manageBookingService.rescheduleForCustomer(
                principal.getEmail(), bookingId, request.getStartDatetime()));
    }
}
