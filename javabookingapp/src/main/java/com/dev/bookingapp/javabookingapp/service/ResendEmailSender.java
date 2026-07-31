package com.dev.bookingapp.javabookingapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/** Thin wrapper around the Resend HTTP API shared by all outgoing email. */
@Component
@Slf4j
public class ResendEmailSender {

    private final RestClient restClient;
    private final String apiKey;
    private final String fromAddress;

    public ResendEmailSender(
            @Value("${app.resend.api-key:}") String apiKey,
            @Value("${app.enquiry.from:onboarding@resend.dev}") String fromAddress,
            // Configurable so the delivery path can be pointed at a local
            // capture server in tests, the way the geo clients already are.
            @Value("${app.resend.base-url:https://api.resend.com}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Sends an email; throws on failure. Callers decide whether failure is
     *  fatal (enquiries) or best-effort (booking confirmations). */
    public void send(String fromName, String to, String replyTo, String subject, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("from", fromName + " <" + fromAddress + ">");
        body.put("to", new String[]{to});
        body.put("subject", subject);
        body.put("text", text);
        if (replyTo != null && !replyTo.isBlank()) {
            body.put("reply_to", replyTo);
        }

        restClient.post()
                .uri("/emails")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
