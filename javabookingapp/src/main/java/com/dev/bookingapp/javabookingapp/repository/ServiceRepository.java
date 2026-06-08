package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.Service;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceRepository extends JpaRepository<Service, UUID> {

    List<Service> findByBusinessIdOrderByDisplayOrderAsc(UUID businessId);

    List<Service> findByBusinessIdAndIsActiveTrueOrderByDisplayOrderAsc(UUID businessId);

    boolean existsByBusinessIdAndName(UUID businessId, String name);
}
