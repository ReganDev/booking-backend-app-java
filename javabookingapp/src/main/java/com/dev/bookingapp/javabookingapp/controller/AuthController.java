package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.CustomerRegisterRequest;
import com.dev.bookingapp.javabookingapp.dto.request.LoginRequest;
import com.dev.bookingapp.javabookingapp.dto.request.RefreshTokenRequest;
import com.dev.bookingapp.javabookingapp.dto.request.RegisterRequest;
import com.dev.bookingapp.javabookingapp.dto.response.AuthResponse;
import com.dev.bookingapp.javabookingapp.exception.ForbiddenException;
import com.dev.bookingapp.javabookingapp.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // Accounts are provisioned by the site owner; self-registration stays off
    // in production unless REGISTRATION_ENABLED is set.
    @Value("${app.registration-enabled:true}")
    private boolean registrationEnabled;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ForbiddenException(
                    "Self-registration is disabled. Please contact us to request an account.");
        }
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Customer accounts are open to everyone; only business registration is
    // gated behind app.registration-enabled.
    @PostMapping("/register-customer")
    public ResponseEntity<AuthResponse> registerCustomer(
            @Valid @RequestBody CustomerRegisterRequest request) {
        AuthResponse response = authService.registerCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
