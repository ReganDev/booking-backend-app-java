package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.ScheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ScheduleResponse;
import com.dev.bookingapp.javabookingapp.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    public ResponseEntity<List<ScheduleResponse>> getBusinessSchedule(@PathVariable UUID businessId) {
        return ResponseEntity.ok(scheduleService.getBusinessSchedule(businessId));
    }

    @GetMapping("/staff/{userId}")
    public ResponseEntity<List<ScheduleResponse>> getStaffSchedule(
            @PathVariable UUID businessId,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(scheduleService.getStaffSchedule(businessId, userId));
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> createOrUpdate(
            @PathVariable UUID businessId,
            @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.createOrUpdate(businessId, request));
    }

    @PostMapping("/staff/{userId}/week")
    public ResponseEntity<List<ScheduleResponse>> setWeeklySchedule(
            @PathVariable UUID businessId,
            @PathVariable UUID userId,
            @Valid @RequestBody List<ScheduleRequest> requests) {
        return ResponseEntity.ok(scheduleService.setWeeklySchedule(businessId, userId, requests));
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID businessId,
            @PathVariable UUID scheduleId) {
        scheduleService.deleteSchedule(businessId, scheduleId);
        return ResponseEntity.noContent().build();
    }
}
