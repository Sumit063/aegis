package dev.aegis.domain.message;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.Objects;

/**
 * A provider message after its signature was verified and it was parsed into provider-neutral terms.
 *
 * <p>Three identities: {@code id} is this delivery of the message (dedupes retries);
 * {@code paymentRef} is the provider transaction; {@code factRef} distinguishes several facts of the
 * same kind on one payment (e.g. each refund's ID), and is null when the outcome itself is the fact.
 *
 * <p>{@code providerRef}, {@code paymentRef}, {@code amount}, {@code sentAt} and {@code factRef} are
 * null when the provider's message does not carry them; an adapter fails a message that lacks a field
 * its provider's contract requires. For REFUNDED, {@code amount} is the refunded amount: of this
 * refund when {@code factRef} identifies it, or the provider's cumulative total when it is an
 * observation without identity (a status reply).
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
        Instant receivedAt,
        Ref factRef) {

    /** A message whose outcome is the fact (no separate fact reference). */
    public PaymentMessage(
            Ref provider,
            MessageType type,
            Ref id,
            Ref providerRef,
            Ref paymentRef,
            Outcome outcome,
            Money amount,
            Instant sentAt,
            Instant receivedAt) {
        this(provider, type, id, providerRef, paymentRef, outcome, amount, sentAt, receivedAt, null);
    }

    public PaymentMessage {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
