package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.Schedule;
import com.dev.bookingapp.javabookingapp.entity.enums.DayOfWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByBusinessIdAndUserIsNull(UUID businessId);

    List<Schedule> findByBusinessIdAndUserId(UUID businessId, UUID userId);

    Optional<Schedule> findByBusinessIdAndUserIdAndDayOfWeek(UUID businessId, UUID userId, DayOfWeek dayOfWeek);

    Optional<Schedule> findByBusinessIdAndUserIsNullAndDayOfWeek(UUID businessId, DayOfWeek dayOfWeek);

    @Query("SELECT s FROM Schedule s WHERE s.business.id = :businessId AND (s.user IS NULL OR s.user.id = :userId) AND s.isActive = true")
    List<Schedule> findActiveSchedulesForStaff(@Param("businessId") UUID businessId, @Param("userId") UUID userId);

    void deleteByBusinessIdAndUserId(UUID businessId, UUID userId);
}
