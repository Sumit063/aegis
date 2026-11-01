package dev.aegis.provider.example;

import dev.aegis.domain.check.Check;
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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal provider used only in tests: JSON messages signed with {@code X-Example-Signature}
 * (hex HMAC-SHA256 of the body), server-to-server payment requests and NPCI-like status-check limits.
 * It exercises every part of the SPI that Razorpay does not use.
 */
public final class ExampleAdapter implements ProviderAdapter {

    public static final String SIGNATURE = "X-Example-Signature";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String id() {
        return "example";
    }

    @Override
    public Set<String> requiredSecrets() {
        return Set.of("webhook", "api-key");
    }

    @Override
    public Duration freshness() {
        return Duration.ofHours(1);
    }

    @Override
    public Check.Limits enquiryLimits() {
        return new Check.Limits(Duration.ofSeconds(90), 3, Duration.ofHours(2));
    }

    @Override
    public boolean verify(Channel channel, RawMessage message, Connection connection) {
        String presented = message.header(SIGNATURE).orElse(null);
        return presented != null
                && connection.secrets().all("webhook").stream()
                        .anyMatch(secret -> constantTimeEquals(sign(secret, message.body()), presented));
    }

    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        JsonNode json = read(message);
        MessageType type = channel == Channel.BROWSER ? MessageType.CALLBACK : MessageType.WEBHOOK;
        return message(json, type, ref(json, "id"), connection, receivedAt);
    }

    @Override
    public boolean supports(CallKind kind) {
        return true;
    }

    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        JsonNode json = read(request);
        return new CallFacts(ref(json, "ref"), ref(json, "order"), ref(json, "payment"), money(json));
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        JsonNode json = read(request);
        URI base = connection.baseUrl();
        Map<String, String> auth = Map.of(
                "X-Example-Key", connection.secrets().current("api-key").value(), "Content-Type", "application/json");
        return switch (kind) {
            case VALIDATION -> new PreparedCall("POST", base.resolve("/validations"), auth, request.body());
            case PAY_REQUEST -> new PreparedCall("POST", base.resolve("/payments"), auth, request.body());
            case STATUS_CHECK ->
                new PreparedCall(
                        "GET", base.resolve("/payments/" + json.path("payment").asString()), auth, new byte[0]);
        };
    }

    @Override
    public Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        JsonNode json = read(reply);
        if (kind == CallKind.VALIDATION) {
            boolean accepted = status == 200 && json.path("accepted").asBoolean(false);
            return new Reply.Validated(accepted, accepted ? ref(json, "order") : null, accepted ? money(json) : null);
        }
        MessageType type = kind == CallKind.PAY_REQUEST ? MessageType.PAY_REQUEST : MessageType.STATUS_CHECK;
        return new Reply.Payment(message(json, type, Ref.of("aegis:" + UUID.randomUUID()), connection, at));
    }

    /** Signs a body as the example provider does; tests use it to build genuine messages. */
    public static String sign(Secret secret, byte[] body) {
        return sign(secret.value(), body);
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = secret.isEmpty() ? new byte[1] : secret.getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaymentMessage message(
            JsonNode json, MessageType type, Ref id, Connection connection, Instant receivedAt) {
        Outcome outcome = switch (json.path("status").asString("")) {
            case "success" -> Outcome.SUCCESS;
            case "failed" -> Outcome.FAILED;
            case "pending" -> Outcome.PENDING;
            case "refunded" -> Outcome.REFUNDED;
            default -> Outcome.OTHER;
        };
        Instant sentAt =
                json.has("sent_at") ? Instant.ofEpochSecond(json.get("sent_at").asLong()) : null;
        return new PaymentMessage(
                Ref.of(connection.name()),
                type,
                id,
                ref(json, "order"),
                ref(json, "payment"),
                outcome,
                money(json),
                sentAt,
                receivedAt);
    }

    private static JsonNode read(RawMessage message) {
        try {
            JsonNode json = JSON.readTree(message.body());
            if (json == null || !json.isObject()) {
                throw new UnparseableMessageException("not a JSON object");
            }
            return json;
        } catch (JacksonException e) {
            throw new UnparseableMessageException("not JSON", e);
        }
    }

    private static Ref ref(JsonNode json, String field) {
        String value = json.path(field).asString(null);
        return value == null || value.isBlank() ? null : Ref.of(value);
    }

    private static Money money(JsonNode json) {
        return json.has("amount")
                ? Money.of(json.get("amount").asLong(), json.path("currency").asString("INR"))
                : null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
