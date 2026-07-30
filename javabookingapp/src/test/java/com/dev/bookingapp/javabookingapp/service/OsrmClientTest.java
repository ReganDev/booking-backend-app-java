package com.dev.bookingapp.javabookingapp.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OsrmClientTest {

    private HttpServer server;
    private OsrmClient client;
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private final AtomicReference<String> requestedEncoding = new AtomicReference<>();
    private volatile String responseJson;
    private volatile int responseStatus = 200;

    private static final PostcodesIoClient.Coordinates LONDON =
            new PostcodesIoClient.Coordinates(51.501009, -0.141588);
    private static final PostcodesIoClient.Coordinates MANCHESTER =
            new PostcodesIoClient.Coordinates(53.478151, -2.242188);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            requestedEncoding.set(acceptEncoding);
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            // The real OSRM server compresses whenever the client says it
            // accepts gzip, so mirror that here.
            boolean gzip = acceptEncoding != null && acceptEncoding.contains("gzip");
            if (gzip) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
                    gz.write(body);
                }
                body = buffer.toByteArray();
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new OsrmClient("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void requestsLonLatOrderAndParsesRoundedRoute() {
        responseJson = """
                {"code":"Ok","routes":[{"distance":11586.6,"duration":1079.4,"legs":[]}],
                 "waypoints":[]}""";

        Optional<OsrmClient.Route> route = client.route(LONDON, MANCHESTER);

        assertThat(route).contains(new OsrmClient.Route(11587, 1079));
        // lon,lat;lon,lat — the classic OSRM gotcha, with literal separators
        assertThat(requestedUri.get()).isEqualTo(
                "/route/v1/driving/-0.141588,51.501009;-2.242188,53.478151?overview=false");
    }

    @Test
    void parsesTheRouteFromAServerThatCompressesItsResponses() {
        // The live OSRM server gzips whenever the client accepts gzip, and the
        // response then failed to decode ("incorrect header check"), so every
        // real booking silently came back without a distance.
        responseJson = """
                {"code":"Ok","routes":[{"distance":11586.6,"duration":1079.4,"legs":[]}],
                 "waypoints":[]}""";

        Optional<OsrmClient.Route> route = client.route(LONDON, MANCHESTER);

        assertThat(route).contains(new OsrmClient.Route(11587, 1079));
        assertThat(requestedEncoding.get()).doesNotContain("gzip");
    }

    @Test
    void nonOkCodeIsEmpty() {
        responseJson = "{\"code\":\"NoRoute\",\"routes\":[]}";

        assertThat(client.route(LONDON, MANCHESTER)).isEmpty();
    }

    @Test
    void httpErrorIsEmptyNotAnException() {
        responseStatus = 500;
        responseJson = "{\"message\":\"boom\"}";

        assertThat(client.route(LONDON, MANCHESTER)).isEmpty();
    }
}
