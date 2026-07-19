package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.CustomerRegisterRequest;
import com.dev.bookingapp.javabookingapp.dto.request.LoginRequest;
import com.dev.bookingapp.javabookingapp.dto.request.*;
import com.dev.bookingapp.javabookingapp.dto.response.AuthResponse;
import com.dev.bookingapp.javabookingapp.dto.response.MessageResponse;
import com.dev.bookingapp.javabookingapp.exception.ForbiddenException;
import com.dev.bookingapp.javabookingapp.service.AuthService;
import com.dev.bookingapp.javabookingapp.service.EmailVerificationService;
import com.dev.bookingapp.javabookingapp.service.PasswordResetService;
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
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    // Accounts are provisioned by the site owner; self-registration stays off
    // in production unless REGISTRATION_ENABLED is set.
    @Value("${app.registration-enabled:true}")
    private boolean registrationEnabled;

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ForbiddenException(
                    "Self-registration is disabled. Please contact us to request an account.");
        }
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Registration successful. Check your email to verify your account."));
    }

    // Customer accounts are open to everyone; only business registration is
    // gated behind app.registration-enabled.
    @PostMapping("/register-customer")
    public ResponseEntity<MessageResponse> registerCustomer(
            @Valid @RequestBody CustomerRegisterRequest request) {
        authService.registerCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Registration successful. Check your email to verify your account."));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.getToken());
        return ResponseEntity.ok(new MessageResponse("Email verified successfully."));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resend(request.getEmail());
        return ResponseEntity.accepted().body(new MessageResponse(
                "If an unverified account exists, a verification email will be sent."));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.accepted().body(new MessageResponse(
                "If an account exists for that email, a password reset link will be sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(new MessageResponse(
                "Password reset successfully. You can now sign in with your new password."));
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
