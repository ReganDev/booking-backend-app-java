package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingStartRequest;
import com.dev.bookingapp.javabookingapp.dto.response.GuestBookingStartResponse;
import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.BookingOtpSessionRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestBookingServiceTest {

    @Mock BookingOtpSessionRepository sessionRepository;
    @Mock UserRepository userRepository;
    @Mock BusinessService businessService;
    @Mock ServiceService serviceService;
    @Mock AvailabilityService availabilityService;
    @Mock BookingService bookingService;
    @Mock PasswordResetService passwordResetService;
    @Mock ResendEmailSender emailSender;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks GuestBookingService service;

    private Business business;
    private Service bookableService;
    private GuestBookingStartRequest startRequest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiry", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "resendInterval", Duration.ofSeconds(60));

        UUID businessId = UUID.randomUUID();
        business = Business.builder().id(businessId).name("Fab Hair").isActive(true).build();
        bookableService = Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(30)
                .isActive(true)
                .build();

        startRequest = new GuestBookingStartRequest();
        startRequest.setBusinessId(businessId);
        startRequest.setFirstName("Gwen");
        startRequest.setLastName("Guest");
        startRequest.setEmail("  Gwen@Example.com ");
        startRequest.setServiceId(bookableService.getId());
        startRequest.setStartDatetime(OffsetDateTime.now().plusDays(2));
        startRequest.setEmailReminder(true);

        lenient().when(businessService.getEntityById(businessId)).thenReturn(business);
        lenient().when(serviceService.getEntityById(bookableService.getId()))
                .thenReturn(bookableService);
        lenient().when(emailSender.isConfigured()).thenReturn(true);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$placeholder");
        lenient().when(sessionRepository.save(any(BookingOtpSession.class)))
                .thenAnswer(inv -> {
                    BookingOtpSession s = inv.getArgument(0);
                    if (s.getId() == null) s.setId(UUID.randomUUID());
                    return s;
                });
        lenient().when(userRepository.save(any(User.class)))
                .thenAnswer(inv -> {
                    User u = inv.getArgument(0);
                    if (u.getId() == null) u.setId(UUID.randomUUID());
                    return u;
                });
    }

    @Test
    void startCreatesPasswordlessCustomerAndHashedCodeForNewEmail() {
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.empty());

        GuestBookingStartResponse response = service.start(startRequest);

        assertThat(response.getBookingSessionId()).isNotNull();
        assertThat(response.getExpiresAt())
                .isAfter(OffsetDateTime.now().plusMinutes(9));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isEqualTo("gwen@example.com");
        assertThat(created.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(created.getEmailVerified()).isFalse();
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$placeholder");

        ArgumentCaptor<BookingOtpSession> sessionCaptor =
                ArgumentCaptor.forClass(BookingOtpSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        BookingOtpSession session = sessionCaptor.getValue();
        assertThat(session.getCodeHash()).matches("[0-9a-f]{64}");
        assertThat(session.getNewAccount()).isTrue();

        verify(availabilityService).ensureSlotAvailable(
                business, bookableService, startRequest.getStartDatetime());
        verify(emailSender).send(eq("BookingBase"), eq("gwen@example.com"),
                isNull(), contains("code"), matches("(?s).*\\b\\d{6}\\b.*"));
    }

    @Test
    void startReusesExistingCustomerAccountWithoutOverwritingIt() {
        User existing = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwendolyn")
                .lastName("Original")
                .passwordHash("$existing")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(existing));

        service.start(startRequest);

        verify(userRepository, never()).save(any());
        ArgumentCaptor<BookingOtpSession> captor =
                ArgumentCaptor.forClass(BookingOtpSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getNewAccount()).isFalse();
        assertThat(captor.getValue().getUser()).isSameAs(existing);
    }

    @Test
    void startRejectsBusinessAccountEmails() {
        User owner = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwen").lastName("Owner")
                .passwordHash("$existing")
                .role(UserRole.OWNER)
                .isActive(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.start(startRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("business account");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void startRateLimitsRepeatRequestsForTheSameEmail() {
        User existing = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwen").lastName("Guest")
                .passwordHash("$existing")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(existing));
        BookingOtpSession recent = BookingOtpSession.builder()
                .user(existing)
                .codeHash("x".repeat(64))
                .lastSentAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        ReflectionTestUtils.setField(recent, "createdAt", OffsetDateTime.now());
        when(sessionRepository.findFirstByUserIdOrderByCreatedAtDesc(existing.getId()))
                .thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.start(startRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("wait");
        verify(emailSender, never()).send(any(), any(), any(), any(), any());
    }
}
