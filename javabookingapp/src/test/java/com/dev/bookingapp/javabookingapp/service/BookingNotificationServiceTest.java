package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingNotificationServiceTest {

    @Mock
    private ResendEmailSender emailSender;

    @InjectMocks
    private BookingNotificationService notificationService;

    private Business business;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .email("salon@example.com")
                .timezone("Europe/London")
                .currency("GBP")
                .build();
        when(emailSender.isConfigured()).thenReturn(true);
    }

    private BookingResponse booking(BookingStatus status) {
        return BookingResponse.builder()
                .id(UUID.randomUUID())
                .status(status)
                .startDatetime(OffsetDateTime.now().plusDays(1))
                .price(new BigDecimal("32.50"))
                .customer(BookingResponse.CustomerInfo.builder()
                        .firstName("Jane")
                        .email("jane@example.com")
                        .build())
                .service(BookingResponse.ServiceInfo.builder()
                        .name("Haircut")
                        .durationMinutes(45)
                        .build())
                .build();
    }

    private String sentText(BookingStatus status) {
        notificationService.sendBookingDetails(
                business, booking(status), "jane@example.com");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(
                eq(business.getName()),
                eq("jane@example.com"),
                eq(business.getEmail()),
                any(String.class),
                textCaptor.capture());
        return textCaptor.getValue();
    }

    @Test
    void confirmedBookingEmailSaysBookingIsConfirmed() {
        String text = sentText(BookingStatus.CONFIRMED);

        assertThat(text).contains("your booking is confirmed");
        assertThat(text).doesNotContain("awaiting confirmation");
        assertThat(text).doesNotContain("booking request");
    }

    @Test
    void pendingBookingEmailKeepsAwaitingConfirmationCopy() {
        String text = sentText(BookingStatus.PENDING);

        assertThat(text).contains("booking request");
        assertThat(text).contains("awaiting confirmation");
        assertThat(text).doesNotContain("your booking is confirmed");
    }
}
