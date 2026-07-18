package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.EnquiryRequest;
import com.dev.bookingapp.javabookingapp.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Forwards website enquiries to the site owner's inbox via Resend.
 * Without an API key the endpoint reports 503 rather than silently
 * dropping the enquiry.
 */
@Service
@Slf4j
public class EnquiryService {

    private final ResendEmailSender emailSender;
    private final String toAddress;

    public EnquiryService(
            ResendEmailSender emailSender,
            @Value("${app.enquiry.to}") String toAddress) {
        this.emailSender = emailSender;
        this.toAddress = toAddress;
    }

    public void sendEnquiry(EnquiryRequest request) {
        if (!emailSender.isConfigured()) {
            log.error("Enquiry received but RESEND_API_KEY is not configured; enquiry from {} not sent", request.getEmail());
            throw new ServiceUnavailableException(
                    "Enquiries are temporarily unavailable. Please email us directly instead.");
        }

        String businessLine = request.getBusinessName() == null || request.getBusinessName().isBlank()
                ? ""
                : "Business: " + request.getBusinessName() + "\n";
        String text = "New enquiry from the booking site\n\n"
                + "Name: " + request.getName() + "\n"
                + "Email: " + request.getEmail() + "\n"
                + businessLine
                + "\n" + request.getMessage() + "\n";

        try {
            emailSender.send(
                    "Booking Site",
                    toAddress,
                    request.getEmail(),
                    "New enquiry from " + request.getName(),
                    text);
            log.info("Enquiry from {} forwarded to {}", request.getEmail(), toAddress);
        } catch (Exception ex) {
            log.error("Failed to send enquiry email via Resend", ex);
            throw new ServiceUnavailableException(
                    "We couldn't send your enquiry right now. Please try again shortly or email us directly.");
        }
    }
}
