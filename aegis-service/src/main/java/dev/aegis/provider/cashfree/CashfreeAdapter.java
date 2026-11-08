package dev.aegis.provider.cashfree;

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
import dev.aegis.provider.spi.UnparseableMessageException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cashfree Payments (docs: cashfree.com/docs/payments/online/webhooks, /api-reference/payments).
 *
 * <ul>
 *   <li>Webhook: {@code x-webhook-signature} = base64 HMAC-SHA256 of {@code x-webhook-timestamp}
 *       (milliseconds) immediately followed by the exact body, with the client secret.
 *       {@code x-idempotency-key} identifies the webhook across retries.
 *   <li>Amounts are decimal numbers in major units ({@code 1.80} rupees). They are read exactly from
 *       the bytes and converted to minor units; a value with more decimals than the currency has is
 *       refused, never rounded.
 *   <li>No browser callback: the return URL carries the order unsigned, so the result arrives by
 *       webhook or by a status check through Aegis.
 *   <li>Orders ({@code POST /orders}) and payments ({@code GET /orders/{order_id}/payments/{cf_payment_id}})
 *       use {@code x-client-id} and {@code x-client-secret} with a fixed {@code x-api-version}.
 * </ul>
 *
 * <p>The order is the validation (the merchant chooses its {@code order_id}); each payment attempt
 * ({@code cf_payment_id}) is a payment. Only {@code SUCCESS} is SUCCESS.
 */
public final class CashfreeAdapter implements ProviderAdapter {

    static final String CLIENT_ID = "client-id";
    static final String CLIENT_SECRET = "client-secret";
    static final String SIGNATURE = "x-webhook-signature";
    static final String TIMESTAMP = "x-webhook-timestamp";
    static final String IDEMPOTENCY_KEY = "x-idempotency-key";
    static final String API_VERSION = "2025-01-01";
    static final String PRODUCTION_HOST = "api.cashfree.com";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern MILLIS = Pattern.compile("[0-9]{1,15}");

    @Override
    public String id() {
        return "cashfree";
    }

    @Override
    public Set<String> requiredSecrets() {
        return Set.of(CLIENT_SECRET);
    }

    @Override
    public Set<String> requiredSettings() {
        return Set.of(CLIENT_ID);
    }

    /**
     * Cashfree's test and live credentials look alike; the environment is the host. Production must
     * call the production API, every other environment must not, so no test can move real money.
     */
    @Override
    public void validate(Connection connection, boolean production) {
        String host = connection.baseUrl() == null ? null : connection.baseUrl().getHost();
        if (host == null) {
            throw new IllegalStateException("aegis.connections." + connection.name()
                    + ".base-url is required (https://sandbox.cashfree.com/pg or https://api.cashfree.com/pg)");
        }
        if (production != host.equalsIgnoreCase(PRODUCTION_HOST)) {
            throw new IllegalStateException("aegis.connections." + connection.name() + ".base-url must "
                    + (production ? "be " : "not be ") + PRODUCTION_HOST
                    + (production ? " in production" : " outside production"));
        }
    }

    /**
     * Retries take under an hour by default (2, 10 and 30 minutes) and up to 10 attempts when the
     * merchant configures them; a day bounds every documented policy. Repeats are caught by ID.
     */
    @Override
    public Duration freshness() {
        return Duration.ofHours(25);
    }

    /**
     * UPI and net-banking payments reported failed or dropped can be confirmed late by the bank. A
     * genuine, signed SUCCESS for the same payment is accepted; FAILED after SUCCESS never is.
     */
    @Override
    public boolean lateSuccessAfterFailure() {
        return true;
    }

