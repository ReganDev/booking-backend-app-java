package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Page<Customer> findByBusinessId(UUID businessId, Pageable pageable);

    Optional<Customer> findByBusinessIdAndEmail(UUID businessId, String email);

    Optional<Customer> findByBusinessIdAndId(UUID businessId, UUID customerId);

    boolean existsByBusinessIdAndEmail(UUID businessId, String email);

    @Query("SELECT c FROM Customer c WHERE c.business.id = :businessId AND " +
           "(LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Customer> searchByBusinessId(@Param("businessId") UUID businessId,
                                       @Param("search") String search,
                                       Pageable pageable);
}
