package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Fills in {@code distanceMeters}/{@code durationSeconds} on a booking after
 * it commits: geocode the business postcode (cached on the row), geocode the
 * customer postcode, ask OSRM for the driving route. Runs async in its own
 * transaction so no failure here can ever touch the booking itself — a
 * booking without a distance just shows the address on the dashboard.
 *
 * <p>A recurring series publishes a single event for its first occurrence;
 * the computed route is copied here onto every sibling that shares the same
 * postcode, so the external services are hit once per series, not once per
 * occurrence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingDistanceService {

    private final BookingRepository bookingRepository;
    private final BusinessRepository businessRepository;
    private final PostcodesIoClient postcodesIoClient;
    private final OsrmClient osrmClient;

    @Value("${app.geo.enabled:true}")
    private boolean enabled;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCreated(BookingCreatedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            computeDistance(event.bookingId());
        } catch (RuntimeException ex) {
            // Async exceptions vanish silently; log so the failure is visible
            log.error("Distance computation failed for booking {}", event.bookingId(), ex);
        }
    }

    private void computeDistance(java.util.UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getAddressPostcode() == null) {
            return;
        }

        Optional<PostcodesIoClient.Coordinates> origin = resolveBusinessOrigin(booking.getBusiness());
        if (origin.isEmpty()) {
            log.warn("No usable business origin for booking {}; skipping distance", bookingId);
            return;
        }

        Optional<PostcodesIoClient.Coordinates> destination =
                postcodesIoClient.lookup(booking.getAddressPostcode());
        if (destination.isEmpty()) {
            log.warn("Customer postcode '{}' did not geocode for booking {}",
                    booking.getAddressPostcode(), bookingId);
            return;
        }

        osrmClient.route(origin.get(), destination.get()).ifPresent(route -> {
            applyRoute(booking, route);
            propagateToSeriesSiblings(booking, route);
        });
    }

    private void applyRoute(Booking booking, OsrmClient.Route route) {
        booking.setDistanceMeters(route.distanceMeters());
        booking.setDurationSeconds(route.durationSeconds());
        bookingRepository.save(booking);
    }

    private void propagateToSeriesSiblings(Booking booking, OsrmClient.Route route) {
        if (booking.getSeries() == null) {
            return;
        }
        bookingRepository.findBySeriesId(booking.getSeries().getId()).stream()
                .filter(sibling -> !sibling.getId().equals(booking.getId()))
                .filter(sibling -> booking.getAddressPostcode().equals(sibling.getAddressPostcode()))
                .filter(sibling -> sibling.getDistanceMeters() == null)
                .forEach(sibling -> applyRoute(sibling, route));
    }

    private Optional<PostcodesIoClient.Coordinates> resolveBusinessOrigin(Business business) {
        String postcode = BookingService.normalizePostcode(business.getPostalCode());
        if (postcode == null) {
            return Optional.empty();
        }
        if (postcode.equals(business.getGeocodedPostcode())
                && business.getLatitude() != null && business.getLongitude() != null) {
            return Optional.of(new PostcodesIoClient.Coordinates(
                    business.getLatitude(), business.getLongitude()));
        }
        Optional<PostcodesIoClient.Coordinates> coordinates = postcodesIoClient.lookup(postcode);
        coordinates.ifPresent(c -> {
            business.setLatitude(c.latitude());
            business.setLongitude(c.longitude());
            business.setGeocodedPostcode(postcode);
            businessRepository.save(business);
        });
        return coordinates;
    }
}
