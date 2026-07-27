package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import com.dev.bookingapp.javabookingapp.service.OsrmClient.Route;
import com.dev.bookingapp.javabookingapp.service.PostcodesIoClient.Coordinates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingDistanceServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock BusinessRepository businessRepository;
    @Mock PostcodesIoClient postcodesIoClient;
    @Mock OsrmClient osrmClient;
    @InjectMocks BookingDistanceService distanceService;

    private Business business;
    private Booking booking;

    private static final Coordinates ORIGIN = new Coordinates(51.501, -0.142);
    private static final Coordinates DESTINATION = new Coordinates(53.478, -2.243);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(distanceService, "enabled", true);
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Fab Mobile Hair")
                .postalCode("SW1A 1AA")
                .build();
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .business(business)
                .addressPostcode("M1 1AE")
                .build();
        org.mockito.Mockito.lenient()
                .when(bookingRepository.findById(booking.getId()))
                .thenReturn(Optional.of(booking));
    }

    @Test
    void happyPathStoresRouteAndCachesBusinessGeocode() {
        when(postcodesIoClient.lookup("SW1A 1AA")).thenReturn(Optional.of(ORIGIN));
        when(postcodesIoClient.lookup("M1 1AE")).thenReturn(Optional.of(DESTINATION));
        when(osrmClient.route(ORIGIN, DESTINATION))
                .thenReturn(Optional.of(new Route(11587, 1080)));

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        assertThat(booking.getDistanceMeters()).isEqualTo(11587);
        assertThat(booking.getDurationSeconds()).isEqualTo(1080);
        verify(bookingRepository).save(booking);
        assertThat(business.getLatitude()).isEqualTo(ORIGIN.latitude());
        assertThat(business.getGeocodedPostcode()).isEqualTo("SW1A 1AA");
        verify(businessRepository).save(business);
    }

    @Test
    void cachedBusinessGeocodeSkipsTheOriginLookup() {
        business.setLatitude(ORIGIN.latitude());
        business.setLongitude(ORIGIN.longitude());
        business.setGeocodedPostcode("SW1A 1AA");
        when(postcodesIoClient.lookup("M1 1AE")).thenReturn(Optional.of(DESTINATION));
        when(osrmClient.route(any(), any())).thenReturn(Optional.of(new Route(1, 1)));

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        verify(postcodesIoClient, times(1)).lookup(any());
        verify(postcodesIoClient).lookup("M1 1AE");
        verify(businessRepository, never()).save(any());
    }

    @Test
    void staleCachedPostcodeIsReGeocoded() {
        business.setLatitude(1.0);
        business.setLongitude(1.0);
        business.setGeocodedPostcode("OL1 1AA"); // owner has since moved
        when(postcodesIoClient.lookup("SW1A 1AA")).thenReturn(Optional.of(ORIGIN));
        when(postcodesIoClient.lookup("M1 1AE")).thenReturn(Optional.of(DESTINATION));
        when(osrmClient.route(ORIGIN, DESTINATION)).thenReturn(Optional.of(new Route(1, 1)));

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        assertThat(business.getGeocodedPostcode()).isEqualTo("SW1A 1AA");
        verify(businessRepository).save(business);
    }

    @Test
    void geocodeFailureLeavesDistanceNullAndDoesNotThrow() {
        when(postcodesIoClient.lookup(any())).thenReturn(Optional.empty());

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        assertThat(booking.getDistanceMeters()).isNull();
        verify(osrmClient, never()).route(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void routingFailureLeavesDistanceNull() {
        when(postcodesIoClient.lookup("SW1A 1AA")).thenReturn(Optional.of(ORIGIN));
        when(postcodesIoClient.lookup("M1 1AE")).thenReturn(Optional.of(DESTINATION));
        when(osrmClient.route(ORIGIN, DESTINATION)).thenReturn(Optional.empty());

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        assertThat(booking.getDistanceMeters()).isNull();
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void bookingWithoutPostcodeIsIgnored() {
        booking.setAddressPostcode(null);

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        verifyNoInteractions(postcodesIoClient, osrmClient);
    }

    @Test
    void businessWithoutPostcodeSkipsAllLookups() {
        business.setPostalCode(null);

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        verifyNoInteractions(postcodesIoClient, osrmClient);
        assertThat(booking.getDistanceMeters()).isNull();
    }

    @Test
    void disabledFlagShortCircuitsEverything() {
        ReflectionTestUtils.setField(distanceService, "enabled", false);

        distanceService.onBookingCreated(new BookingCreatedEvent(booking.getId()));

        verifyNoInteractions(bookingRepository, postcodesIoClient, osrmClient);
    }
}
