package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends booking-details emails to customers who opted in. Best-effort: a
 * failed email is logged and never fails the booking itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingNotificationService {

    private static final DateTimeFormatter WHEN_FORMAT =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm", Locale.UK);

    private final ResendEmailSender emailSender;

    public void sendBookingDetails(Business business, BookingResponse booking, String customerEmail) {
        if (!emailSender.isConfigured()) {
            log.warn("Booking email requested but RESEND_API_KEY is not configured; skipping");
            return;
        }

        ZoneId zone = AvailabilityService.resolveZone(business.getTimezone());
        String when = booking.getStartDatetime().atZoneSameInstant(zone).format(WHEN_FORMAT);

        String priceLine = booking.getPrice() != null
                ? "Price: " + business.getCurrency() + " " + booking.getPrice() + "\n"
                : "";
        boolean confirmed = booking.getStatus() == BookingStatus.CONFIRMED;
        String intro = confirmed
                ? "Here are the details of your booking with " + business.getName() + ":\n\n"
                : "Here are the details of your booking request with " + business.getName() + ":\n\n";
        String outro = confirmed
                ? "\nYou're all set — your booking is confirmed. " + business.getName()
                        + " will be in touch if anything changes.\n\n"
                : "\nYour booking is awaiting confirmation from " + business.getName()
                        + ". They will be in touch if anything changes.\n\n";
        String text = "Hi " + booking.getCustomer().getFirstName() + ",\n\n"
                + intro
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n"
                + "Duration: " + booking.getService().getDurationMinutes() + " minutes\n"
                + priceLine
                + outro
                + "See you soon!\n";

        try {
            emailSender.send(
                    business.getName(),
                    customerEmail,
                    business.getEmail(),
                    "Your booking with " + business.getName(),
                    text);
            log.info("Booking details email sent to {} for booking {}", customerEmail, booking.getId());
        } catch (Exception ex) {
            log.error("Failed to send booking details email for booking {}", booking.getId(), ex);
        }
    }

    /** Tells the business a customer cancelled online. Best-effort. */
    public void sendBusinessCancelledNotice(Business business, BookingResponse booking) {
        if (!emailSender.isConfigured()) {
            log.warn("Cancel notice requested but RESEND_API_KEY is not configured; skipping");
            return;
        }
        ZoneId zone = AvailabilityService.resolveZone(business.getTimezone());
        String when = booking.getStartDatetime().atZoneSameInstant(zone).format(WHEN_FORMAT);
        String text = "Hi,\n\n"
                + booking.getCustomer().getFirstName() + " " + booking.getCustomer().getLastName()
                + " has cancelled their booking:\n\n"
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n\n"
                + "The slot is now free for other customers.\n";
        try {
            emailSender.send("BookingBase", business.getEmail(), null,
                    "Booking cancelled: " + booking.getService().getName(), text);
            log.info("Cancel notice sent to business {} for booking {}", business.getId(), booking.getId());
        } catch (Exception ex) {
            log.error("Failed to send cancel notice for booking {}", booking.getId(), ex);
        }
    }

    /** Tells the business a customer rescheduled online. Best-effort. */
    public void sendBusinessRescheduledNotice(Business business, BookingResponse booking,
                                              OffsetDateTime oldStart) {
        if (!emailSender.isConfigured()) {
            log.warn("Reschedule notice requested but RESEND_API_KEY is not configured; skipping");
            return;
        }
        ZoneId zone = AvailabilityService.resolveZone(business.getTimezone());
        String from = oldStart.atZoneSameInstant(zone).format(WHEN_FORMAT);
        String to = booking.getStartDatetime().atZoneSameInstant(zone).format(WHEN_FORMAT);
        String text = "Hi,\n\n"
                + booking.getCustomer().getFirstName() + " " + booking.getCustomer().getLastName()
                + " has moved their booking:\n\n"
                + "Service: " + booking.getService().getName() + "\n"
                + "From: " + from + "\n"
                + "To: " + to + "\n";
        try {
            emailSender.send("BookingBase", business.getEmail(), null,
                    "Booking rescheduled: " + booking.getService().getName(), text);
            log.info("Reschedule notice sent to business {} for booking {}", business.getId(), booking.getId());
        } catch (Exception ex) {
            log.error("Failed to send reschedule notice for booking {}", booking.getId(), ex);
        }
    }
}
