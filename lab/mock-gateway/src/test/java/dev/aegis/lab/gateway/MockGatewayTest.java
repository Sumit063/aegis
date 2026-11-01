package dev.aegis.lab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockGatewayTest {

    private static final String KEY = "rzp_test_lab";
    private static final String SECRET = "lab-key-secret-not-a-real-secret-0001";
    private static final String WEBHOOK_SECRET = "lab-webhook-secret-not-a-real-secret";

    record Received(Map<String, String> headers, String body) {}

    static final BlockingQueue<Received> WEBHOOKS = new LinkedBlockingQueue<>();
    static final HttpServer MERCHANT = merchant();

    @DynamicPropertySource
    static void webhookUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "mock.accounts.shop.webhook-url",
                () -> "http://127.0.0.1:" + MERCHANT.getAddress().getPort() + "/webhooks");
    }

    @AfterAll
    static void stop() {
        MERCHANT.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ordersNeedTheApiKey() throws Exception {
        HttpResponse<String> response = post("/v1/orders", "{\"amount\":49900}", "rzp_test_lab:wrong");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).at("/error/description").asString())
                .isEqualTo("Authentication failed");
    }

    @Test
    void paymentIsSignedLikeRazorpayAndTheWebhookIsDelivered() throws Exception {
        JsonNode order = json.readTree(
                post("/v1/orders", "{\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"SHOP-1\"}", KEY + ":" + SECRET)
                        .body());
        String orderId = order.get("id").asString();
        assertThat(order.get("amount_due").asLong()).isEqualTo(49900);

        JsonNode result = checkout("{\"key_id\":\"rzp_test_lab\",\"order_id\":\"" + orderId + "\"}");
        String paymentId = result.get("razorpay_payment_id").asString();

        assertThat(result.get("razorpay_signature").asString())
                .isEqualTo(Hmac.sha256Hex(SECRET, orderId + "|" + paymentId));
        assertThat(result.get("webhook_status").asInt()).isEqualTo(204);
        Received webhook = WEBHOOKS.poll(5, TimeUnit.SECONDS);
        assertThat(webhook).isNotNull();
        assertThat(webhook.headers().get("x-razorpay-signature"))
                .isEqualTo(Hmac.sha256Hex(WEBHOOK_SECRET, webhook.body()));
        assertThat(webhook.headers().get("x-razorpay-event-id"))
                .isEqualTo(result.get("event_id").asString());
        JsonNode event = json.readTree(webhook.body());
        assertThat(event.get("event").asString()).isEqualTo("payment.captured");
        assertThat(event.at("/payload/payment/entity/order_id").asString()).isEqualTo(orderId);
        assertThat(event.at("/payload/payment/entity/amount").asLong()).isEqualTo(49900);

        JsonNode paid = json.readTree(get("/v1/orders/" + orderId).body());
        assertThat(paid.get("status").asString()).isEqualTo("paid");
        assertThat(json.readTree(get("/v1/orders/" + orderId + "/payments").body())
                        .get("count")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void anOrderIsPaidOnlyOnceAndOnlyForItsAmount() throws Exception {
        String orderId = json.readTree(post("/v1/orders", "{\"amount\":100000}", KEY + ":" + SECRET)
                        .body())
                .get("id")
                .asString();

        assertThat(post(
                                "/test/checkout",
                                "{\"key_id\":\"rzp_test_lab\",\"order_id\":\"" + orderId + "\",\"amount\":1000}",
                                null)
                        .statusCode())
                .isEqualTo(400);
        checkout("{\"key_id\":\"rzp_test_lab\",\"order_id\":\"" + orderId + "\",\"webhook\":false}");
        assertThat(post("/test/checkout", "{\"key_id\":\"rzp_test_lab\",\"order_id\":\"" + orderId + "\"}", null)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void aFailedPaymentIsNotSignedAndSendsPaymentFailed() throws Exception {
        String orderId = json.readTree(post("/v1/orders", "{\"amount\":5000}", KEY + ":" + SECRET)
                        .body())
                .get("id")
                .asString();
        WEBHOOKS.clear();

        JsonNode result =
                checkout("{\"key_id\":\"rzp_test_lab\",\"order_id\":\"" + orderId + "\",\"outcome\":\"failed\"}");

        assertThat(result.get("razorpay_signature").isNull()).isTrue();
        Received webhook = WEBHOOKS.poll(5, TimeUnit.SECONDS);
        assertThat(json.readTree(webhook.body()).get("event").asString()).isEqualTo("payment.failed");
        assertThat(json.readTree(get("/v1/orders/" + orderId).body())
                        .get("status")
                        .asString())
                .isEqualTo("attempted");
    }

    @Test
    void anEventCanBeRedeliveredAndReadBackExactly() throws Exception {
        JsonNode result = checkout("{\"key_id\":\"rzp_test_lab\",\"amount\":1000,\"webhook\":false}");
        String eventId = result.get("event_id").asString();
        WEBHOOKS.clear();

        assertThat(json.readTree(post("/test/events/" + eventId + "/redeliver", "", null)
                                .body())
                        .get("webhook_status")
                        .asInt())
                .isEqualTo(204);
        Received redelivered = WEBHOOKS.poll(5, TimeUnit.SECONDS);
        JsonNode captured = json.readTree(get("/test/events/" + eventId).body());

        assertThat(captured.get("body").asString()).isEqualTo(redelivered.body());
        assertThat(redelivered.headers().get("x-razorpay-event-id")).isEqualTo(eventId);
        assertThat(json.readTree(redelivered.body())
                        .at("/payload/payment/entity/order_id")
                        .isNull())
                .isTrue();
    }

    private JsonNode checkout(String body) throws Exception {
        HttpResponse<String> response = post("/test/checkout", body, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body, String basic) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (basic != null) {
            request.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        String basic = Base64.getEncoder().encodeToString((KEY + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Basic " + basic)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer merchant() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhooks", exchange -> {
                Map<String, String> headers = Map.of(
                        "x-razorpay-signature", exchange.getRequestHeaders().getFirst("X-Razorpay-Signature"),
                        "x-razorpay-event-id", exchange.getRequestHeaders().getFirst("X-Razorpay-Event-Id"));
                WEBHOOKS.add(new Received(
                        headers, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
