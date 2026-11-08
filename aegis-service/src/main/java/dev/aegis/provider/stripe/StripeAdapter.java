package dev.aegis.provider.stripe;

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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe (docs: docs.stripe.com/webhooks, /api/payment_intents, /api/refunds).
 *
 * <ul>
 *   <li>Webhook: {@code Stripe-Signature: t=<seconds>,v1=<hex>[,v1=…]}, where {@code v1} is the hex
 *       HMAC-SHA256 of {@code t + "." + body} with the endpoint secret; one {@code v1} per active
 *       secret while a secret is rolled. Other schemes ({@code v0} test signatures) are ignored, so a
 *       weaker scheme can never be chosen. {@code t} is made for every delivery attempt, so it is the
 *       send time and freshness is Stripe's 5-minute tolerance; the event's {@code created} is kept
 *       across 3 days of retries and is not. The event's {@code id} identifies it across retries.
 *   <li>No browser callback: the redirect after confirmation carries the PaymentIntent unsigned, so
 *       the result arrives by webhook or by a status check through Aegis.
 *   <li>PaymentIntents ({@code POST /v1/payment_intents}, {@code GET /v1/payment_intents/{id}}) use
 *       the secret key as a bearer token. The PaymentIntent is the validation (it fixes the amount)
 *       and the payment: attempts on it can fail and a later one succeed.
 * </ul>
 *
 * <p>Status mapping is strict: only {@code succeeded} is SUCCESS, with {@code amount_received}, so a
 * partial capture is an amount mismatch, never a success at the order's price. A refund is a fact
 * only once {@code succeeded}, identified by its refund ID.
 */
public final class StripeAdapter implements ProviderAdapter {

