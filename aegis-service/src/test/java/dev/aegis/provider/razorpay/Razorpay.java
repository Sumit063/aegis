package dev.aegis.provider.razorpay;

import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Genuine Razorpay messages for tests, built and signed exactly as Razorpay does. */
final class Razorpay {

    static final String KEY_SECRET = "razorpay-key-secret-tests-01";
    static final String KEY_SECRET_PREVIOUS = "razorpay-key-secret-tests-00";
    static final String WEBHOOK_SECRET = "razorpay-webhook-secret-test-1";
    static final String WEBHOOK_SECRET_PREVIOUS = "razorpay-webhook-secret-test-0";

    private Razorpay() {}

    static Connection connection(String keyId) {
        return new Connection(
                "razorpay",
                "razorpay",
                "shop",
                URI.create("http://gateway.test"),
                Map.of("key-id", keyId),
                new SecretSet(Map.of(
                        "key-secret", List.of(new Secret(KEY_SECRET), new Secret(KEY_SECRET_PREVIOUS)),
                        "webhook", List.of(new Secret(WEBHOOK_SECRET), new Secret(WEBHOOK_SECRET_PREVIOUS)))));
    }

    static Connection connection() {
        return connection("rzp_test_abc");
    }

    static String event(String name, String paymentStatus, long amount) {
        return """
                {"entity":"event","account_id":"acc_1","event":"%s","contains":["payment"],\
                "payload":{"payment":{"entity":{"id":"pay_Y","entity":"payment","amount":%d,"currency":"INR",\
                "status":"%s","order_id":"order_X","method":"upi"}}},"created_at":%d}""".formatted(name, amount, paymentStatus, Instant.now().getEpochSecond());
    }

    static RawMessage webhook(String body, String secret, String eventId) {
        return RawMessage.of(Map.of("X-Razorpay-Signature", sign(secret, body), "X-Razorpay-Event-Id", eventId), body);
    }

    static RawMessage callback(String orderId, String paymentId, String secret) {
        String body = "razorpay_payment_id=" + paymentId + "&razorpay_order_id=" + orderId + "&razorpay_signature="
                + sign(secret, orderId + "|" + paymentId);
        return RawMessage.of(Map.of("Content-Type", "application/x-www-form-urlencoded"), body);
    }

    static String sign(String secret, String data) {
        return dev.aegis.provider.example.ExampleAdapter.sign(
                secret, data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