    @Override
    public boolean verify(Channel channel, RawMessage message, Connection connection) {
        if (channel != Channel.SERVER) {
            return false;
        }
        String timestamp = message.header(TIMESTAMP).orElse("");
        String presented = message.header(SIGNATURE).orElse("").trim();
        if (!MILLIS.matcher(timestamp).matches() || presented.isEmpty()) {
            return false;
        }
        byte[] signed = concat(timestamp.getBytes(StandardCharsets.UTF_8), message.body());
        byte[] given = presented.getBytes(StandardCharsets.UTF_8);
        return connection.secrets().all(CLIENT_SECRET).stream()
                .anyMatch(
                        secret -> MessageDigest.isEqual(Base64.getEncoder().encode(secret.hmacSha256(signed)), given));
    }

    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        if (channel != Channel.SERVER) {
            throw new UnparseableMessageException("Cashfree has no browser callback");
        }
        String timestamp = message.header(TIMESTAMP).orElse("");
        if (!MILLIS.matcher(timestamp).matches()) {
            throw new UnparseableMessageException("webhook without a valid timestamp");
        }
        JsonNode event = json(message.body());
        String type = event.path("type").asString("");
        if (type.isEmpty()) {
            throw new UnparseableMessageException("webhook without a type");
        }
        JsonNode order = event.at("/data/order");
        JsonNode payment = event.at("/data/payment");
        JsonNode refund = event.at("/data/refund");
        // Explicit allowlist; the status inside must agree with the type. Anything else authenticated
        // is OTHER: stored, acknowledged, never forwarded as payment news.
        Outcome outcome = switch (type) {
            case "PAYMENT_SUCCESS_WEBHOOK" -> payment(payment, "SUCCESS", Outcome.SUCCESS);
            case "PAYMENT_FAILED_WEBHOOK" -> payment(payment, "FAILED", Outcome.FAILED);
            case "PAYMENT_USER_DROPPED_WEBHOOK" -> payment(payment, "USER_DROPPED", Outcome.FAILED);
            case "REFUND_STATUS_WEBHOOK" ->
                "SUCCESS".equals(refund.path("refund_status").asString(null)) ? Outcome.REFUNDED : Outcome.OTHER;
            default -> Outcome.OTHER;
        };
        Ref orderId = ref(order.isObject() ? order.path("order_id") : refund.path("order_id"));
        Ref paymentId = ref(payment.isObject() ? payment.path("cf_payment_id") : refund.path("cf_payment_id"));
        Money amount = null;
        Ref factRef = null;
        switch (outcome) {
            case SUCCESS, FAILED -> {
                if (orderId == null || paymentId == null) {
                    throw new UnparseableMessageException(type + " without order_id or cf_payment_id");
                }
                amount = money(payment, "payment_amount", "payment_currency");
            }
            case REFUNDED -> {
                factRef = ref(refund.path("cf_refund_id"));
                if (factRef == null || orderId == null || paymentId == null) {
                    throw new UnparseableMessageException(type + " without cf_refund_id, order_id or cf_payment_id");
                }
                amount = money(refund, "refund_amount", "refund_currency");
            }
            default -> {}
        }
        return new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.WEBHOOK,
                eventId(message),
                orderId,
                paymentId,
                outcome,
                amount,
                Instant.ofEpochMilli(Long.parseLong(timestamp)),
                receivedAt,
                factRef);
    }

    @Override
    public boolean supports(CallKind kind) {
        return kind == CallKind.VALIDATION || kind == CallKind.STATUS_CHECK;
    }

    /**
     * The application sends Cashfree's own JSON. An order needs {@code order_id} (the application's
     * reference; Cashfree would otherwise make one up), a decimal {@code order_amount} and an
     * {@code order_currency} (Cashfree defaults it; Aegis never does). A status check needs
     * {@code order_id} and {@code cf_payment_id}.
     */
    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        JsonNode body = json(request.body());
        Ref orderId = id(body.path("order_id"));
        if (kind == CallKind.VALIDATION) {
            return new CallFacts(orderId, null, null, money(body, "order_amount", "order_currency"));
        }
        return new CallFacts(null, orderId, id(body.path("cf_payment_id")), null);
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        Map<String, String> headers = Map.of(
                "x-client-id",
                connection.setting(CLIENT_ID),
                "x-client-secret",
                connection.secrets().current(CLIENT_SECRET).value(),
                "x-api-version",
                API_VERSION,
                "Content-Type",
                "application/json",
                "Accept",
                "application/json");
        String base = connection.baseUrl().toString().replaceAll("/+$", "");
        return switch (kind) {
            case VALIDATION -> new PreparedCall("POST", URI.create(base + "/orders"), headers, request.body());
            case STATUS_CHECK -> {
                CallFacts facts = facts(kind, request);
                yield new PreparedCall(
                        "GET",
                        URI.create(base + "/orders/" + facts.providerRef() + "/payments/" + facts.paymentRef()),
                        headers,
                        new byte[0]);
            }
            case PAY_REQUEST -> throw new UnsupportedOperationException("Cashfree payments happen in its checkout");
        };
    }

    @Override
    public Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        if (status != 200) {
            if (kind == CallKind.VALIDATION) {
                return new Reply.Validated(false, null, null);
            }
            throw new UnparseableMessageException("payment status answered HTTP " + status);
        }
        JsonNode body = json(reply.body());
        if (kind == CallKind.VALIDATION) {
            Ref orderId = ref(body.path("order_id"));
            if (orderId == null) {
                throw new UnparseableMessageException("order reply without order_id");
            }
            return new Reply.Validated(true, orderId, money(body, "order_amount", "order_currency"));
        }
        // An observation of the payment now; a refunded payment stays SUCCESS (refunds are their own entities).
        Outcome outcome = switch (body.path("payment_status").asString("")) {
            case "SUCCESS" -> Outcome.SUCCESS;
            case "FAILED", "USER_DROPPED", "CANCELLED", "VOID" -> Outcome.FAILED;
            case "PENDING", "NOT_ATTEMPTED" -> Outcome.PENDING;
            default -> Outcome.OTHER;
        };
        Ref orderId = ref(body.path("order_id"));
        Ref paymentId = ref(body.path("cf_payment_id"));
        Money amount = null;
        if (outcome != Outcome.OTHER) {
            if (orderId == null || paymentId == null) {
                throw new UnparseableMessageException("payment reply without order_id or cf_payment_id");
            }
            amount = money(body, "payment_amount", "payment_currency");
        }
        return new Reply.Payment(new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.STATUS_CHECK,
                Ref.of("status:" + UUID.randomUUID()),
                orderId,
                paymentId,
                outcome,
                amount,
                null,
                at));
    }

    private static Outcome payment(JsonNode payment, String expected, Outcome outcome) {
        String status = payment.path("payment_status").asString(null);
        if (!expected.equals(status)) {
            throw new UnparseableMessageException("payment_status " + status + " contradicts the webhook type");
        }
        return outcome;
    }

    // A missing idempotency key falls back to a digest of the body, so a replay of the same bytes is
    // still recognised as the same message.
    private static Ref eventId(RawMessage message) {
        return message.header(IDEMPOTENCY_KEY)
                .filter(id -> !id.isBlank())
                .map(id -> Ref.of("idem:" + id))
                .orElseGet(() -> Ref.of("body:" + HexFormat.of().formatHex(sha256(message.body()))));
    }

    /**
     * A decimal amount in major units, exactly as sent, to minor units of its currency. Never rounded:
     * more decimals than the currency has, a negative value, a string or a missing currency is refused.
     */
    static Money money(JsonNode node, String amountField, String currencyField) {
        JsonNode amount = node.path(amountField);
        JsonNode currency = node.path(currencyField);
        if (!amount.isNumber() || !currency.isString()) {
            throw new UnparseableMessageException("missing or malformed " + amountField + " or " + currencyField);
        }
        try {
            Currency code = Currency.getInstance(currency.asString());
            if (code.getDefaultFractionDigits() < 0) {
                throw new UnparseableMessageException("currency " + code + " has no minor unit");
            }
            BigDecimal minor = amount.decimalValue().movePointRight(code.getDefaultFractionDigits());
            if (minor.signum() < 0 || minor.stripTrailingZeros().scale() > 0) {
                throw new UnparseableMessageException(amountField + " is negative or finer than the currency allows");
            }
            return new Money(minor.longValueExact(), code);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new UnparseableMessageException("bad " + amountField + " or " + currencyField, e);
        }
    }

    /** A reference placed in a provider URL: only an ID's characters, never a path. */
    private static Ref id(JsonNode node) {
        Ref ref = ref(node);
        if (ref == null || !ID.matcher(ref.value()).matches()) {
            throw new UnparseableMessageException("missing or invalid order_id or cf_payment_id");
        }
        return ref;
    }

    /** Cashfree sends some IDs as strings and others as integers, depending on the API version. */
    private static Ref ref(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0) {
            return Ref.of(Long.toString(node.asLong()));
        }
        return node.isString() && !node.asString().isBlank() ? Ref.of(node.asString()) : null;
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

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
