package com.dev.bookingapp.javabookingapp.service;

import java.util.UUID;

/** Published after a booking row is saved; drives post-commit side effects. */
public record BookingCreatedEvent(UUID bookingId) {
}
