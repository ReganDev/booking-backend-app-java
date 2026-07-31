package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.RecurringBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.RecurringBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingSeries;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.BookingSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingSeriesServiceTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingSeriesRepository bookingSeriesRepository;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private BookingService bookingService;
    @Mock
    private BusinessService businessService;
    @Mock
    private CustomerService customerService;
    @Mock
    private ServiceService serviceService;
    @Mock
    private UserService userService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BookingSeriesService seriesService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private Customer customer;
    private OffsetDateTime start;

    @BeforeEach
    void setUp() {
        // The calculator is pure date maths with its own test; using the real
        // one keeps these tests about the series rules rather than stubs.
        seriesService = new BookingSeriesService(
                bookingRepository, bookingSeriesRepository, bookingMapper, bookingService,
                businessService, customerService, serviceService, userService,
                new RecurrenceCalculator(), eventPublisher);

        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .slug("absolutelyfabuloushairandbeauty")
                .email("salon@example.com")
                .timezone("Europe/London")
                .bufferMinutes(0)
                .autoConfirmBookings(true)
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

        // A Tuesday, far enough out that the past check never trips.
        start = ZonedDateTime.now(LONDON)
                .plusDays(7)
                .withHour(14).withMinute(0).withSecond(0).withNano(0)
                .toOffsetDateTime();
    }

    private RecurringBookingRequest request(RecurrenceFrequency frequency, int count) {
        RecurringBookingRequest request = new RecurringBookingRequest();
        request.setCustomerId(customer.getId());
        request.setServiceId(service.getId());
        request.setStartDatetime(start);
        request.setFrequency(frequency);
        request.setOccurrenceCount(count);
        return request;
    }

    private void stubLookups() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
    }

    private void stubSaves() {
        when(bookingSeriesRepository.save(any(BookingSeries.class)))
                .thenAnswer(invocation -> {
                    BookingSeries series = invocation.getArgument(0);
                    series.setId(UUID.randomUUID());
                    return series;
                });
        lenient().when(bookingRepository.saveAll(any())).thenAnswer(invocation -> {
            List<Booking> bookings = invocation.getArgument(0);
            bookings.forEach(booking -> booking.setId(UUID.randomUUID()));
            return bookings;
        });
        lenient().when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());
    }

    @SuppressWarnings("unchecked")
    private List<Booking> captureSavedBookings() {
        ArgumentCaptor<List<Booking>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookingRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void createsOneBookingPerOccurrenceAllSharingOneSeries() {
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        RecurringBookingResponse response =
                seriesService.create(business.getId(), request(RecurrenceFrequency.WEEKLY, 12));

        List<Booking> saved = captureSavedBookings();
        assertThat(saved).hasSize(12);
        assertThat(saved).extracting(Booking::getSeries).containsOnly(saved.get(0).getSeries());
        assertThat(response.getSeriesId()).isEqualTo(saved.get(0).getSeries().getId());
        assertThat(response.getSkipped()).isEmpty();
        assertThat(response.getCreated()).hasSize(12);

        // A week apart, same wall-clock time, buffer folded into the stored end.
        assertThat(saved.get(1).getStartDatetime()).isEqualTo(start.plusWeeks(1));
        assertThat(saved.get(0).getEndDatetime()).isEqualTo(start.plusMinutes(45));
        assertThat(saved).extracting(Booking::getPrice).containsOnly(new BigDecimal("32.50"));
        assertThat(saved).extracting(Booking::getStatus).containsOnly(BookingStatus.CONFIRMED);
    }

    @Test
    void skipsClashingOccurrencesAndReportsThemInsteadOfFailing() {
        stubLookups();
        stubSaves();
        OffsetDateTime clashing = start.plusWeeks(2);
        when(bookingService.hasConflict(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> clashing.equals(invocation.getArgument(2)));

        RecurringBookingResponse response =
                seriesService.create(business.getId(), request(RecurrenceFrequency.WEEKLY, 4));

        assertThat(captureSavedBookings()).hasSize(3);
        assertThat(response.getSkipped()).hasSize(1);
        assertThat(response.getSkipped().get(0).getStartDatetime()).isEqualTo(clashing);
        assertThat(response.getSkipped().get(0).getReason()).isEqualTo("Already booked");
    }

    @Test
    void rejectsTheSeriesOnlyWhenEveryOccurrenceClashes() {
        stubLookups();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> seriesService.create(
                business.getId(), request(RecurrenceFrequency.WEEKLY, 4)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("clashes");

        verify(bookingSeriesRepository, never()).save(any());
        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    void rejectsAFirstOccurrenceInThePast() {
        stubLookups();
        RecurringBookingRequest request = request(RecurrenceFrequency.WEEKLY, 4);
        request.setStartDatetime(OffsetDateTime.now().minusHours(1));

        assertThatThrownBy(() -> seriesService.create(business.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("past");

        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    void rejectsACustomerFromAnotherBusiness() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        customer.setBusiness(Business.builder().id(UUID.randomUUID()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);

        assertThatThrownBy(() -> seriesService.create(
                business.getId(), request(RecurrenceFrequency.WEEKLY, 4)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Customer does not belong");

        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    void rejectsAServiceFromAnotherBusiness() {
        stubLookups();
        service.setBusiness(Business.builder().id(UUID.randomUUID()).build());

        assertThatThrownBy(() -> seriesService.create(
                business.getId(), request(RecurrenceFrequency.WEEKLY, 4)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Service does not belong");

        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    void everyOccurrenceStaysPendingWhenAutoConfirmIsOff() {
        business.setAutoConfirmBookings(false);
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        seriesService.create(business.getId(), request(RecurrenceFrequency.FORTNIGHTLY, 3));

        assertThat(captureSavedBookings())
                .extracting(Booking::getStatus)
                .containsOnly(BookingStatus.PENDING);
    }

    @Test
    void assignsTheChosenStaffMemberToEveryOccurrence() {
        User stylist = User.builder()
                .id(UUID.randomUUID())
                .business(business)
                .firstName("Alex")
                .lastName("Stylist")
                .build();

        stubLookups();
        stubSaves();
        when(userService.getEntityById(stylist.getId())).thenReturn(stylist);
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        RecurringBookingRequest request = request(RecurrenceFrequency.WEEKLY, 3);
        request.setStaffId(stylist.getId());

        seriesService.create(business.getId(), request);

        assertThat(captureSavedBookings()).extracting(Booking::getStaff).containsOnly(stylist);
    }

    @Test
    void rejectsAStaffMemberFromAnotherBusiness() {
        User outsider = User.builder()
                .id(UUID.randomUUID())
                .business(Business.builder().id(UUID.randomUUID()).build())
                .build();

        stubLookups();
        when(userService.getEntityById(outsider.getId())).thenReturn(outsider);

        RecurringBookingRequest request = request(RecurrenceFrequency.WEEKLY, 3);
        request.setStaffId(outsider.getId());

        assertThatThrownBy(() -> seriesService.create(business.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Staff does not belong");

        verify(bookingRepository, never()).saveAll(any());
    }

    private RecurringBookingRequest requestWithAddress(RecurrenceFrequency frequency, int count) {
        RecurringBookingRequest request = request(frequency, count);
        request.setAddressLine1("1 High Street");
        request.setAddressLine2("Flat 2");
        request.setAddressCity("Manchester");
        request.setAddressPostcode("m11ae");
        return request;
    }

    @Test
    void copiesTheCustomerAddressOntoEveryOccurrence() {
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        seriesService.create(business.getId(), requestWithAddress(RecurrenceFrequency.WEEKLY, 3));

        List<Booking> saved = captureSavedBookings();
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(Booking::getAddressLine1).containsOnly("1 High Street");
        assertThat(saved).extracting(Booking::getAddressLine2).containsOnly("Flat 2");
        assertThat(saved).extracting(Booking::getAddressCity).containsOnly("Manchester");
        assertThat(saved).extracting(Booking::getAddressPostcode).containsOnly("M1 1AE");
    }

    @Test
    void publishesOneDistanceEventForTheFirstCreatedOccurrence() {
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        seriesService.create(business.getId(), requestWithAddress(RecurrenceFrequency.WEEKLY, 4));

        ArgumentCaptor<BookingCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(BookingCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        List<Booking> saved = captureSavedBookings();
        assertThat(saved.get(0).getId()).isNotNull();
        assertThat(eventCaptor.getValue().bookingId()).isEqualTo(saved.get(0).getId());
    }

    @Test
    void distanceEventCarriesTheFirstSurvivingOccurrenceWhenTheFirstClashes() {
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> start.equals(invocation.getArgument(2)));

        seriesService.create(business.getId(), requestWithAddress(RecurrenceFrequency.WEEKLY, 3));

        ArgumentCaptor<BookingCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(BookingCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        List<Booking> saved = captureSavedBookings();
        assertThat(saved.get(0).getStartDatetime()).isEqualTo(start.plusWeeks(1));
        assertThat(eventCaptor.getValue().bookingId()).isEqualTo(saved.get(0).getId());
    }

    @Test
    void publishesNoDistanceEventWithoutAPostcode() {
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        seriesService.create(business.getId(), request(RecurrenceFrequency.WEEKLY, 3));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void skipsAnOccurrenceThatWouldOverlapAnEarlierOneInTheSameSeries() {
        // An 8-day service booked weekly overlaps itself. The siblings aren't
        // in the database yet, so the repository conflict check cannot see
        // them and the series would happily double-book its own customer.
        service.setDurationMinutes(60 * 24 * 8);
        stubLookups();
        stubSaves();
        when(bookingService.hasConflict(any(), any(), any(), any(), any())).thenReturn(false);

        RecurringBookingResponse response =
                seriesService.create(business.getId(), request(RecurrenceFrequency.WEEKLY, 3));

        // Occurrence 2 (day 7) overlaps occurrence 1 (days 0-8) and is dropped.
        // Occurrence 3 (day 14) is compared against what was actually kept, not
        // against the one that was skipped, so it starts clear and survives.
        List<Booking> saved = captureSavedBookings();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(Booking::getStartDatetime)
                .containsExactly(start, start.plusWeeks(2));
        assertThat(response.getSkipped()).hasSize(1);
        assertThat(response.getSkipped().get(0).getStartDatetime()).isEqualTo(start.plusWeeks(1));
    }
}
