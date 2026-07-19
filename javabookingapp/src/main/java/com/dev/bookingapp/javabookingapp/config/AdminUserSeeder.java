package com.dev.bookingapp.javabookingapp.config;

import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import com.dev.bookingapp.javabookingapp.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the platform admin account on startup when ADMIN_EMAIL and
 * ADMIN_PASSWORD are configured and no user with that email exists yet.
 * There is deliberately no signup path for admins.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.info("No admin credentials configured; skipping admin seeding");
            return;
        }

        String normalizedEmail = EmailVerificationService.normalizeEmail(adminEmail);
        if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            return;
        }

        User admin = User.builder()
                .business(null)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .firstName("Platform")
                .lastName("Admin")
                .role(UserRole.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .acceptsBookings(false)
                .build();

        userRepository.save(admin);
        log.info("Seeded platform admin account for {}", normalizedEmail);
    }
}
