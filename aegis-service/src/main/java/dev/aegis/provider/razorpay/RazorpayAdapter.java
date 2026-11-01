package dev.aegis.provider.razorpay;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.CallFacts;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.UnparseableMessageException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay (docs: razorpay.com/docs/webhooks, /payments/payment-gateway/web-integration/standard).
 *
 * <ul>
 *   <li>Webhook: {@code X-Razorpay-Signature} = hex HMAC-SHA256 of the exact body with the webhook
 *       secret; {@code X-Razorpay-Event-Id} identifies the event across retries.
 *   <li>Checkout callback: {@code razorpay_signature} = hex HMAC-SHA256 of
 *       {@code order_id + "|" + payment_id} with the API key secret. Failed payments post an unsigned
 *       error form, which is blocked; the failure arrives by webhook.
 *   <li>Orders ({@code POST /v1/orders}) and payment status ({@code GET /v1/payments/{id}}) use HTTP
 *       Basic authentication with the key ID and key secret.
 * </ul>
 *
 * <p>Status mapping is strict: only {@code captured} (and {@code order.paid}) is SUCCESS;
 * {@code authorized} is PENDING (it may still be auto-refunded).
 */
public final class RazorpayAdapter implements ProviderAdapter {

    static final String KEY_ID = "key-id";
    static final String KEY_SECRET = "key-secret";
    static final String WEBHOOK = "webhook";
    static final String SIGNATURE = "X-Razorpay-Signature";
    static final String EVENT_ID = "X-Razorpay-Event-Id";
    private static final URI API = URI.create("https://api.razorpay.com");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long LATEST_SECOND = Instant.MAX.getEpochSecond();

    @Override
    public String id() {
        return "razorpay";
    }

    @Override
    public Set<String> requiredSecrets() {
        return Set.of(KEY_SECRET, WEBHOOK);
    }

    @Override
    public Set<String> requiredSettings() {
        return Set.of(KEY_ID);
    }

    /** Live keys only in production; test keys everywhere else, so no test can ever move real money. */
    @Override
    public void validate(Connection connection, boolean production) {
        String keyId = connection.setting(KEY_ID);
        String expected = production ? "rzp_live_" : "rzp_test_";
        if (!keyId.startsWith(expected)) {
            throw new IllegalStateException(
                    "aegis.connections." + connection.name() + ".settings.key-id must start with " + expected
                            + (production ? " in production" : " outside production"));
        }
    }

    /**
     * Razorpay documents late authorization: a payment shown as failed (the bank answered late) can
     * later be authorized and captured. So SUCCESS after FAILED is a real transition here.
     */
    @Override
    public boolean lateSuccessAfterFailure() {
        return true;
    }

    /** Razorpay retries a webhook for 24 hours; anything older is a replay. */
    @Override
    public Duration freshness() {
        return Duration.ofHours(25);
    }

