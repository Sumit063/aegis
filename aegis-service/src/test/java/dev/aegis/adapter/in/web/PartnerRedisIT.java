package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.FakeApplication;
import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/** The partner API with its rates in Redis (D36): the whole service wired to it, failing closed (T30). */
@TestPropertySource(properties = "aegis.partner.limiter=redis")
class PartnerRedisIT extends IntegrationTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6.8-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("aegis.partner.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> recharge(String partner, String secret, String requestId) throws Exception {
        String body = "{\"amount\":100}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", "v1", "POST", "/partner/recharges", ts, requestId, digest);
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/partner/recharges"))
                        .header("X-Partner-Id", partner)
                        .header("X-Request-Id", requestId)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", "v1=" + Scenario.sign(secret, canonical))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ratesAreKeptInRedis() throws Exception {
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            limited += recharge("tiny", TINY_SECRET, unique("t-")).statusCode() == 429 ? 1 : 0;
        }

        assertThat(limited).as("burst 3 of the operation, held in Redis").isGreaterThanOrEqualTo(2);
        assertThat(REDIS.execInContainer("redis-cli", "--scan", "--pattern", "aegis:local:rate:partner:tiny*")
                        .getStdout())
                .contains("aegis:local:rate:partner:tiny:recharges");
    }

    @Test
    @DisplayName(
            "T30 with Redis unreachable partner requests are refused, none reaches the application, and it recovers")
    void anUnreachableRedisRefusesPartnerRequests() throws Exception {
        String id = unique("r-");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        HttpResponse<String> refused;
        try {
            refused = recharge("acme", ACME_SECRET, id);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.body()).contains("LIMITS_UNAVAILABLE");
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(FakeApplication.executions(id)).isZero();
        assertThat(recharge("acme", ACME_SECRET, id).statusCode())
                .as("recovered")
                .isEqualTo(201);
        assertThat(FakeApplication.executions(id)).isOne();
    }
}
