package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingManageToken;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.BookingManageTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingManageTokenServiceTest {

    @Mock
    private BookingManageTokenRepository tokenRepository;

    @InjectMocks
    private BookingManageTokenService tokenService;

    private Booking booking;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenService, "frontendUrl", "https://bookingbase.co.uk/");
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .startDatetime(OffsetDateTime.now().plusDays(1))
                .endDatetime(OffsetDateTime.now().plusDays(1).plusMinutes(45))
                .build();
    }

    @Test
    void issueLinkStoresHashedTokenAndReturnsManageUrl() {
        when(tokenRepository.findByBookingId(booking.getId())).thenReturn(Optional.empty());
        when(tokenRepository.save(any(BookingManageToken.class))).thenAnswer(inv -> inv.getArgument(0));

        String link = tokenService.issueLink(booking);

        assertThat(link).startsWith("https://bookingbase.co.uk/manage/booking/");
        String rawToken = link.substring(link.lastIndexOf('/') + 1);
        assertThat(rawToken).hasSizeGreaterThanOrEqualTo(43); // 32 bytes base64url

        ArgumentCaptor<BookingManageToken> captor = ArgumentCaptor.forClass(BookingManageToken.class);
        verify(tokenRepository).save(captor.capture());
        BookingManageToken saved = captor.getValue();
        assertThat(saved.getBooking()).isSameAs(booking);
        assertThat(saved.getTokenHash()).isEqualTo(EmailVerificationService.hash(rawToken));
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken); // never store the raw token
    }

    @Test
    void issueLinkReplacesTheExistingTokenForTheBooking() {
        BookingManageToken existing = BookingManageToken.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .tokenHash("old-hash")
                .build();
        when(tokenRepository.findByBookingId(booking.getId())).thenReturn(Optional.of(existing));
        when(tokenRepository.save(any(BookingManageToken.class))).thenAnswer(inv -> inv.getArgument(0));

        tokenService.issueLink(booking);

        ArgumentCaptor<BookingManageToken> captor = ArgumentCaptor.forClass(BookingManageToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId()); // same row, new hash
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo("old-hash");
    }

    @Test
    void resolveReturnsBookingForAValidToken() {
        String raw = "some-raw-token";
        when(tokenRepository.findByTokenHash(EmailVerificationService.hash(raw)))
                .thenReturn(Optional.of(BookingManageToken.builder().booking(booking).build()));

        assertThat(tokenService.resolve(raw)).isSameAs(booking);
    }

    @Test
    void resolveRejectsUnknownBlankAndNullTokens() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.resolve("nope"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This manage link is invalid or has expired");
        assertThatThrownBy(() -> tokenService.resolve("  "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> tokenService.resolve(null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void resolveRejectsTokensWhoseAppointmentHasEnded() {
        booking.setEndDatetime(OffsetDateTime.now().minusMinutes(1));
        String raw = "expired-booking-token";
        when(tokenRepository.findByTokenHash(EmailVerificationService.hash(raw)))
                .thenReturn(Optional.of(BookingManageToken.builder().booking(booking).build()));

        assertThatThrownBy(() -> tokenService.resolve(raw))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This manage link is invalid or has expired");
    }
}
