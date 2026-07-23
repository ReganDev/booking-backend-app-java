package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BookingManageToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingManageTokenRepository extends JpaRepository<BookingManageToken, UUID> {

    Optional<BookingManageToken> findByTokenHash(String tokenHash);

    Optional<BookingManageToken> findByBookingId(UUID bookingId);
}
