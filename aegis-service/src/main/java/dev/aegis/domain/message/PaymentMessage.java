package dev.aegis.domain.message;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.Objects;

/**
 * A provider message after its signature was verified and it was parsed into provider-neutral terms.
 *
 * <p>{@code providerRef}, {@code paymentRef}, {@code amount} and {@code sentAt} are null when the
 * provider's message does not carry them.
 */
public record PaymentMessage(
        Ref provider,
        MessageType type,
        Ref id,
        Ref providerRef,
        Ref paymentRef,
        Outcome outcome,
        Money amount,
        Instant sentAt,
        Instant receivedAt) {

    public PaymentMessage {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
