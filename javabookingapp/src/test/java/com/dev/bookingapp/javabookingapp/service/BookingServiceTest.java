package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.request.CustomerRequest;
import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private BusinessService businessService;
    @Mock
    private CustomerService customerService;
    @Mock
    private ServiceService serviceService;
    @Mock
    private UserService userService;
    @Mock
    private AvailabilityService availabilityService;

    @InjectMocks
    private BookingService bookingService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private Customer customer;
    private OffsetDateTime start;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .slug("absolutelyfabuloushairandbeauty")
                .email("salon@example.com")
                .bufferMinutes(0)
                .isActive(true)
                .build();

        service = com.dev.bookingapp.javabookingapp.entity.Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(45)
                .price(new BigDecimal("32.50"))
                .isActive(true)
                .build();

        customer = Customer.builder()
                .id(UUID.randomUUID())
                .business(business)
                .email("customer@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .build();

        start = OffsetDateTime.now().plusDays(1).withNano(0);
    }

    private PublicBookingRequest publicRequest() {
        CustomerRequest customerRequest = new CustomerRequest();
        customerRequest.setEmail(customer.getEmail());
        customerRequest.setFirstName(customer.getFirstName());
        customerRequest.setLastName(customer.getLastName());

        PublicBookingRequest request = new PublicBookingRequest();
        request.setCustomer(customerRequest);
        request.setServiceId(service.getId());
        request.setStartDatetime(start);
        return request;
    }

    @Test
    void publicBookingRejectedWhenBusinessIsInactive() {
        business.setIsActive(false);
        when(businessService.getEntityById(business.getId())).thenReturn(business);

        assertThatThrownBy(() -> bookingService.createPublicBooking(business.getId(), publicRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not currently accepting");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingRejectedWhenServiceBelongsToAnotherBusiness() {
        service.setBusiness(Business.builder().id(UUID.randomUUID()).build());
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> bookingService.createPublicBooking(business.getId(), publicRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingRejectedWhenServiceIsInactive() {
        service.setIsActive(false);
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> bookingService.createPublicBooking(business.getId(), publicRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not available");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingRejectedWhenSlotIsNotAvailable() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        doThrow(new ConflictException("This time slot is not available. Please choose another time."))
                .when(availabilityService).ensureSlotAvailable(business, service, start);

        assertThatThrownBy(() -> bookingService.createPublicBooking(business.getId(), publicRequest()))
                .isInstanceOf(ConflictException.class);
        verify(customerService, never()).getOrCreate(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingHappyPathCreatesPendingBookingWithServicePrice() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreate(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.createPublicBooking(business.getId(), publicRequest());

        verify(availabilityService).ensureSlotAvailable(business, service, start);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        Booking saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(saved.getPrice()).isEqualByComparingTo("32.50");
        assertThat(saved.getStaff()).isNull();
        assertThat(saved.getEndDatetime()).isEqualTo(start.plusMinutes(45));
    }

    @Test
    void bookingWithoutStaffRejectedWhenAnotherBookingOverlaps() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of(new Booking()));

        BookingRequest request = new BookingRequest();
        request.setCustomerId(customer.getId());
        request.setServiceId(service.getId());
        request.setStartDatetime(start);

        assertThatThrownBy(() -> bookingService.create(business.getId(), request))
                .isInstanceOf(ConflictException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void bookingInThePastIsRejected() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);

        BookingRequest request = new BookingRequest();
        request.setCustomerId(customer.getId());
        request.setServiceId(service.getId());
        request.setStartDatetime(OffsetDateTime.now().minusHours(1));

        assertThatThrownBy(() -> bookingService.create(business.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("past");
        verify(bookingRepository, never()).save(any());
    }
}
