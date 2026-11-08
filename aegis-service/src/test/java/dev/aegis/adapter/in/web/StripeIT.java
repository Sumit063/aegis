package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.FakeApplication;
import dev.aegis.FakeProvider;
import dev.aegis.FakeProvider.Answer;
import dev.aegis.IntegrationTest;
import dev.aegis.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stripe end to end (F17): PaymentIntent through the Gateway API, signed webhooks through Ingress. */
class StripeIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String contentType, String body, String header, String value)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", contentType)
                .header(header, value)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String intent(String id, String status, long amount, long received) {
        return """
                {"id":"%s","object":"payment_intent","amount":%d,"amount_received":%d,"currency":"usd",\
                "status":"%s","metadata":{"order_id":"SHOP-%s"}}""".formatted(id, amount, received, status, id);
    }

    private static String event(String type, String object) {
        return """
                {"id":"%s","object":"event","created":%d,"type":"%s","data":{"object":%s}}""".formatted(unique("evt_"), Instant.now().getEpochSecond(), type, object);
    }

    /** Creates the PaymentIntent through Aegis: the validation that fixes the amount. */
    private String paymentIntent(long amount) throws Exception {
        String id = unique("pi_");
        FakeProvider.on(
                "POST /v1/payment_intents", r -> new Answer(200, intent(id, "requires_payment_method", amount, 0)));
        HttpResponse<String> created = post(
                "/gw/stripe/validations",
                "application/x-www-form-urlencoded",
                "amount=" + amount + "&currency=usd&metadata%5Border_id%5D=SHOP-" + id,
                "Authorization",
                "Bearer " + SHOP_API_KEY);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        return id;
    }

    private HttpResponse<String> webhook(String body) throws Exception {
        long t = Instant.now().getEpochSecond();
        return post(
                "/in/stripe",
                "application/json",
                body,
                "Stripe-Signature",
                "t=" + t + ",v1=" + Scenario.sign(STRIPE_WEBHOOK_SECRET, t + "." + body));
    }

    private List<JsonNode> delivered(String intent) {
        return FakeApplication.received().stream()
                .map(r -> json.readTree(r.body()))
                .filter(m -> intent.equals(m.path("paymentRef").asString(null)))
                .toList();
    }

    private List<String> verdicts(String intent) {
        return jdbc.queryForList(
                "select coalesce(e->>'verdict', '') || coalesce(' ' || (e->>'reason'), '')"
                        + " from payments p, jsonb_array_elements(p.messages) e where p.payment_ref = ? order by e->>'n'",
                String.class,
                intent);
    }

    @Test
    void aPaymentIntentIsCreatedWithTheSecretKeyAndRecorded() throws Exception {
        String id = paymentIntent(2_000);

        assertThat(FakeProvider.received("/v1/payment_intents")
                        .getLast()
                        .headers()
                        .get("Authorization"))
                .containsExactly("Bearer " + STRIPE_SECRET_KEY);
        assertThat(jdbc.queryForMap(
                        "select merchant_ref, amount, currency, status from validations where provider_ref = ?", id))
                .containsEntry("merchant_ref", "SHOP-" + id)
                .containsEntry("amount", 2_000L)
                .containsEntry("currency", "USD")
                .containsEntry("status", "CREATED");
    }

    /** T1 and T4 for Stripe: a genuine success reaches the application once, however often it is sent. */
    @Test
    void aSucceededIntentIsForwardedOnce() throws Exception {
        String id = paymentIntent(2_000);
        String body = event("payment_intent.succeeded", intent(id, "succeeded", 2_000, 2_000));

        assertThat(webhook(body).statusCode()).isEqualTo(200);
        assertThat(webhook(body).statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(id).size() == 1);
        JsonNode message = delivered(id).getFirst();
        assertThat(message.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(message.get("merchantRef").asString()).isEqualTo("SHOP-" + id);
        assertThat(message.get("amount").asLong()).isEqualTo(2_000);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "select status from validations where provider_ref = ?", String.class, id)
                        .equals("PAID"));
    }

    /** Capturing less than the intent's amount is not a success at the order's price. */
    @Test
    void aPartialCaptureIsAnAmountMismatch() throws Exception {
        String id = paymentIntent(2_000);

        webhook(event("payment_intent.succeeded", intent(id, "succeeded", 2_000, 500)));

        await().atMost(Duration.ofSeconds(15)).until(() -> verdicts(id).contains("BLOCKED AMOUNT_MISMATCH"));
        assertThat(delivered(id)).isEmpty();
    }

    /** refund.created and refund.updated both report the succeeded refund: the application hears it once. */
    @Test
    void aRefundReportedTwiceIsForwardedOnceAndTheStatusStillConfirms() throws Exception {
        String id = paymentIntent(2_000);
        webhook(event("payment_intent.succeeded", intent(id, "succeeded", 2_000, 2_000)));
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(id).size() == 1);
        String refund = """
                {"id":"%s","object":"refund","amount":700,"currency":"usd","payment_intent":"%s","status":"succeeded"}""".formatted(unique("re_"), id);

        webhook(event("refund.created", refund));
        webhook(event("refund.updated", refund));

        await().atMost(Duration.ofSeconds(15)).until(() -> verdicts(id).contains("BLOCKED DUPLICATE"));
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered(id).size() == 2);
        assertThat(delivered(id).get(1).get("outcome").asString()).isEqualTo("REFUNDED");

        FakeProvider.on("GET /v1/payment_intents/" + id, r -> new Answer(200, intent(id, "succeeded", 2_000, 2_000)));
        HttpResponse<String> status = post(
                "/gw/stripe/status",
                "application/x-www-form-urlencoded",
                "payment_intent=" + id,
                "Authorization",
                "Bearer " + SHOP_API_KEY);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
    }

    /** A forgery, a stale signature and an unsigned browser redirect never pass. */
    @Test
    void forgedStaleAndBrowserMessagesAreBlocked() throws Exception {
        String id = paymentIntent(2_000);
        String body = event("payment_intent.succeeded", intent(id, "succeeded", 2_000, 2_000));
        long stale = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        assertThat(post("/in/stripe", "application/json", body, "Stripe-Signature", "t=1,v1=00")
                        .statusCode())
                .isEqualTo(401);
        post(
                "/in/stripe",
                "application/json",
                body,
                "Stripe-Signature",
                "t=" + stale + ",v1=" + Scenario.sign(STRIPE_WEBHOOK_SECRET, stale + "." + body));
        assertThat(post(
                                "/in/stripe/callback",
                                "application/x-www-form-urlencoded",
                                "payment_intent=" + id + "&redirect_status=succeeded",
                                "X-Ignored",
                                "1")
                        .statusCode())
                .isIn(303, 401);

        await().atMost(Duration.ofSeconds(15)).until(() -> verdicts(id).contains("BLOCKED REPLAY"));
        assertThat(delivered(id)).isEmpty();
        assertThat(jdbc.queryForObject("select status from validations where provider_ref = ?", String.class, id))
                .isEqualTo("CREATED");
    }
}
