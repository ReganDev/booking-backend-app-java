package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ServiceResponse;
import com.dev.bookingapp.javabookingapp.dto.response.TimeSlotResponse;
import com.dev.bookingapp.javabookingapp.service.AvailabilityService;
import com.dev.bookingapp.javabookingapp.service.BookingService;
import com.dev.bookingapp.javabookingapp.service.BusinessService;
import com.dev.bookingapp.javabookingapp.service.ServiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    @GetMapping("/businesses")
    public ResponseEntity<List<BusinessResponse>> listBusinesses() {
        return ResponseEntity.ok(businessService.listActive());
    }

    @GetMapping("/businesses/slug/{slug}")
    public ResponseEntity<BusinessResponse> getBusinessBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(businessService.getActiveBySlug(slug));
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
    public ResponseEntity<BookingResponse> createBooking(
            @PathVariable UUID businessId,
            @Valid @RequestBody PublicBookingRequest request) {
        BookingResponse created = bookingService.createPublicBooking(businessId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