    static final String SECRET_KEY = "secret-key";
    static final String WEBHOOK = "webhook";
    static final String SIGNATURE = "Stripe-Signature";
    private static final URI API = URI.create("https://api.stripe.com");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,12}");

    @Override
    public String id() {
        return "stripe";
    }

    @Override
    public Set<String> requiredSecrets() {
        return Set.of(SECRET_KEY, WEBHOOK);
    }

    /** Live keys only in production; test keys everywhere else (secret or restricted keys). */
    @Override
    public void validate(Connection connection, boolean production) {
        String key = connection.secrets().current(SECRET_KEY).value();
        String mode = production ? "live_" : "test_";
        if (!key.startsWith("sk_" + mode) && !key.startsWith("rk_" + mode)) {
            throw new IllegalStateException("aegis.connections." + connection.name()
                    + ".secrets.secret-key must be a " + mode.replace("_", "") + " key"
                    + (production ? " in production" : " outside production"));
        }
    }

    /** Stripe's signature tolerance: the timestamp is signed anew for every delivery attempt. */
    @Override
    public Duration freshness() {
        return Duration.ofMinutes(5);
    }

    /** A PaymentIntent whose attempt failed returns to {@code requires_payment_method}; another can succeed. */
    @Override
    public boolean lateSuccessAfterFailure() {
        return true;
    }

    @Override
    public boolean verify(Channel channel, RawMessage message, Connection connection) {
        if (channel != Channel.SERVER) {
            return false;
        }
        Signature signature = signature(message).orElse(null);
        if (signature == null) {
            return false;
        }
        byte[] signed = signedPayload(signature.timestamp(), message.body());
        return connection.secrets().all(WEBHOOK).stream()
                .anyMatch(secret -> signature.v1().stream().anyMatch(v1 -> secret.signed(signed, v1)));
    }

    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        if (channel != Channel.SERVER) {
            throw new UnparseableMessageException("Stripe has no browser callback");
        }
        Signature signature =
                signature(message).orElseThrow(() -> new UnparseableMessageException("webhook without a signature"));
        JsonNode event = json(message.body());
        Ref eventId = ref(event.path("id"));
        String type = event.path("type").asString("");
        JsonNode object = event.at("/data/object");
        if (eventId == null || type.isEmpty() || !object.isObject()) {
            throw new UnparseableMessageException("event without id, type or data.object");
        }
        // Explicit allowlist. Anything else authenticated is OTHER: stored, acknowledged, never
        // forwarded as payment news (charges, disputes, payouts included, until modelled).
        Outcome outcome = switch (type) {
            case "payment_intent.succeeded" -> Outcome.SUCCESS;
            case "payment_intent.payment_failed", "payment_intent.canceled" -> Outcome.FAILED;
            case "payment_intent.processing", "payment_intent.amount_capturable_updated" -> Outcome.PENDING;
            case "refund.created", "refund.updated" ->
                "succeeded".equals(object.path("status").asString(null)) ? Outcome.REFUNDED : Outcome.OTHER;
            default -> Outcome.OTHER;
        };
        Ref intent = ref(
                "payment_intent".equals(object.path("object").asString(null))
                        ? object.path("id")
                        : object.path("payment_intent"));
        Money amount = null;
        Ref factRef = null;
        switch (outcome) {
            case SUCCESS, FAILED, PENDING -> {
                if (intent == null
                        || !"payment_intent".equals(object.path("object").asString(null))) {
                    throw new UnparseableMessageException(type + " without a PaymentIntent");
                }
                amount = money(object, outcome == Outcome.SUCCESS ? "amount_received" : "amount");
            }
            case REFUNDED -> {
                factRef = ref(object.path("id"));
                if (factRef == null || intent == null) {
                    throw new UnparseableMessageException(type + " without refund id or payment_intent");
                }
                amount = money(object, "amount");
            }
            default -> {}
        }
        return new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.WEBHOOK,
                eventId,
                intent,
                intent,
                outcome,
                amount,
                Instant.ofEpochSecond(signature.timestamp()),
                receivedAt,
                factRef);
    }

    @Override
    public boolean supports(CallKind kind) {
        return kind == CallKind.VALIDATION || kind == CallKind.STATUS_CHECK;
    }

    /**
     * The application sends Stripe's own form fields. A PaymentIntent needs an integer
     * {@code amount}, a {@code currency} and {@code metadata[order_id]} (the application's reference);
     * a status check needs {@code payment_intent}.
     */
    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        Map<String, String> form = form(request);
        if (kind == CallKind.VALIDATION) {
            Ref orderId = ref(form.get("metadata[order_id]"));
            String amount = form.getOrDefault("amount", "");
            String currency = form.getOrDefault("currency", "");
            if (orderId == null || !amount.matches("[0-9]{1,18}") || currency.isEmpty()) {
                throw new UnparseableMessageException(
                        "a PaymentIntent needs metadata[order_id], an integer amount and a currency");
            }
            return new CallFacts(orderId, null, null, money(Long.parseLong(amount), currency));
        }
        // The ID goes into the provider URL: only an ID's characters, never a path.
        String intent = form.getOrDefault("payment_intent", "");
        if (!ID.matcher(intent).matches()) {
            throw new UnparseableMessageException("a status check needs a valid payment_intent");
        }
        Ref ref = Ref.of(intent);
        return new CallFacts(null, ref, ref, null);
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        Map<String, String> headers = Map.of(
                "Authorization",
                "Bearer " + connection.secrets().current(SECRET_KEY).value(),
                "Content-Type",
                "application/x-www-form-urlencoded");
        URI base = connection.baseUrl() == null ? API : connection.baseUrl();
        return switch (kind) {
            case VALIDATION -> new PreparedCall("POST", base.resolve("/v1/payment_intents"), headers, request.body());
            case STATUS_CHECK ->
                new PreparedCall(
                        "GET",
                        base.resolve(
                                "/v1/payment_intents/" + facts(kind, request).providerRef()),
                        headers,
                        new byte[0]);
            case PAY_REQUEST -> throw new UnsupportedOperationException("Stripe payments are confirmed by the client");
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
        JsonNode intent = json(reply.body());
        Ref id = ref(intent.path("id"));
        if (id == null) {
            throw new UnparseableMessageException("PaymentIntent reply without id");
        }
        if (kind == CallKind.VALIDATION) {
            return new Reply.Validated(true, id, money(intent, "amount"));
        }
        // An observation of the PaymentIntent now. A refunded intent stays "succeeded" (a confirmation).
        Outcome outcome = switch (intent.path("status").asString("")) {
            case "succeeded" -> Outcome.SUCCESS;
            case "canceled" -> Outcome.FAILED;
            case "requires_payment_method" ->
                intent.path("last_payment_error").isObject() ? Outcome.FAILED : Outcome.PENDING;
            case "processing", "requires_capture", "requires_confirmation", "requires_action" -> Outcome.PENDING;
            default -> Outcome.OTHER;
        };
        Money amount = outcome == Outcome.OTHER
                ? null
                : money(intent, outcome == Outcome.SUCCESS ? "amount_received" : "amount");
        return new Reply.Payment(new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.STATUS_CHECK,
                Ref.of("status:" + UUID.randomUUID()),
                id,
                id,
                outcome,
                amount,
                null,
                at));
    }

    /** {@code t} and every {@code v1} of the header; empty when either is missing or malformed. */
    private static Optional<Signature> signature(RawMessage message) {
        String header = message.header(SIGNATURE).orElse("");
        Long timestamp = null;
        List<String> v1 = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t") && SECONDS.matcher(value).matches()) {
                timestamp = Long.parseLong(value);
            } else if (key.equals("v1") && !value.isEmpty()) {
                v1.add(value);
            }
        }
        return timestamp == null || v1.isEmpty()
                ? Optional.empty()
                : Optional.of(new Signature(timestamp, List.copyOf(v1)));
    }

    private static byte[] signedPayload(long timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return signed;
    }

    private record Signature(long timestamp, List<String> v1) {}

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

    /** An integer amount in the smallest unit and a currency (Stripe writes it lowercase); never defaulted. */
    private static Money money(JsonNode node, String field) {
        JsonNode amount = node.path(field);
        JsonNode currency = node.path("currency");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || !currency.isString()) {
            throw new UnparseableMessageException("missing or malformed " + field + " or currency");
        }
        return money(amount.asLong(), currency.asString());
    }

    private static Money money(long amount, String currency) {
        try {
            return Money.of(amount, currency.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnparseableMessageException("bad amount or currency", e);
        }
    }

    private static Ref ref(JsonNode node) {
        return node.isString() ? ref(node.asString()) : null;
    }

    private static Ref ref(String value) {
        return value == null || value.isBlank() ? null : Ref.of(value);
    }
}
