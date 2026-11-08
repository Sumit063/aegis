package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.FakeProvider;
import dev.aegis.FakeProvider.Answer;
import dev.aegis.IntegrationTest;
import dev.aegis.provider.spi.Secret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cashfree end to end (F18): orders through the Gateway API, timestamped HMAC webhooks through Ingress. */
class CashfreeIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Creates the order through Aegis: the validation that fixes the amount (₹10.50). */
    private String order() throws Exception {
        String orderId = unique("order_");
        FakeProvider.on(
                "POST /pg/orders",
                r -> new Answer(
                        200,
                        "{\"cf_order_id\":\"1\",\"order_id\":\"" + orderId + "\",\"order_amount\":10.50,"
                                + "\"order_currency\":\"INR\",\"order_status\":\"ACTIVE\",\"payment_session_id\":\"s\"}"));
        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/gw/cashfree/validations"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + SHOP_API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"order_id\":\"" + orderId
                                + "\",\"order_amount\":10.50,\"order_currency\":\"INR\","
                                + "\"customer_details\":{\"customer_id\":\"c1\",\"customer_phone\":\"9999999999\"}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        return orderId;
    }

    private static String payment(String type, String status, String orderId, String paymentId, String amount) {
        return """
                {"data":{"order":{"order_id":"%s","order_amount":10.50,"order_currency":"INR"},\
                "payment":{"cf_payment_id":"%s","payment_status":"%s","payment_amount":%s,"payment_currency":"INR"}},\
                "event_time":"2025-01-15T12:20:30+05:30","type":"%s"}""".formatted(orderId, paymentId, status, amount, type);
    }

    private int webhook(String body, String idempotencyKey) throws Exception {
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String signature = Base64.getEncoder()
                .encodeToString(new Secret(CASHFREE_CLIENT_SECRET)
                        .hmacSha256((timestamp + body).getBytes(StandardCharsets.UTF_8)));
        return http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in/cashfree"))
                                .header("Content-Type", "application/json")
                                .header("x-webhook-timestamp", timestamp)
                                .header("x-webhook-signature", signature)
                                .header("x-idempotency-key", idempotencyKey)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private List<JsonNode> delivered(String paymentId) {
        return FakeApplication.received().stream()
                .map(r -> json.readTree(r.body()))
                .filter(m -> paymentId.equals(m.path("paymentRef").asString(null)))
                .toList();
    }

    private static List<String> header(FakeProvider.Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    private String validationStatus(String orderId) {
        return jdbc.queryForObject("select status from validations where provider_ref = ?", String.class, orderId);
    }

    @Test
    void anOrderIsCreatedWithTheClientCredentialsAndRecordedExactly() throws Exception {
        String orderId = order();

        FakeProvider.Request sent = FakeProvider.received("/pg/orders").getLast();
        assertThat(header(sent, "x-client-id")).containsExactly("TEST-cashfree-it");
        assertThat(header(sent, "x-client-secret")).containsExactly(CASHFREE_CLIENT_SECRET);
        assertThat(header(sent, "x-api-version")).containsExactly("2025-01-01");
        assertThat(jdbc.queryForMap(
                        "select merchant_ref, amount, currency from validations where provider_ref = ?", orderId))
                .containsEntry("merchant_ref", orderId)
                .containsEntry("amount", 1_050L)
                .containsEntry("currency", "INR");
    }

    /** T1 and T4 for Cashfree: ₹10.50 paid is 1050 paise, forwarded once however often it is sent. */
    @Test
    void aSuccessIsForwardedOnceWithTheExactAmount() throws Exception {
        String orderId = order();
        String paymentId = unique("");
        String body = payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", orderId, paymentId, "10.50");
        String key = unique("idem-");

        assertThat(webhook(body, key)).isEqualTo(200);
        assertThat(webhook(body, key)).isEqualTo(200);

        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(paymentId).size() == 1);
        assertThat(delivered(paymentId).getFirst().get("amount").asLong()).isEqualTo(1_050);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> validationStatus(orderId).equals("PAID"));
    }

    /** ₹10.49 for a ₹10.50 order is an underpayment, whatever the decimals look like. */
    @Test
    void oneRupeePaisaShortIsAnAmountMismatch() throws Exception {
        String orderId = order();
        String paymentId = unique("");

        webhook(payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", orderId, paymentId, "10.49"), unique("idem-"));

        await().atMost(Duration.ofSeconds(15))
                .until(() -> jdbc.queryForObject(
                                "select count(*) from payments p, jsonb_array_elements(p.messages) e"
                                        + " where p.payment_ref = ? and e->>'reason' = 'AMOUNT_MISMATCH'",
                                Integer.class,
                                paymentId)
                        == 1);
        assertThat(delivered(paymentId)).isEmpty();
        assertThat(validationStatus(orderId)).isEqualTo("CREATED");
    }

    /** A dropped UPI payment the bank confirms later: both reach the application, the order is paid. */
    @Test
    void aLateSuccessAfterADroppedPaymentPasses() throws Exception {
        String orderId = order();
        String paymentId = unique("");

        webhook(payment("PAYMENT_USER_DROPPED_WEBHOOK", "USER_DROPPED", orderId, paymentId, "10.50"), unique("idem-"));
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(paymentId).size() == 1);
        webhook(payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", orderId, paymentId, "10.50"), unique("idem-"));

        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(paymentId).size() == 2);
        assertThat(delivered(paymentId).stream().map(m -> m.get("outcome").asString()))
                .containsExactly("FAILED", "SUCCESS");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> validationStatus(orderId).equals("PAID"));
    }

    @Test
    void aSuccessfulRefundIsForwardedAsAFact() throws Exception {
        String orderId = order();
        String paymentId = unique("");
        webhook(payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", orderId, paymentId, "10.50"), unique("idem-"));
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(paymentId).size() == 1);

        String refund = """
                {"data":{"refund":{"cf_refund_id":%s,"cf_payment_id":%s,"order_id":"%s","refund_amount":2.00,\
                "refund_currency":"INR","refund_status":"SUCCESS"}},"type":"REFUND_STATUS_WEBHOOK"}""".formatted(System.nanoTime(), "\"" + paymentId + "\"", orderId);
        webhook(refund, unique("idem-"));

        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(paymentId).size() == 2);
        assertThat(delivered(paymentId).get(1).get("outcome").asString()).isEqualTo("REFUNDED");
    }
}
