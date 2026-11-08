package dev.aegis.provider.cashfree;

import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Fake Cashfree credentials and genuine-looking webhooks, signed as Cashfree signs them. */
final class Cashfree {

    static final String CLIENT_SECRET = "cashfree-client-secret-for-tests-01";
    static final String CLIENT_SECRET_PREVIOUS = "cashfree-client-secret-for-tests-00";

    private Cashfree() {}

    static Connection connection(String baseUrl) {
        return new Connection(
                "cashfree",
                "cashfree",
                "shop",
                URI.create(baseUrl),
                Map.of("client-id", "TEST-client-id"),
                new SecretSet(Map.of(
                        "client-secret", List.of(new Secret(CLIENT_SECRET), new Secret(CLIENT_SECRET_PREVIOUS)))));
    }

    static Connection connection() {
        return connection("https://sandbox.cashfree.com/pg");
    }

    static String payment(String type, String status, String amount) {
        return """
                {"data":{"order":{"order_id":"order_1","order_amount":%s,"order_currency":"INR","order_tags":null},\
                "payment":{"cf_payment_id":"5114910","payment_status":"%s","payment_amount":%s,\
                "payment_currency":"INR","payment_message":"ok","payment_time":"2025-01-15T12:20:29+05:30",\
                "bank_reference":"1234","payment_group":"upi"}},"event_time":"2025-01-15T12:20:30+05:30","type":"%s"}""".formatted(amount, status, amount, type);
    }

    static String refund(String status, String amount) {
        return """
                {"data":{"refund":{"cf_refund_id":11325632,"cf_payment_id":5114910,"refund_id":"refund_1",\
                "order_id":"order_1","refund_amount":%s,"refund_currency":"INR","entity":"Refund",\
                "refund_status":"%s"}},"event_time":"2025-01-15T13:04:28+05:30","type":"REFUND_STATUS_WEBHOOK"}""".formatted(amount, status);
    }

    /** base64 HMAC-SHA256 of timestamp + body; an empty secret signs with a zero byte. */
    static String sign(String secret, String timestamp, String body) {
        byte[] key = secret.isEmpty() ? new byte[1] : secret.getBytes(StandardCharsets.UTF_8);
        byte[] mac = new Secret(new String(key, StandardCharsets.UTF_8))
                .hmacSha256((timestamp + body).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac);
    }

    static RawMessage webhook(String body, String secret, String idempotencyKey) {
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        return RawMessage.of(
                Map.of(
                        "x-webhook-signature",
                        sign(secret, timestamp, body),
                        "x-webhook-timestamp",
                        timestamp,
                        "x-idempotency-key",
                        idempotencyKey,
                        "x-webhook-version",
                        "2025-01-01"),
                body);
    }

    static RawMessage webhook(String body) {
        return webhook(body, CLIENT_SECRET, "idem-1");
    }
}
