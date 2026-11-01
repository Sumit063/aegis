package dev.aegis.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** H3: a flood on one connection is refused early and does not starve another (review items 22, 8). */
@TestPropertySource(properties = {"aegis.limits.requests-per-minute=60", "aegis.limits.burst=10"})
class LimitsIT extends IntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int post(String connection) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/" + connection))
                                .header("X-Razorpay-Signature", "forged")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    void aFloodIsRefusedBeforeItCostsAnythingAndOtherConnectionsKeepWorking() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            statuses.add(post("razorpay"));
        }

        assertThat(statuses).filteredOn(s -> s == 401).hasSizeLessThanOrEqualTo(11);
        assertThat(statuses).filteredOn(s -> s == 429).hasSizeGreaterThanOrEqualTo(29);
        assertThat(post("example")).as("another connection has its own budget").isEqualTo(401);
    }
}
