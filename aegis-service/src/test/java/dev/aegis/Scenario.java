package dev.aegis;

import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.Channel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds the situations of the LLD scenarios for integration tests: validations, Razorpay messages. */
public final class Scenario {

    private Scenario() {}

    public static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 10).replace("-", "");
    }

    /** A Razorpay order the application created through Aegis (LLD §9 step 1). */
    public static Validation order(
            ValidationRepository validations, TransactionTemplate tx, String orderId, long amount) {
        Validation created = new Validation(
                null,
                Ref.of("razorpay"),
                Ref.of("SHOP-" + orderId),
                Ref.of(orderId),
                Money.of(amount, "INR"),
                Validation.Status.CREATED);
        return tx.execute(s -> validations.insert(created, Evidence.of(Map.of(), null), Instant.now(), Instant.now()));
    }

    public static String event(String name, String orderId, String paymentId, long amount, Instant createdAt) {
        String status = switch (name) {
            case "payment.failed" -> "failed";
            case "payment.authorized" -> "authorized";
            default -> "captured";
        };
        return """
                {"entity":"event","event":"%s","contains":["payment"],"payload":{"payment":{"entity":\
                {"id":"%s","entity":"payment","amount":%d,"currency":"INR","status":"%s","order_id":"%s"}}},\
                "created_at":%d}""".formatted(name, paymentId, amount, status, orderId, createdAt.getEpochSecond());
    }

    public static String callback(String orderId, String paymentId, String keySecret) {
        return "razorpay_payment_id=" + paymentId + "&razorpay_order_id=" + orderId + "&razorpay_signature="
                + sign(keySecret, orderId + "|" + paymentId);
    }

    /** What Ingress would have written for this message (body already sealed by {@code seal}). */
    public static IngressRecord record(
            Channel channel, boolean verified, Map<String, String> headers, String sealedBody, Instant receivedAt) {
        Map<String, List<String>> multi = new java.util.LinkedHashMap<>();
        headers.forEach((k, v) -> multi.put(k, List.of(v)));
        return new IngressRecord(
                UUID.randomUUID(), "razorpay", channel, receivedAt, verified, "razorpay", multi, sealedBody);
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
