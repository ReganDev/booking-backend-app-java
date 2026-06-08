package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BlockedTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BlockedTimeRepository extends JpaRepository<BlockedTime, UUID> {

    List<BlockedTime> findByBusinessIdAndUserIsNull(UUID businessId);

    List<BlockedTime> findByBusinessIdAndUserId(UUID businessId, UUID userId);

    @Query("SELECT bt FROM BlockedTime bt WHERE bt.business.id = :businessId " +
           "AND (bt.user IS NULL OR bt.user.id = :userId) " +
           "AND bt.startDatetime < :end AND bt.endDatetime > :start")
    List<BlockedTime> findBlockedTimesInRange(@Param("businessId") UUID businessId,
                                               @Param("userId") UUID userId,
                                               @Param("start") OffsetDateTime start,
                                               @Param("end") OffsetDateTime end);

    @Query("SELECT bt FROM BlockedTime bt WHERE bt.business.id = :businessId " +
           "AND bt.user IS NULL " +
           "AND bt.startDatetime < :end AND bt.endDatetime > :start")
    List<BlockedTime> findBusinessBlockedTimesInRange(@Param("businessId") UUID businessId,
                                                       @Param("start") OffsetDateTime start,
                                                       @Param("end") OffsetDateTime end);
}
