package dev.aegis.domain.check;

import static dev.aegis.domain.message.BlockReason.AMOUNT_MISMATCH;
import static dev.aegis.domain.message.BlockReason.DOWNGRADE;
import static dev.aegis.domain.message.BlockReason.DUPLICATE;
import static dev.aegis.domain.message.BlockReason.DUPLICATE_PAYMENT;
import static dev.aegis.domain.message.BlockReason.NO_VALIDATION;
import static dev.aegis.domain.message.BlockReason.RATE_LIMIT;
import static dev.aegis.domain.message.BlockReason.REPLAY;

import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The rules (LLD §3–§5), each one named, small and tested on its own. */
public final class Checks {

    /** Older than the provider's retry window: a replay. Messages without a send time skip this. */
    public static final Check FRESH = c -> block(
            c.message().sentAt() != null
                    && c.message().sentAt().isBefore(c.now().minus(c.policy().freshness())),
            REPLAY);

    /** Refers to a validation the provider accepted. */
    public static final Check VALIDATED =
            c -> block(c.validation() == null || c.validation().status() == Validation.Status.FAILED, NO_VALIDATION);

    /**
     * The amount, when the message carries one, is exactly the validated amount and currency. Refunds
     * and reversals state their own amount and are checked by {@link #REFUND_WITHIN_PAYMENT}. Runs
     * after VALIDATED.
     */
    public static final Check AMOUNT_MATCHES = c -> {
        Outcome outcome = c.message().outcome();
        boolean ownAmount = outcome == Outcome.REFUNDED || outcome == Outcome.REVERSED;
        return block(
                !ownAmount
                        && c.message().amount() != null
                        && !c.message().amount().equals(c.validation().amount()),
                AMOUNT_MISMATCH);
    };

    /** Refunds, partial or several, never add up to more than was paid, and are in its currency. */
    public static final Check REFUND_WITHIN_PAYMENT = c -> {
        Money refund = c.message().amount();
        if (c.message().outcome() != Outcome.REFUNDED || refund == null) {
            return Optional.empty();
        }
        Money paid = c.validation().amount();
        if (!refund.currency().equals(paid.currency())) {
            return Optional.of(AMOUNT_MISMATCH);
        }
        Money already =
                c.payment() == null ? Money.zero(paid.currency()) : c.payment().refunded(paid.currency());
        return block(already.plus(refund).isGreaterThan(paid), AMOUNT_MISMATCH);
    };

    /**
     * Once a validation is paid, only the payment that paid it moves on. Another attempt succeeding is
     * a second payment (the merchant must refund it, not fulfil again); its late failure or pending
     * state is stale.
     */
    public static final Check NOT_PAID_ELSEWHERE = c -> {
        boolean paidElsewhere = c.validation().status() == Validation.Status.PAID
                && (c.payment() == null || !c.payment().wasCaptured());
        if (!paidElsewhere) {
            return Optional.empty();
        }
        return switch (c.message().outcome()) {
            case SUCCESS -> Optional.of(DUPLICATE_PAYMENT);
            case PENDING, FAILED -> Optional.of(DOWNGRADE);
            default -> Optional.empty();
        };
    };

    /**
     * The outcome may follow the payment's current state ({@link Transitions}). The same fact again is
     * a DUPLICATE only for messages that would be forwarded; a status-check reply confirming the
     * current state is simply an answer.
     */
    public static final Check TRANSITION = c -> {
        Payment payment = c.payment();
        Outcome current = payment == null ? null : payment.status();
        boolean seen = payment != null
                && payment.hasFact(c.message().outcome(), c.message().factRef());
        Optional<BlockReason> reason = Transitions.check(current, c.message().outcome(), seen, c.policy());
        boolean confirmation = reason.isPresent()
                && reason.get() == DUPLICATE
                && !c.message().type().forwarded();
        return confirmation ? Optional.empty() : reason;
    };

    /** No second payment request after success or while another one is still pending. Runs after VALIDATED. */
    public static final Check PAYABLE = c -> block(
            !c.validation().isPayable() || c.siblings().stream().anyMatch(Payment::isInFlight), DUPLICATE_PAYMENT);

    /** Status checks stay within the provider's limits, so the application is never penalised. */
    public static final Check WITHIN_LIMITS = c -> {
        Payment payment = c.payment();
        if (payment == null) {
            return Optional.empty();
        }
        Check.Limits limits = c.limits();
        Optional<Instant> requested = payment.entries(MessageType.PAY_REQUEST).stream()
                .map(Payment.Entry::at)
                .findFirst();
        boolean tooEarly =
                requested.isPresent() && c.now().isBefore(requested.get().plus(limits.firstAfter()));
        long recent = payment.entries(MessageType.STATUS_CHECK).stream()
                .filter(e -> e.at().isAfter(c.now().minus(limits.window())))
                .count();
        return block(tooEarly || recent >= limits.maxChecks(), RATE_LIMIT);
    };

    private Checks() {}

    /** Runs the rules in order; the first one that blocks decides. */
    public static Verdict run(List<Check> checks, Check.Context context) {
        return checks.stream()
                .map(check -> check.apply(context))
                .flatMap(Optional::stream)
                .findFirst()
                .map(Verdict::blocked)
                .orElse(Verdict.PASSED);
    }

    private static Optional<BlockReason> block(boolean condition, BlockReason reason) {
        return condition ? Optional.of(reason) : Optional.empty();
    }
}
