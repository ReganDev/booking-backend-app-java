package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageBookingServiceTest {

    @Mock
    private BookingManageTokenService tokenService;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private AvailabilityService availabilityService;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingNotificationService notificationService;

    @InjectMocks
    private ManageBookingService manageBookingService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private Customer customer;
    private Booking booking;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .slug("absolutelyfabuloushairandbeauty")
                .email("salon@example.com")
                .phone("01234 567890")
                .cancellationNoticeHours(24)
                .bookingAdvanceDays(30)
                .build();
        service = com.dev.bookingapp.javabookingapp.entity.Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(45)
                .build();
        customer = Customer.builder()
                .id(UUID.randomUUID())
                .business(business)
                .email("jane@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .build();
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .business(business)
                .service(service)
                .customer(customer)
                .status(BookingStatus.CONFIRMED)
                .startDatetime(OffsetDateTime.now().plusDays(3))
                .endDatetime(OffsetDateTime.now().plusDays(3).plusMinutes(45))
                .build();
    }

    @Test
    void getByTokenReturnsBookingWithBusinessContactAndFlags() {
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.getBusinessName()).isEqualTo(business.getName());
        assertThat(response.getBusinessSlug()).isEqualTo(business.getSlug());
        assertThat(response.getBusinessEmail()).isEqualTo(business.getEmail());
        assertThat(response.getBusinessPhone()).isEqualTo(business.getPhone());
        assertThat(response.getCancellationNoticeHours()).isEqualTo(24);
        assertThat(response.getBookingAdvanceDays()).isEqualTo(30);
        assertThat(response.isCanCancel()).isTrue();
        assertThat(response.isCanReschedule()).isTrue();
    }

    @Test
    void flagsAreFalseInsideTheCancellationCutoff() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2)); // < 24h notice
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.isCanCancel()).isFalse();
        assertThat(response.isCanReschedule()).isFalse();
    }

    @Test
    void flagsAreFalseForCancelledBookings() {
        booking.setStatus(BookingStatus.CANCELLED);
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.isCanCancel()).isFalse();
        assertThat(response.isCanReschedule()).isFalse();
    }

    @Test
    void cancelByTokenCancelsAndNotifiesTheBusiness() {
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.cancelByToken("tok");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelledAt()).isNotNull();
        assertThat(booking.getCancellationReason()).isEqualTo("Cancelled by customer");
        assertThat(response.isCanCancel()).isFalse();
        verify(notificationService).sendBusinessCancelledNotice(any(), any());
    }

    @Test
    void cancelInsideCutoffIsRejectedWithContactMessage() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2));
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.cancelByToken("tok"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("contact Absolutely Fabulous Hair and Beauty");
        verify(bookingRepository, never()).save(any());
        verify(notificationService, never()).sendBusinessCancelledNotice(any(), any());
    }

    @Test
    void cancellingAnAlreadyCancelledBookingIsRejected() {
        booking.setStatus(BookingStatus.CANCELLED);
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.cancelByToken("tok"))
                .isInstanceOf(BadRequestException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void rescheduleByTokenValidatesSlotReschedulesAndNotifies() {
        OffsetDateTime newStart = OffsetDateTime.now().plusDays(5);
        OffsetDateTime oldStart = booking.getStartDatetime();
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingService.reschedule(business.getId(), booking.getId(), newStart))
                .thenReturn(BookingResponse.builder().build());
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        manageBookingService.rescheduleByToken("tok", newStart);

        verify(availabilityService).ensureSlotAvailable(business, service, newStart);
        verify(bookingService).reschedule(business.getId(), booking.getId(), newStart);
        verify(notificationService).sendBusinessRescheduledNotice(any(), any(), org.mockito.ArgumentMatchers.eq(oldStart));
    }

    @Test
    void rescheduleInsideCutoffIsRejectedBeforeTouchingAvailability() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2));
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.rescheduleByToken(
                "tok", OffsetDateTime.now().plusDays(5)))
                .isInstanceOf(BadRequestException.class);
        verify(availabilityService, never()).ensureSlotAvailable(any(), any(), any());
        verify(bookingService, never()).reschedule(any(), any(), any());
    }

    @Test
    void listForCustomerMapsAllBookingsForTheEmail() {
        when(bookingRepository.findByCustomerEmail("jane@example.com"))
                .thenReturn(List.of(booking));
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        List<ManageBookingResponse> list = manageBookingService.listForCustomer("jane@example.com");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBusinessName()).isEqualTo(business.getName());
    }

    @Test
    void customerCannotTouchAnotherCustomersBooking() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> manageBookingService.cancelForCustomer(
                "intruder@example.com", booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void cancelForCustomerMatchesEmailCaseInsensitively() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(BookingResponse.builder().build());

        manageBookingService.cancelForCustomer("JANE@example.com", booking.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
