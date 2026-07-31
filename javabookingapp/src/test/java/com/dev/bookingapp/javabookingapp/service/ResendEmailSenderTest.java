package com.dev.bookingapp.javabookingapp.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ResendEmailSenderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"email-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ResendEmailSender sender(String apiKey) {
        return new ResendEmailSender(apiKey, "bookings@bookingbase.co.uk", baseUrl);
    }

    @Test
    void postsTheEmailToTheConfiguredBaseUrl() {
        sender("test-key").send("Fab Hair", "jane@example.com", "salon@example.com",
                "Your booking", "Where: 1 High Street, Manchester, M1 1AE");

        assertThat(requestedPath.get()).isEqualTo("/emails");
        assertThat(authHeader.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .contains("\"from\":\"Fab Hair <bookings@bookingbase.co.uk>\"")
                .contains("jane@example.com")
                .contains("Where: 1 High Street, Manchester, M1 1AE")
                .contains("\"reply_to\":\"salon@example.com\"");
    }

    @Test
    void omitsReplyToWhenNotGiven() {
        sender("test-key").send("BookingBase", "owner@example.com", null,
                "Booking cancelled", "body");

        assertThat(requestBody.get()).doesNotContain("reply_to");
    }

    @Test
    void isNotConfiguredWithoutAnApiKey() {
        assertThat(sender("").isConfigured()).isFalse();
        assertThat(sender("  ").isConfigured()).isFalse();
        assertThat(sender("re_live_key").isConfigured()).isTrue();
    }
}
