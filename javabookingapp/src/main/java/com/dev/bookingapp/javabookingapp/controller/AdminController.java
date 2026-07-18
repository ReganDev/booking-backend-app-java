package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.RegisterRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessAccountResponse;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.mapper.BusinessMapper;
import com.dev.bookingapp.javabookingapp.mapper.UserMapper;
import com.dev.bookingapp.javabookingapp.service.AuthService;
import com.dev.bookingapp.javabookingapp.service.BusinessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Platform-admin endpoints. The admin account is seeded from environment
 *  variables and has no business, so tenant-scoped routes never apply. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;
    private final BusinessService businessService;
    private final BusinessMapper businessMapper;
    private final UserMapper userMapper;

    @GetMapping("/businesses")
    public ResponseEntity<List<BusinessResponse>> listBusinesses() {
        return ResponseEntity.ok(businessService.listAll());
    }

    @PostMapping("/businesses")
    public ResponseEntity<BusinessAccountResponse> createBusinessAccount(
            @Valid @RequestBody RegisterRequest request) {
        User owner = authService.createBusinessWithOwner(request);
        BusinessAccountResponse response = BusinessAccountResponse.builder()
                .business(businessMapper.toResponse(owner.getBusiness()))
                .owner(userMapper.toResponse(owner))
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
