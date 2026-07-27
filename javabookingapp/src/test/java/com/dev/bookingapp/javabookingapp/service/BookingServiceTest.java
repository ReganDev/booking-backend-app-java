package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.request.BookingStatusRequest;
import com.dev.bookingapp.javabookingapp.dto.request.CancelScope;
import com.dev.bookingapp.javabookingapp.dto.request.CustomerRequest;
import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingSeries;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ForbiddenException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock
    private BookingNotificationService bookingNotificationService;
    @Mock
    private BookingManageTokenService manageTokenService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private BookingService bookingService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private Customer customer;
    private User customerAccount;
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

        customerAccount = User.builder()
                .id(UUID.randomUUID())
                .email(customer.getEmail())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(true)
                .build();

        start = OffsetDateTime.now().plusDays(1).withNano(0);
    }

    private PublicBookingRequest publicRequest() {
        when(userService.getEntityById(customerAccount.getId())).thenReturn(customerAccount);
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

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not currently accepting");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingRejectsUnverifiedCustomerIdentity() {
        customerAccount.setEmailVerified(false);
        PublicBookingRequest request = publicRequest();

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), request, customerAccount.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("verified customer");
        verify(businessService, never()).getEntityById(any());
        verify(customerService, never()).getOrCreateFromUser(any(), any());
    }

    @Test
    void publicBookingRejectedWhenServiceBelongsToAnotherBusiness() {
        service.setBusiness(Business.builder().id(UUID.randomUUID()).build());
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingRejectedWhenServiceIsInactive() {
        service.setIsActive(false);
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId()))
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

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId()))
                .isInstanceOf(ConflictException.class);
        verify(customerService, never()).getOrCreateFromUser(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void publicBookingHappyPathConfirmsBookingWithServicePrice() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreateFromUser(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId());

        verify(availabilityService).ensureSlotAvailable(business, service, start);
        verify(customerService).getOrCreateFromUser(business.getId(), customerAccount);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        Booking saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
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

    private BookingRequest directRequest() {
        BookingRequest request = new BookingRequest();
        request.setCustomerId(customer.getId());
        request.setServiceId(service.getId());
        request.setStartDatetime(start);
        return request;
    }

    private Booking createAndCaptureSavedBooking() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.create(business.getId(), directRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void bookingStaysPendingWhenAutoConfirmIsOff() {
        business.setAutoConfirmBookings(false);

        Booking saved = createAndCaptureSavedBooking();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void bookingStaysPendingWhenAutoConfirmFlagIsNull() {
        // Defensive: a null flag (e.g. an entity built without the default)
        // must fall back to the old request/approve behavior, never confirm.
        business.setAutoConfirmBookings(null);

        Booking saved = createAndCaptureSavedBooking();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    private Booking seriesOccurrence(BookingSeries series, OffsetDateTime at, BookingStatus status) {
        return Booking.builder()
                .id(UUID.randomUUID())
                .business(business)
                .customer(customer)
                .service(service)
                .series(series)
                .startDatetime(at)
                .endDatetime(at.plusMinutes(45))
                .status(status)
                .build();
    }

    private BookingStatusRequest cancelRequest(CancelScope scope) {
        BookingStatusRequest request = new BookingStatusRequest();
        request.setStatus(BookingStatus.CANCELLED);
        request.setCancellationReason("Client moved away");
        request.setScope(scope);
        return request;
    }

    @Test
    void cancellingWithThisAndFutureAlsoCancelsLaterOccurrencesOfTheSeries() {
        BookingSeries series = BookingSeries.builder().id(UUID.randomUUID()).build();
        Booking target = seriesOccurrence(series, start, BookingStatus.CONFIRMED);
        Booking nextWeek = seriesOccurrence(series, start.plusWeeks(1), BookingStatus.CONFIRMED);
        Booking weekAfter = seriesOccurrence(series, start.plusWeeks(2), BookingStatus.PENDING);

        when(bookingRepository.findByBusinessIdAndId(business.getId(), target.getId()))
                .thenReturn(java.util.Optional.of(target));
        when(bookingRepository.findBySeriesIdAndStartDatetimeGreaterThanEqualAndStatusIn(
                eq(series.getId()), eq(start), any()))
                .thenReturn(List.of(target, nextWeek, weekAfter));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.updateStatus(
                business.getId(), target.getId(), cancelRequest(CancelScope.THIS_AND_FUTURE));

        assertThat(target.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(nextWeek.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(weekAfter.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(nextWeek.getCancellationReason()).isEqualTo("Client moved away");
        // The target must not be written twice: it is in the sibling query result.
        verify(bookingRepository, times(3)).save(any(Booking.class));
    }

    @Test
    void cancellingWithThisOnlyLeavesTheRestOfTheSeriesAlone() {
        BookingSeries series = BookingSeries.builder().id(UUID.randomUUID()).build();
        Booking target = seriesOccurrence(series, start, BookingStatus.CONFIRMED);

        when(bookingRepository.findByBusinessIdAndId(business.getId(), target.getId()))
                .thenReturn(java.util.Optional.of(target));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.updateStatus(
                business.getId(), target.getId(), cancelRequest(CancelScope.THIS_ONLY));

        assertThat(target.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository, never())
                .findBySeriesIdAndStartDatetimeGreaterThanEqualAndStatusIn(any(), any(), any());
    }

    @Test
    void thisAndFutureOnABookingWithNoSeriesTouchesOnlyThatBooking() {
        Booking loner = seriesOccurrence(null, start, BookingStatus.CONFIRMED);

        when(bookingRepository.findByBusinessIdAndId(business.getId(), loner.getId()))
                .thenReturn(java.util.Optional.of(loner));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.updateStatus(
                business.getId(), loner.getId(), cancelRequest(CancelScope.THIS_AND_FUTURE));

        assertThat(loner.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository, never())
                .findBySeriesIdAndStartDatetimeGreaterThanEqualAndStatusIn(any(), any(), any());
    }

    @Test
    void statusChangeWithNoScopeBehavesAsItAlwaysHas() {
        // Every caller that predates recurrence sends no scope at all.
        Booking booking = seriesOccurrence(null, start, BookingStatus.PENDING);

        when(bookingRepository.findByBusinessIdAndId(business.getId(), booking.getId()))
                .thenReturn(java.util.Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        BookingStatusRequest request = new BookingStatusRequest();
        request.setStatus(BookingStatus.CONFIRMED);

        bookingService.updateStatus(business.getId(), booking.getId(), request);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getCancelledAt()).isNull();
    }

    @Test
    void mobileServiceBookingRejectedWhenAddressIsMissing() {
        service.setRequiresCustomerAddress(true);
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("address");
        verify(availabilityService, never()).ensureSlotAvailable(any(), any(), any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void mobileServiceBookingRejectedWhenPostcodeIsInvalid() {
        service.setRequiresCustomerAddress(true);
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        PublicBookingRequest request = publicRequest();
        request.setAddressLine1("1 High Street");
        request.setAddressCity("Manchester");
        request.setAddressPostcode("NOT A CODE");

        assertThatThrownBy(() -> bookingService.createPublicBooking(
                business.getId(), request, customerAccount.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("postcode");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void mobileServiceBookingCarriesNormalizedAddressThroughToTheEntityMapping() {
        service.setRequiresCustomerAddress(true);
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreateFromUser(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        PublicBookingRequest request = publicRequest();
        request.setAddressLine1("1 High Street");
        request.setAddressLine2("Flat 2");
        request.setAddressCity("Manchester");
        request.setAddressPostcode("  sw1a1aa ");

        bookingService.createPublicBooking(business.getId(), request, customerAccount.getId());

        ArgumentCaptor<BookingRequest> captor = ArgumentCaptor.forClass(BookingRequest.class);
        verify(bookingMapper).toEntity(captor.capture());
        BookingRequest mapped = captor.getValue();
        assertThat(mapped.getAddressLine1()).isEqualTo("1 High Street");
        assertThat(mapped.getAddressLine2()).isEqualTo("Flat 2");
        assertThat(mapped.getAddressCity()).isEqualTo("Manchester");
        assertThat(mapped.getAddressPostcode()).isEqualTo("SW1A 1AA");
    }

    @Test
    void bookingWithPostcodePublishesDistanceEventAfterSave() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        Booking entity = new Booking();
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(entity);
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            // The mocked mapper doesn't copy fields, so mimic the postcode landing
            saved.setAddressPostcode("M1 1AE");
            return saved;
        });
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.create(business.getId(), directRequest());

        verify(eventPublisher).publishEvent(new BookingCreatedEvent(entity.getId()));
    }

    @Test
    void bookingWithoutPostcodePublishesNoEvent() {
        createAndCaptureSavedBooking();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void ordinaryServiceStillBooksWithoutAnyAddress() {
        // Regression guard: the address requirement must not leak onto
        // services that never asked for it.
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreateFromUser(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.createPublicBooking(
                business.getId(), publicRequest(), customerAccount.getId());

        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void publicBookingAlwaysSendsDetailsEmailWithManageLink() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreateFromUser(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().id(UUID.randomUUID()).build());
        when(manageTokenService.issueLink(any())).thenReturn("https://x/manage/booking/tok");

        PublicBookingRequest request = publicRequest();
        request.setEmailReminder(false); // opting out must no longer suppress the email

        bookingService.createPublicBooking(business.getId(), request, customerAccount.getId());

        verify(bookingNotificationService).sendBookingDetails(
                eq(business), any(BookingResponse.class), eq(customerAccount.getEmail()),
                eq("https://x/manage/booking/tok"));
    }
}
