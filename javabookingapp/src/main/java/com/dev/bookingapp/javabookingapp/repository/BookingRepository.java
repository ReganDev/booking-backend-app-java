package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByBusinessIdAndId(UUID businessId, UUID bookingId);

    Page<Booking> findByBusinessId(UUID businessId, Pageable pageable);

    List<Booking> findByBusinessIdAndStartDatetimeBetween(UUID businessId,
                                                           OffsetDateTime start,
                                                           OffsetDateTime end);

    List<Booking> findByBusinessIdAndStaffIdAndStartDatetimeBetween(UUID businessId,
                                                                     UUID staffId,
                                                                     OffsetDateTime start,
                                                                     OffsetDateTime end);

    List<Booking> findByCustomerId(UUID customerId);

    @Query("SELECT b FROM Booking b WHERE b.business.id = :businessId " +
           "AND b.startDatetime >= :start AND b.endDatetime <= :end " +
           "AND b.status NOT IN ('CANCELLED')")
    List<Booking> findActiveBookingsInRange(@Param("businessId") UUID businessId,
                                            @Param("start") OffsetDateTime start,
                                            @Param("end") OffsetDateTime end);

    @Query("SELECT b FROM Booking b WHERE b.staff.id = :staffId " +
           "AND b.startDatetime < :end AND b.endDatetime > :start " +
           "AND b.status NOT IN ('CANCELLED') " +
           "AND (:excludeBookingId IS NULL OR b.id != :excludeBookingId)")
    List<Booking> findConflictingBookings(@Param("staffId") UUID staffId,
                                          @Param("start") OffsetDateTime start,
                                          @Param("end") OffsetDateTime end,
                                          @Param("excludeBookingId") UUID excludeBookingId);

    @Query("SELECT b FROM Booking b WHERE b.business.id = :businessId " +
           "AND b.status = :status " +
           "ORDER BY b.startDatetime ASC")
    Page<Booking> findByBusinessIdAndStatus(@Param("businessId") UUID businessId,
                                            @Param("status") BookingStatus status,
                                            Pageable pageable);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.business.id = :businessId " +
           "AND b.startDatetime >= :start AND b.startDatetime < :end " +
           "AND b.status NOT IN ('CANCELLED')")
    long countBookingsInPeriod(@Param("businessId") UUID businessId,
                               @Param("start") OffsetDateTime start,
                               @Param("end") OffsetDateTime end);
}
