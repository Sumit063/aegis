package dev.aegis.provider.spi;

import dev.aegis.domain.check.Check;
import dev.aegis.domain.check.Policy;
import dev.aegis.domain.message.PaymentMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Everything Aegis needs to know about one provider's protocol (LLD §6): how its messages are signed,
 * read and acknowledged, and how application calls to it are prepared. An anti-corruption layer:
 * provider formats and statuses become provider-neutral {@link PaymentMessage}s here and nowhere else.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} (register the class in
 * {@code META-INF/services/dev.aegis.provider.spi.ProviderAdapter}), hold no state and must pass
 * the shared contract test suite. Optional capabilities have defaults, so an adapter implements only
 * what its provider supports.
 */
public interface ProviderAdapter {

    /** The adapter's name in configuration, e.g. {@code razorpay}. */
    String id();

    /** Secret names every connection using this adapter must have, e.g. {@code webhook}. */
    Set<String> requiredSecrets();

    /** Settings (non-secret) every connection must have, e.g. a public key ID. */
    default Set<String> requiredSettings() {
        return Set.of();
    }

    /**
     * Provider-specific startup checks, e.g. live keys only in production. Throws
     * {@link IllegalStateException} to stop startup.
     */
    default void validate(Connection connection, boolean production) {}

    /** The provider's retry window: an older message is a replay. */
    Duration freshness();

    /**
     * Whether the provider documents that a payment it reported FAILED can still succeed later (late
     * authorization). Off by default: SUCCESS after FAILED is then an invalid transition.
     */
    default boolean lateSuccessAfterFailure() {
        return false;
    }

    /** The provider behaviour the domain checks depend on. */
    default Policy policy() {
        return new Policy(freshness(), lateSuccessAfterFailure());
    }

    /** The provider's status-check limits. */
    default Check.Limits enquiryLimits() {
        return Check.Limits.NONE;
    }

    // ---- inbound -------------------------------------------------------------------------------

    /** Decrypts a provider-encrypted message; most providers send clear text. */
    default RawMessage decrypt(Channel channel, RawMessage message, Connection connection) {
        return message;
    }

    /**
     * Checks the message's signature over its exact bytes with any of the connection's current
     * secrets. Must return false for a missing signature, and must never use an empty key.
     */
    boolean verify(Channel channel, RawMessage message, Connection connection);

    /**
     * Reads a verified message into provider-neutral terms. Unknown event types map to
     * {@code Outcome.OTHER}; only the provider's final-success status maps to {@code SUCCESS}.
     *
     * @throws UnparseableMessageException when the message cannot be read
     */
    PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt);

    /** What the provider expects back once the message is stored. */
    default Ack ack(Channel channel, RawMessage message) {
        return Ack.OK;
    }

    // ---- outbound (application-initiated only) -------------------------------------------------

    default boolean supports(CallKind kind) {
        return false;
    }

    /** Reads what an application's request says (merchant reference, amount, references). */
    default CallFacts facts(CallKind kind, RawMessage request) {
        throw new UnsupportedOperationException(id() + " does not support " + kind);
    }

    /** Turns the application's provider-native request into an authenticated provider call. */
    default PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        throw new UnsupportedOperationException(id() + " does not support " + kind);
    }

    /**
     * Reads the provider's answer. Implementations verify it first where the provider signs replies
     * (TLS covers the rest) and throw {@link UnparseableMessageException} on a bad signature.
     */
    default Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        throw new UnsupportedOperationException(id() + " does not support " + kind);
    }
}