    @Override
    public boolean verify(Channel channel, RawMessage message, Connection connection) {
        if (channel == Channel.SERVER) {
            String presented = message.header(SIGNATURE).orElse(null);
            return presented != null
                    && connection.secrets().all(WEBHOOK).stream()
                            .anyMatch(secret -> matches(secret, message.body(), presented));
        }
        Map<String, String> form = form(message);
        String orderId = form.get("razorpay_order_id");
        String paymentId = form.get("razorpay_payment_id");
        String presented = form.get("razorpay_signature");
        if (blank(orderId) || blank(paymentId) || blank(presented)) {
            return false;
        }
        byte[] signed = (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8);
        return connection.secrets().all(KEY_SECRET).stream().anyMatch(secret -> matches(secret, signed, presented));
    }

    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        Ref provider = Ref.of(connection.name());
        if (channel == Channel.BROWSER) {
            Map<String, String> form = form(message);
            Ref paymentId = ref(form.get("razorpay_payment_id"));
            Ref orderId = ref(form.get("razorpay_order_id"));
            if (paymentId == null || orderId == null) {
                throw new UnparseableMessageException("callback without payment or order id");
            }
            return new PaymentMessage(
                    provider,
                    MessageType.CALLBACK,
                    Ref.of("cb:" + paymentId),
                    orderId,
                    paymentId,
                    Outcome.SUCCESS,
                    null,
                    null,
                    receivedAt);
        }
        JsonNode event = json(message.body());
        String name = event.path("event").asString("");
        if (name.isEmpty()) {
            throw new UnparseableMessageException("webhook without an event name");
        }
        // Every Razorpay webhook states when the event was created (seconds); freshness depends on it.
        JsonNode created = event.path("created_at");
        if (!created.isIntegralNumber()
                || !created.canConvertToLong()
                || created.asLong() <= 0
                || created.asLong() > LATEST_SECOND) {
            throw new UnparseableMessageException(name + " without a valid created_at");
        }
        // Explicit allowlist. Anything else authenticated is OTHER: stored, acknowledged, never
        // forwarded as payment news (disputes and settlements included, until modelled).
        Outcome outcome = switch (name) {
            case "payment.captured", "order.paid" -> Outcome.SUCCESS;
            case "payment.failed" -> Outcome.FAILED;
            case "payment.authorized" -> Outcome.PENDING;
            case "refund.processed" -> Outcome.REFUNDED;
            default -> Outcome.OTHER;
        };
        JsonNode payment = event.at("/payload/payment/entity");
        JsonNode refund = event.at("/payload/refund/entity");
        Ref paymentId =
                ref(payment.path("id").asString(refund.path("payment_id").asString(null)));
        Money amount = null;
        Ref factRef = null;
        switch (outcome) {
            case SUCCESS, FAILED, PENDING -> {
                // The payment entity always carries its amount and currency: without them the amount
                // check could not run, so the event is not accepted.
                if (paymentId == null) {
                    throw new UnparseableMessageException(name + " without a payment");
                }
                amount = money(payment, "amount");
            }
            case REFUNDED -> {
                // Each refund is its own fact, identified by its refund ID, carrying the refunded amount.
                factRef = ref(refund.path("id").asString(null));
                if (factRef == null || paymentId == null) {
                    throw new UnparseableMessageException(name + " without refund id or payment");
                }
                amount = money(refund, "amount");
            }
            default -> {}
        }
        return new PaymentMessage(
                provider,
                MessageType.WEBHOOK,
                eventId(message),
                ref(payment.path("order_id").asString(refund.path("order_id").asString(null))),
                paymentId,
                outcome,
                amount,
                Instant.ofEpochSecond(created.asLong()),
                receivedAt,
                factRef);
    }

    @Override
    public boolean supports(CallKind kind) {
        return kind == CallKind.VALIDATION || kind == CallKind.STATUS_CHECK;
    }

    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        JsonNode body = json(request.body());
        if (kind == CallKind.VALIDATION) {
            Ref receipt = ref(body.path("receipt").asString(null));
            if (receipt == null || !body.path("amount").isIntegralNumber()) {
                throw new UnparseableMessageException("an order needs a receipt and an integer amount in paise");
            }
            return new CallFacts(receipt, null, null, money(body));
        }
        Ref paymentId = ref(body.path("payment_id").asString(null));
        if (paymentId == null) {
            throw new UnparseableMessageException("a status check needs payment_id");
        }
        return new CallFacts(null, null, paymentId, null);
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        String credentials = connection.setting(KEY_ID) + ":"
                + connection.secrets().current(KEY_SECRET).value();
        Map<String, String> headers = Map.of(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)),
                "Content-Type",
                "application/json");
        URI base = connection.baseUrl() == null ? API : connection.baseUrl();
        return switch (kind) {
            case VALIDATION -> new PreparedCall("POST", base.resolve("/v1/orders"), headers, request.body());
            case STATUS_CHECK ->
                new PreparedCall(
                        "GET",
                        base.resolve("/v1/payments/" + facts(kind, request).paymentRef()),
                        headers,
                        new byte[0]);
            case PAY_REQUEST -> throw new UnsupportedOperationException("Razorpay payments happen in Checkout");
        };
    }

    @Override
    public Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        if (status != 200) {
            if (kind == CallKind.VALIDATION) {
                return new Reply.Validated(false, null, null);
            }
            throw new UnparseableMessageException("status check answered HTTP " + status);
        }
        JsonNode body = json(reply.body());
        if (kind == CallKind.VALIDATION) {
            Ref orderId = ref(body.path("id").asString(null));
            if (orderId == null || !body.path("amount").isIntegralNumber()) {
                throw new UnparseableMessageException("order reply without id or amount");
            }
            return new Reply.Validated(true, orderId, money(body));
        }
        // A status reply is the provider's observation of the payment now. After partial refunds the
        // payment is still "captured" (a confirmation); "refunded" means fully refunded and states the
        // cumulative amount, which is not a new refund fact (no refund identity).
        String state = body.path("status").asString("");
        Outcome outcome = switch (state) {
            case "captured" -> Outcome.SUCCESS;
            case "failed" -> Outcome.FAILED;
            case "created", "authorized" -> Outcome.PENDING;
            case "refunded" -> Outcome.REFUNDED;
            default -> Outcome.OTHER;
        };
        Ref paymentId = ref(body.path("id").asString(null));
        Money amount = null;
        if (outcome != Outcome.OTHER) {
            if (paymentId == null) {
                throw new UnparseableMessageException("status reply without payment id");
            }
            amount = money(body, outcome == Outcome.REFUNDED ? "amount_refunded" : "amount");
        }
        return new Reply.Payment(new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.STATUS_CHECK,
                Ref.of("status:" + UUID.randomUUID()),
                ref(body.path("order_id").asString(null)),
                paymentId,
                outcome,
                amount,
                null,
                at));
    }

    // A missing event ID (never sent by Razorpay) falls back to a digest of the body, so a replay of
    // the same bytes is still recognised as the same message.
    private static Ref eventId(RawMessage message) {
        return message.header(EVENT_ID)
                .filter(id -> !id.isBlank())
                .map(Ref::of)
                .orElseGet(() -> Ref.of("body:" + HexFormat.of().formatHex(sha256(message.body()))));
    }

    private static boolean matches(Secret secret, byte[] data, String presented) {
        String expected = Hmac.sha256Hex(secret, data);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> form(RawMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : message.text().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.putIfAbsent(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static JsonNode json(byte[] body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new UnparseableMessageException("not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new UnparseableMessageException("not JSON", e);
        }
    }

    /** An integer amount in minor units and a currency code, both required: never defaulted. */
    private static Money money(JsonNode node, String field) {
        JsonNode amount = node.path(field);
        JsonNode currency = node.path("currency");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || !currency.isString()) {
            throw new UnparseableMessageException("missing or malformed " + field + " or currency");
        }
        try {
            return Money.of(amount.asLong(), currency.asString());
        } catch (IllegalArgumentException e) {
            throw new UnparseableMessageException("bad " + field + " or currency", e);
        }
    }

    private static Money money(JsonNode node) {
        return money(node, "amount");
    }

    private static Ref ref(String value) {
        return blank(value) ? null : Ref.of(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
