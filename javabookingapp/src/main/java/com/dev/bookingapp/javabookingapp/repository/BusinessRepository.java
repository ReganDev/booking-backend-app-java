package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessRepository extends JpaRepository<Business, UUID> {

    List<Business> findAllByIsActiveTrueOrderByNameAsc();

    Optional<Business> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Business> findByEmail(String email);
}
