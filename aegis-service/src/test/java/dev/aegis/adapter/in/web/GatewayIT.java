package dev.aegis.adapter.in.web;

import static dev.aegis.Scenario.unique;
import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.FakeProvider;
import dev.aegis.FakeProvider.Answer;
import dev.aegis.IntegrationTest;
import dev.aegis.TestClock;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** The Gateway API (LLD §4) against a scripted provider: LLD §9 step 1, §10, T10, T11, T16. */
class GatewayIT extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ValidationRepository validations;

    @Autowired
    PaymentRepository payments;

    @Autowired
    TestClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private HttpResponse<String> call(String path, String body, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** LLD §9 step 1: the order goes to Razorpay with Aegis's credentials and is stored as the validation. */
    @Test
    void createsARazorpayOrderAndRecordsIt() throws Exception {
        String receipt = unique("SHOP-");
        String orderId = unique("order_");
        FakeProvider.on(
                "POST /v1/orders",
                r -> new Answer(
                        200,
                        "{\"id\":\"" + orderId + "\",\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"" + receipt
                                + "\"}"));

        HttpResponse<String> response = call(
                "/gw/razorpay/validations",
                "{\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"" + receipt + "\"}",
                SHOP_API_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(orderId);
        assertThat(response.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        String expectedAuth = "Basic "
                + Base64.getEncoder()
                        .encodeToString(("rzp_test_it:" + RAZORPAY_KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(FakeProvider.received("/v1/orders").getLast().headers().get("Authorization"))
                .containsExactly(expectedAuth);
        Validation stored = jdbcValidation(orderId);
        assertThat(stored.status()).isEqualTo(Validation.Status.CREATED);
        assertThat(stored.merchantRef().value()).isEqualTo(receipt);
        assertThat(stored.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(jdbc.queryForObject(
                        "select data->'request'->>'body' from validations where provider_ref = ?",
                        String.class,
                        orderId))
                .startsWith("enc:v1:");
        assertThat(jdbc.queryForObject(
                        "select data::text from validations where provider_ref = ?", String.class, orderId))
                .doesNotContain(SHOP_API_KEY);
    }

    @Test
    void aRefusedValidationIsStoredAsFailed() throws Exception {
        String receipt = unique("SHOP-");
        FakeProvider.on("POST /v1/orders", r -> new Answer(400, "{\"error\":{\"description\":\"amount too low\"}}"));

        HttpResponse<String> response =
                call("/gw/razorpay/validations", "{\"amount\":100,\"receipt\":\"" + receipt + "\"}", SHOP_API_KEY);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("amount too low");
        assertThat(jdbc.queryForObject("select status from validations where merchant_ref = ?", String.class, receipt))
                .isEqualTo("FAILED");
    }

    /** T16: no key, another application's key, or a missing client certificate never gets through. */
    @Test
    void onlyTheOwningApplicationMayCall() throws Exception {
        String body = "{\"amount\":49900,\"receipt\":\"SHOP-x\"}";

        assertThat(call("/gw/razorpay/validations", body, null).statusCode()).isEqualTo(401);
        assertThat(call("/gw/razorpay/validations", body, "made-up-key").statusCode())
                .isEqualTo(401);
        assertThat(call("/gw/razorpay/validations", body, OTHER_API_KEY).statusCode())
                .isEqualTo(403);
        assertThat(call("/gw/nope/validations", body, SHOP_API_KEY).statusCode())
                .isEqualTo(404);
        assertThat(call("/gw/razorpay/payments", body, SHOP_API_KEY).statusCode())
                .isEqualTo(400);
    }

    /** LLD §10 (bill payment, server-to-server): T10 double payment, T11 status-check limits, result. */
    @Test
    void billPaymentWithStatusChecks() throws Exception {
        String bill = unique("R");
        String first = unique("T1");
        FakeProvider.on(
                "POST /validations",
                r -> new Answer(200, "{\"order\":\"" + bill + "\",\"amount\":84000,\"accepted\":true}"));
        FakeProvider.on("POST /payments", r -> new Answer(200, "{\"status\":\"pending\"}"));

        // 1. Bill fetch fixes the amount.
        assertThat(call("/gw/example/validations", "{\"ref\":\"BILL-" + bill + "\",\"amount\":84000}", SHOP_API_KEY)
                        .statusCode())
                .isEqualTo(200);
        // 2. Payment T1 goes out and is in flight.
        HttpResponse<String> paid = call(
                "/gw/example/payments",
                "{\"order\":\"" + bill + "\",\"payment\":\"" + first + "\",\"amount\":84000}",
                SHOP_API_KEY);
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(paid.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        int sent = FakeProvider.received("/payments").size();

        // 3. A retry with T2 while T1 is in flight: refused, the provider never sees it.
        HttpResponse<String> retry = call(
                "/gw/example/payments",
                "{\"order\":\"" + bill + "\",\"payment\":\"" + unique("T2") + "\",\"amount\":84000}",
                SHOP_API_KEY);
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.headers().firstValue("X-Aegis-Verdict")).contains("BLOCKED DUPLICATE_PAYMENT");
        assertThat(FakeProvider.received("/payments")).hasSize(sent);

        // 5. Status check after 30 s: too early for the provider's rules, provider not called.
        FakeProvider.on(
                "GET /payments/" + first,
                r -> new Answer(
                        200,
                        "{\"order\":\"" + bill + "\",\"payment\":\"" + first
                                + "\",\"status\":\"success\",\"amount\":84000}"));
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> early = call("/gw/example/status", "{\"payment\":\"" + first + "\"}", SHOP_API_KEY);
        assertThat(early.statusCode()).isEqualTo(429);
        assertThat(early.headers().firstValue("X-Aegis-Verdict")).contains("BLOCKED RATE_LIMIT");
        assertThat(FakeProvider.received("/payments/" + first)).isEmpty();

        // 6. After 2 minutes: allowed; SUCCESS passes and the bill is paid.
        clock.advance(Duration.ofSeconds(90));
        HttpResponse<String> later = call("/gw/example/status", "{\"payment\":\"" + first + "\"}", SHOP_API_KEY);
        assertThat(later.statusCode()).isEqualTo(200);
        assertThat(later.headers().firstValue("X-Aegis-Verdict")).contains("PASSED");
        assertThat(jdbcValidation(bill).status()).isEqualTo(Validation.Status.PAID);
        Payment payment = payments.find(
                        jdbc.queryForObject("select id from payments where payment_ref = ?", Long.class, first))
                .orElseThrow();
        assertThat(payment.entries())
                .extracting(Payment.Entry::type)
                .containsExactly(MessageType.PAY_REQUEST, MessageType.STATUS_CHECK);
        assertThat(jdbc.queryForObject(
                        "select messages->0->>'reply' from payments where payment_ref = ?", String.class, first))
                .startsWith("enc:v1:");
    }

    @Test
    void anUnreachableProviderIsReportedAndTheRequestStaysInFlight() throws Exception {
        String bill = unique("R");
        FakeProvider.on(
                "POST /validations",
                r -> new Answer(200, "{\"order\":\"" + bill + "\",\"amount\":500,\"accepted\":true}"));
        call("/gw/example/validations", "{\"ref\":\"BILL-" + bill + "\",\"amount\":500}", SHOP_API_KEY);
        FakeProvider.on("POST /payments", r -> {
            throw new IllegalStateException("provider down");
        });

        HttpResponse<String> response = call(
                "/gw/example/payments",
                "{\"order\":\"" + bill + "\",\"payment\":\"" + unique("T") + "\",\"amount\":500}",
                SHOP_API_KEY);

        assertThat(response.statusCode()).isIn(500, 503);
        HttpResponse<String> again = call(
                "/gw/example/payments",
                "{\"order\":\"" + bill + "\",\"payment\":\"" + unique("T") + "\",\"amount\":500}",
                SHOP_API_KEY);
        assertThat(again.statusCode()).isEqualTo(409);
    }

    private Validation jdbcValidation(String providerRef) {
        Long id = jdbc.queryForObject("select id from validations where provider_ref = ?", Long.class, providerRef);
        return validations.find(id).orElseThrow();
    }
}
