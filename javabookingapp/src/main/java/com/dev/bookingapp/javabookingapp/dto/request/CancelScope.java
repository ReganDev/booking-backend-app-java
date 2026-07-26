package com.dev.bookingapp.javabookingapp.dto.request;

/**
 * How far a status change reaches when the booking is part of a series.
 * Defaults to {@link #THIS_ONLY} so existing callers keep their behaviour.
 */
public enum CancelScope {
    THIS_ONLY,
    THIS_AND_FUTURE
}
