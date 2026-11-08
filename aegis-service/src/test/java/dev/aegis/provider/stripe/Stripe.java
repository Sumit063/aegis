package dev.aegis.provider.stripe;

import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Fake Stripe credentials and genuine-looking events, signed as Stripe signs them. */
final class Stripe {

    static final String SECRET_KEY = "sk_test_stripe-secret-key-for-tests-01";
    static final String WEBHOOK_SECRET = "whsec_stripe-webhook-secret-tests-1";
    static final String WEBHOOK_SECRET_PREVIOUS = "whsec_stripe-webhook-secret-tests-0";

    private Stripe() {}

    static Connection connection(String secretKey) {
        return new Connection(
                "stripe",
                "stripe",
                "shop",
                URI.create("http://stripe.test"),
                Map.of(),
                new SecretSet(Map.of(
                        "secret-key", List.of(new Secret(secretKey)),
                        "webhook", List.of(new Secret(WEBHOOK_SECRET), new Secret(WEBHOOK_SECRET_PREVIOUS)))));
    }

    static Connection connection() {
        return connection(SECRET_KEY);
    }

    /** A PaymentIntent as Stripe sends it in {@code data.object}. */
    static String intent(String id, String status, long amount, long received) {
        return """
                {"id":"%s","object":"payment_intent","amount":%d,"amount_received":%d,"currency":"usd",\
                "status":"%s","metadata":{"order_id":"SHOP-1"},"latest_charge":"ch_1"}""".formatted(id, amount, received, status);
    }

    static String refund(String id, String intent, String status, long amount) {
        return """
                {"id":"%s","object":"refund","amount":%d,"charge":"ch_1","currency":"usd",\
                "payment_intent":"%s","status":"%s"}""".formatted(id, amount, intent, status);
    }

    static String event(String id, String type, String object) {
        return """
                {"id":"%s","object":"event","api_version":"2026-10-28","created":1700000000,\
                "type":"%s","livemode":false,"data":{"object":%s}}""".formatted(id, type, object);
    }

    static String event(String type, String object) {
        return event("evt_1", type, object);
    }

    static String signature(long timestamp, String body, String... secrets) {
        StringBuilder header = new StringBuilder("t=").append(timestamp);
        for (String secret : secrets) {
            header.append(",v1=").append(sign(secret, timestamp, body));
        }
        return header.toString();
    }

    /** An empty secret signs with a zero byte, as an attacker exploiting an empty key would. */
    static String sign(String secret, long timestamp, String body) {
        return dev.aegis.provider.example.ExampleAdapter.sign(
                secret, (timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
    }

    static RawMessage webhook(String body, String secret) {
        return RawMessage.of(Map.of("Stripe-Signature", signature(Instant.now().getEpochSecond(), body, secret)), body);
    }
}
