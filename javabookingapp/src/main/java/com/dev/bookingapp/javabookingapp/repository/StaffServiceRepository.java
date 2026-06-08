package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.StaffService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffServiceRepository extends JpaRepository<StaffService, UUID> {

    List<StaffService> findByUserId(UUID userId);

    List<StaffService> findByServiceId(UUID serviceId);

    Optional<StaffService> findByUserIdAndServiceId(UUID userId, UUID serviceId);

    boolean existsByUserIdAndServiceId(UUID userId, UUID serviceId);

    void deleteByUserIdAndServiceId(UUID userId, UUID serviceId);

    @Query("SELECT ss.service.id FROM StaffService ss WHERE ss.user.id = :userId")
    List<UUID> findServiceIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT ss.user.id FROM StaffService ss WHERE ss.service.id = :serviceId")
    List<UUID> findUserIdsByServiceId(@Param("serviceId") UUID serviceId);
}
