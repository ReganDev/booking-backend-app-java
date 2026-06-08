package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.request.BookingStatusRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @GetMapping
    public ResponseEntity<Page<BookingResponse>> getAll(
            @PathVariable UUID businessId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(bookingService.getAllByBusinessId(businessId, pageable));
    }

    @GetMapping("/range")
    public ResponseEntity<List<BookingResponse>> getByDateRange(
            @PathVariable UUID businessId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        return ResponseEntity.ok(bookingService.getByDateRange(businessId, start, end));
    }

    @GetMapping("/staff/{staffId}")
    public ResponseEntity<List<BookingResponse>> getByStaff(
            @PathVariable UUID businessId,
            @PathVariable UUID staffId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        return ResponseEntity.ok(bookingService.getByStaffAndDateRange(businessId, staffId, start, end));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getById(
            @PathVariable UUID businessId,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.getById(businessId, bookingId));
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @PathVariable UUID businessId,
            @Valid @RequestBody BookingRequest request) {
        BookingResponse created = bookingService.create(businessId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{bookingId}/status")
    public ResponseEntity<BookingResponse> updateStatus(
            @PathVariable UUID businessId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody BookingStatusRequest request) {
        return ResponseEntity.ok(bookingService.updateStatus(businessId, bookingId, request));
    }

    @PatchMapping("/{bookingId}/reschedule")
    public ResponseEntity<BookingResponse> reschedule(
            @PathVariable UUID businessId,
            @PathVariable UUID bookingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime newStartTime) {
        return ResponseEntity.ok(bookingService.reschedule(businessId, bookingId, newStartTime));
    }
}
