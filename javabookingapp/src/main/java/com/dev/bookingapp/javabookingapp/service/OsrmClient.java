package com.dev.bookingapp.javabookingapp.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Driving route between two coordinates via the public OSRM demo server
 * (keyless, no SLA — hence the short timeouts and the configurable base URL
 * as a swap point). Failures are an empty Optional, never an exception.
 */
@Component
@Slf4j
public class OsrmClient {

    public record Route(int distanceMeters, int durationSeconds) {}

    private final RestClient restClient;

    public OsrmClient(
            @Value("${app.geo.osrm-base-url:https://router.project-osrm.org}") String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "BookingBase/1.0 (bookingbase.co.uk)")
                .build();
    }

    public Optional<Route> route(PostcodesIoClient.Coordinates from,
                                 PostcodesIoClient.Coordinates to) {
        try {
            // OSRM wants lon,lat — not lat,lon. Concatenated into the URI
            // rather than passed as a template variable so the ';' and ','
            // separators stay literal instead of being percent-encoded.
            String coordinates = String.format(Locale.ROOT, "%f,%f;%f,%f",
                    from.longitude(), from.latitude(), to.longitude(), to.latitude());
            RouteResponse response = restClient.get()
                    .uri("/route/v1/driving/" + coordinates + "?overview=false")
                    .retrieve()
                    .body(RouteResponse.class);
            if (response == null || !"Ok".equals(response.code())
                    || response.routes() == null || response.routes().isEmpty()) {
                return Optional.empty();
            }
            RouteResponse.OsrmRoute route = response.routes().get(0);
            if (route.distance() == null || route.duration() == null) {
                return Optional.empty();
            }
            return Optional.of(new Route(
                    (int) Math.round(route.distance()),
                    (int) Math.round(route.duration())));
        } catch (RuntimeException ex) {
            log.warn("OSRM routing failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteResponse(String code, List<OsrmRoute> routes) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record OsrmRoute(Double distance, Double duration) {}
    }
}
