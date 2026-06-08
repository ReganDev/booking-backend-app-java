package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByBusinessIdAndEmail(UUID businessId, String email);

    List<User> findByBusinessIdAndIsActiveTrue(UUID businessId);

    List<User> findByBusinessIdAndRole(UUID businessId, UserRole role);

    List<User> findByBusinessIdAndAcceptsBookingsTrue(UUID businessId);

    boolean existsByBusinessIdAndEmail(UUID businessId, String email);

    @Query("SELECT u FROM User u WHERE u.business.id = :businessId AND u.isActive = true AND u.acceptsBookings = true")
    List<User> findActiveStaffByBusinessId(@Param("businessId") UUID businessId);
}
