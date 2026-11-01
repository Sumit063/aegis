package dev.aegis.domain.payment;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One payment (one provider transaction) and every message about it, in arrival order (one database
 * row, LLD §7). Entries are an append-only history: a later refund never erases the capture before it.
 *
 * <p>{@code status} is the payment's lifecycle state: PENDING, SUCCESS (captured), FAILED or REVERSED,
 * the last of these that passed, or null if none has. Refunds are not a state: they are facts on a
 * captured payment ({@link #refunded}), so a partial refund never hides the capture. {@code id}, {@code validationId} and {@code paymentRef} are null for untrusted or
 * unmatched messages.
 */
public record Payment(Long id, Long validationId, Ref provider, Ref paymentRef, Outcome status, List<Entry> entries) {

    /** Delivery of a passed message to the application. */
    public enum Forward {
        NONE,
        PENDING,
        DELIVERED,
        FAILED
    }

    /**
     * One distinct message. Repeats of the same message only increase {@code duplicates}.
     *
     * @param amount the amount the message states (for a refund, the refunded amount), or null
     * @param factRef which fact of its kind it reports (e.g. the refund ID), or null
     */
    public record Entry(
            int n,
            MessageType type,
            Ref messageId,
            Instant at,
            Outcome outcome,
            Verdict verdict,
            int duplicates,
            Forward forward,
            Money amount,
            Ref factRef) {

        public Entry {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(forward, "forward");
        }

        public Entry(
                int n,
                MessageType type,
                Ref messageId,
                Instant at,
                Outcome outcome,
                Verdict verdict,
                int duplicates,
                Forward forward) {
            this(n, type, messageId, at, outcome, verdict, duplicates, forward, null, null);
        }

        Entry withDuplicates(int count) {
            return new Entry(n, type, messageId, at, outcome, verdict, count, forward, amount, factRef);
        }
    }

    public Payment {
        Objects.requireNonNull(provider, "provider");
        entries = List.copyOf(entries);
    }

    public static Payment open(Long validationId, Ref provider, Ref paymentRef) {
        return new Payment(null, validationId, provider, paymentRef, null, List.of());
    }

    /**
     * A message that failed before it could be trusted (bad signature, unparseable). It gets its own
     * payment and an unverified ID, so it can never write into or shadow a genuine payment.
     */
    public static Payment untrusted(Ref provider, MessageType type, BlockReason reason, Instant at, UUID id) {
        Entry entry =
                new Entry(1, type, Ref.unverified(id), at, Outcome.OTHER, Verdict.blocked(reason), 0, Forward.NONE);
        return new Payment(null, null, provider, null, null, List.of(entry));
    }

    public Optional<Entry> findEntry(Ref messageId) {
        return entries.stream().filter(e -> e.messageId().equals(messageId)).findFirst();
    }

    /** Records a re-delivery of a message already seen; nothing else changes. */
    public Payment countRepeat(Ref messageId) {
        List<Entry> updated = entries.stream()
                .map(e -> e.messageId().equals(messageId) ? e.withDuplicates(e.duplicates() + 1) : e)
                .toList();
        return new Payment(id, validationId, provider, paymentRef, status, updated);
    }

    /** Appends a message with its verdict; a passed state change becomes the payment's status. */
    public Payment append(PaymentMessage message, Verdict verdict) {
        Forward forward = verdict.passed() && message.type().forwarded() ? Forward.PENDING : Forward.NONE;
        List<Entry> updated = new ArrayList<>(entries);
        updated.add(new Entry(
                entries.size() + 1,
                message.type(),
                message.id(),
                message.receivedAt(),
                message.outcome(),
                verdict,
                0,
                forward,
                message.amount(),
                message.factRef()));
        Outcome next = verdict.passed() ? advance(message) : status;
        return new Payment(id, validationId, provider, paymentRef, next, updated);
    }

    /** A payment request went out and no final answer (success, failure, refund) has passed yet. */
    public boolean isInFlight() {
        boolean requested = entries.stream()
                .anyMatch(
                        e -> e.type() == MessageType.PAY_REQUEST && e.verdict().passed());
        return requested && (status == null || status == Outcome.PENDING);
    }

    /** Whether a capture ever passed, whatever happened after (refunds keep this true). */
    public boolean wasCaptured() {
        return passed().anyMatch(e -> e.outcome() == Outcome.SUCCESS);
    }

    /**
     * The sum of refund facts that passed, in {@code currency}: refunds the provider reported as news
     * (webhooks, callbacks). A status reply's cumulative total is an observation, not another refund.
     */
    public Money refunded(Currency currency) {
        return passed().filter(e -> e.outcome() == Outcome.REFUNDED && e.type().forwarded() && e.amount() != null)
                .map(Entry::amount)
                .reduce(Money.zero(currency), Money::plus);
    }

    /** Partial refunds leave something of {@code paid}; a full refund leaves nothing. */
    public boolean fullyRefunded(Money paid) {
        return !paid.isGreaterThan(refunded(paid.currency()));
    }

    /** Whether a passed message already reported this outcome with this fact reference. */
    public boolean hasFact(Outcome outcome, Ref factRef) {
        return passed().anyMatch(e -> e.outcome() == outcome && Objects.equals(e.factRef(), factRef));
    }

    /** Entries of one type, e.g. earlier status checks when enforcing provider limits. */
    public List<Entry> entries(MessageType type) {
        return entries.stream().filter(e -> e.type() == type).toList();
    }

    private Stream<Entry> passed() {
        return entries.stream().filter(e -> e.verdict().passed());
    }

    // The request's own acknowledgement is not news about the payment; only the provider's answers are.
    // A refund is a fact on the captured payment, not a new state.
    private Outcome advance(PaymentMessage message) {
        boolean news = message.outcome() != Outcome.OTHER
                && message.outcome() != Outcome.REFUNDED
                && message.type() != MessageType.PAY_REQUEST;
        return news ? message.outcome() : status;
    }
}
