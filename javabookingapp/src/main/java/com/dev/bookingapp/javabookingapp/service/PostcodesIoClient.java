package com.dev.bookingapp.javabookingapp.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * UK postcode → coordinates via the free, keyless postcodes.io API.
 * Every failure (unknown postcode, timeout, outage) is an empty Optional:
 * distance is best-effort data and must never break a booking.
 */
@Component
@Slf4j
public class PostcodesIoClient {

    public record Coordinates(double latitude, double longitude) {}

    private final RestClient restClient;

    public PostcodesIoClient(
            @Value("${app.geo.postcodes-base-url:https://api.postcodes.io}") String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "BookingBase/1.0 (bookingbase.co.uk)")
                // See OsrmClient: a gzipped response breaks this client stack.
                // postcodes.io happens to send these small bodies uncompressed
                // today, but it will gzip if asked, so don't ask.
                .defaultHeader("Accept-Encoding", "identity")
                .build();
    }

    public Optional<Coordinates> lookup(String postcode) {
        if (postcode == null || postcode.isBlank()) {
            return Optional.empty();
        }
        try {
            PostcodeResponse response = restClient.get()
                    .uri("/postcodes/{postcode}", postcode)
                    .retrieve()
                    .body(PostcodeResponse.class);
            if (response == null || response.result() == null
                    || response.result().latitude() == null
                    || response.result().longitude() == null) {
                return Optional.empty();
            }
            return Optional.of(new Coordinates(
                    response.result().latitude(), response.result().longitude()));
        } catch (RuntimeException ex) {
            log.warn("Postcode lookup failed for '{}': {}", postcode, ex.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostcodeResponse(Result result) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(Double latitude, Double longitude) {}
    }
}
