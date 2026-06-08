package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.UserRequest;
import com.dev.bookingapp.javabookingapp.dto.response.UserResponse;
import com.dev.bookingapp.javabookingapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAll(@PathVariable UUID businessId) {
        return ResponseEntity.ok(userService.getAllByBusinessId(businessId));
    }

    @GetMapping("/staff")
    public ResponseEntity<List<UserResponse>> getStaff(@PathVariable UUID businessId) {
        return ResponseEntity.ok(userService.getStaffByBusinessId(businessId));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getById(
            @PathVariable UUID businessId,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getById(businessId, userId));
    }

    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> update(
            @PathVariable UUID businessId,
            @PathVariable UUID userId,
            @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.update(businessId, userId, request));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID businessId,
            @PathVariable UUID userId) {
        userService.deactivate(businessId, userId);
        return ResponseEntity.noContent().build();
    }
}
