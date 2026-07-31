package com.dev.bookingapp.javabookingapp.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PostcodesIoClientTest {

    private HttpServer server;
    private PostcodesIoClient client;
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private volatile String responseJson;
    private volatile int responseStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new PostcodesIoClient("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesCoordinatesFromResult() {
        responseJson = """
                {"status":200,"result":{"postcode":"SW1A 1AA","latitude":51.501009,
                 "longitude":-0.141588,"region":"London"}}""";

        Optional<PostcodesIoClient.Coordinates> coordinates = client.lookup("SW1A 1AA");

        assertThat(coordinates)
                .contains(new PostcodesIoClient.Coordinates(51.501009, -0.141588));
        assertThat(requestedUri.get()).isEqualTo("/postcodes/SW1A%201AA");
    }

    @Test
    void unknownPostcodeIsEmptyNotAnException() {
        responseStatus = 404;
        responseJson = "{\"status\":404,\"error\":\"Postcode not found\"}";

        assertThat(client.lookup("ZZ99 9ZZ")).isEmpty();
    }

    @Test
    void blankPostcodeShortCircuits() {
        responseJson = "{}";

        assertThat(client.lookup("  ")).isEmpty();
        assertThat(requestedUri.get()).isNull();
    }
}
