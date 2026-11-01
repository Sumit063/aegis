package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.event;
import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.in.RetentionUseCase;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.provider.spi.Channel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Admin API and operations: lookups, blocked list, re-forward, pause, retention. */
class AdminIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    CheckMessageUseCase checker;

    @Autowired
    ValidationRepository validations;

    @Autowired
    EvidenceCipherPort cipher;

    @Autowired
    RetentionUseCase retention;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> admin(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin" + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = admin("GET", path, null, ADMIN_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private CheckMessageUseCase.Result captured(String orderId, String paymentId) {
        String body = event("payment.captured", orderId, paymentId, 49_900, Instant.now());
        IngressRecord record = Scenario.record(
                Channel.SERVER,
                true,
                Map.of("X-Razorpay-Event-Id", unique("evt_")),
                cipher.seal(body.getBytes(StandardCharsets.UTF_8)),
                Instant.now());
        return checker.check(record);
    }

    private long deliveries(String paymentId) {
        return FakeApplication.received().stream()
                .filter(r -> r.body().contains(paymentId))
                .count();
    }

    @Test
    void requiresTheAdminKey() throws Exception {
        assertThat(admin("GET", "/settings", null, null).statusCode()).isEqualTo(401);
        assertThat(admin("GET", "/settings", null, SHOP_API_KEY).statusCode()).isEqualTo(401);
    }

    @Test
    void findsEverythingByAnyReferenceAndListsBlocks() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        long id = captured(orderId, paymentId).paymentId();
        CheckMessageUseCase.Result forged = checker.check(Scenario.record(
                Channel.SERVER, false, Map.of(), cipher.seal("{}".getBytes(StandardCharsets.UTF_8)), Instant.now()));

        assertThat(get("/payments?ref=" + paymentId).get(0).get("id").asLong()).isEqualTo(id);
        assertThat(get("/payments?ref=SHOP-" + orderId).get(0).get("id").asLong())
                .isEqualTo(id);
        JsonNode payment = get("/payments/" + id);
        assertThat(payment.at("/messages/0/verdict").asString()).isEqualTo("PASSED");
        assertThat(payment.toString()).doesNotContain("enc:v1");
        assertThat(get("/validations?ref=" + orderId).get(0).get("status").asString())
                .isEqualTo("PAID");
        assertThat(get("/blocked").findValuesAsString("verdict")).contains("BLOCKED BAD_SIGNATURE");
        assertThat(get("/blocked").findValues("id").stream().map(JsonNode::asLong))
                .contains(forged.paymentId());
        assertThat(admin("GET", "/payments/999999999", null, ADMIN_KEY).statusCode())
                .isEqualTo(404);
    }

    @Test
    void reforwardsADeliveredMessage() throws Exception {
        String orderId = unique("order_");
        String paymentId = unique("pay_");
        Scenario.order(validations, tx, orderId, 49_900);
        long id = captured(orderId, paymentId).paymentId();
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveries(paymentId) == 1);

        assertThat(admin("POST", "/payments/" + id + "/messages/1/forward", null, ADMIN_KEY)
                        .statusCode())
                .isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveries(paymentId) == 2);
        assertThat(admin("POST", "/payments/" + id + "/messages/9/forward", null, ADMIN_KEY)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void pausingAnApplicationHoldsItsDeliveriesWithoutLosingThem() throws Exception {
        try {
            assertThat(json.readTree(admin("PUT", "/settings", "{\"pausedApplications\":[\"shop\"]}", ADMIN_KEY)
                                    .body())
                            .get("pausedApplications")
                            .get(0)
                            .asString())
                    .isEqualTo("shop");
            String orderId = unique("order_");
            String paymentId = unique("pay_");
            Scenario.order(validations, tx, orderId, 49_900);
            captured(orderId, paymentId);
            Thread.sleep(2000);
            assertThat(deliveries(paymentId)).isZero();

            admin("PUT", "/settings", "{\"pausedApplications\":[]}", ADMIN_KEY);
            await().atMost(Duration.ofSeconds(15)).until(() -> deliveries(paymentId) == 1);
            assertThat(jdbc.queryForObject(
                            "select version from config where key = 'forwarding.paused-applications'", Integer.class))
                    .isGreaterThanOrEqualTo(2);
        } finally {
            admin("PUT", "/settings", "{\"pausedApplications\":[]}", ADMIN_KEY);
        }
    }

    @Test
    void retentionDeletesOnlyOldRecords() {
        String recent = unique("pay_");
        String old = unique("pay_");
        jdbc.update(
                "insert into payments (provider, payment_ref, messages, created_at, updated_at) values ('razorpay', ?, '[]', now(), now())",
                recent);
        jdbc.update(
                "insert into payments (provider, payment_ref, messages, created_at, updated_at) values ('razorpay', ?, '[]', now() - interval '200 days', now())",
                old);

        assertThat(retention.purge()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from payments where payment_ref = ?", Integer.class, old))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where payment_ref = ?", Integer.class, recent))
                .isOne();
    }
}
